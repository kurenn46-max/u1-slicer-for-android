"""Collect one bounded GitHub observation and resume the NIGHT CORE checkpoint."""
import argparse
import json
import os
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from xml.etree.ElementTree import ParseError
from zipfile import BadZipFile

from github_cycle import API, REPO, api, archive
from nightprint_monitor import NATIVE_WORKFLOW, classify_failure, inspect_native
from operational_loop import write_checkpoint
from supervision import current_runs, dependencies, difference, render_summary, summarize_checks

WORKFLOW = "actions/workflows/nightcore-evidence-gate.yml/runs"
BRANCH = "experiment/nightcore-supervisor-v3"
CHECKPOINT = "phase3.json"
BUDGET = 25
DIAGNOSTICS_VERSION = 3
API_ERRORS = (OSError, ValueError, subprocess.SubprocessError, BadZipFile, ParseError)
UNSUCCESSFUL = {"failure", "timed_out", "cancelled", "action_required", "startup_failure"}
RUN_FIELDS = ("id", "workflow_id", "attempt", "path", "url", "name", "status", "conclusion")


def read_previous(branch, default_branch, run_id, attempt):
    candidates = {}
    for query in ({"branch": branch, "per_page": 10},
                  {"branch": default_branch, "event": "schedule", "per_page": 5}):
        for run in api(f"{WORKFLOW}?{urlencode(query)}")["workflow_runs"]:
            if run["id"] < run_id and run["status"] == "completed":
                candidates[run["id"]] = run
            elif run["id"] == run_id and attempt > 1:
                candidates[run_id] = run
    for run in sorted(candidates.values(), key=lambda entry: entry["id"], reverse=True):
        checkpoints = []
        for artifact in api(f"actions/runs/{run['id']}/artifacts?per_page=50")["artifacts"]:
            suffix = re.fullmatch(r"nightcore-phase3-state-([0-9]+)", artifact["name"])
            if not suffix or artifact["expired"]:
                continue
            saved_attempt = int(suffix[1])
            if run["id"] != run_id or saved_attempt < attempt:
                checkpoints.append((saved_attempt, artifact))
        if checkpoints:
            saved_attempt, artifact = max(checkpoints, key=lambda item: item[0])
            with archive(artifact, run["id"], run["head_sha"]) as bundle:
                previous = json.loads(bundle.read(CHECKPOINT))
            if (previous.get("schema") != 3 or previous.get("repository") != REPO
                    or previous.get("run_id") != run["id"] or previous.get("run_attempt") != saved_attempt):
                raise ValueError("invalid Phase 3 checkpoint")
            if previous.get("previous_loaded") is not False:
                return previous, artifact["id"]
    return None, None


def snapshot(pr):
    return {
        "number": pr["number"], "title": pr["title"], "url": pr["html_url"],
        "draft": pr["draft"], "head_sha": pr["head"]["sha"], "base_sha": pr["base"]["sha"],
        "head_ref": pr["head"]["ref"], "base_ref": pr["base"]["ref"],
        "head_repo_id": (pr["head"]["repo"] or {}).get("id"),
        "base_repo_id": pr["base"]["repo"]["id"],
        "checkout_sha": pr["merge_commit_sha"], "file_count": pr["changed_files"],
    }


def list_prs():
    opened = api(f"pulls?state=open&per_page={BUDGET + 1}")
    if len(opened) > BUDGET:
        raise ValueError("open PR monitoring limit exceeded")
    return [
        snapshot(api(f"pulls/{pr['number']}")) for pr in opened
        if not (pr["head"]["ref"] == BRANCH and (pr["head"]["repo"] or {}).get("full_name") == REPO)
    ]


def collect_files(pr, prior):
    if all(pr[key] == prior.get(key) for key in ("head_sha", "base_sha", "file_count")):
        return prior["files"]
    files = api(f"pulls/{pr['number']}/files?per_page=100")
    live = snapshot(api(f"pulls/{pr['number']}"))
    if any(live[key] != pr[key] for key in ("head_sha", "base_sha", "file_count")):
        raise ValueError("PR changed while collecting its files")
    return [{
        "path": file["filename"], "previous_path": file.get("previous_filename"),
        "status": file["status"], "additions": file["additions"], "deletions": file["deletions"],
    } for file in files]


def run_view(run):
    return {
        "id": run["id"], "workflow_id": run["workflow_id"], "attempt": run["run_attempt"],
        "path": run["path"], "url": run["html_url"], "name": run["name"],
        "status": run["status"], "conclusion": run["conclusion"],
    }


def failed_job_log(job):
    job_id = str(int(job["id"]))
    # Job-log redirects may be unavailable even when a run's log archive works.
    for command in (["gh", "api", f"{API}/actions/jobs/{job_id}/logs"],
                    ["gh", "run", "view", "--repo", REPO, "--job", job_id, "--log"]):
        try:
            result = subprocess.check_output(command, timeout=12, stderr=subprocess.DEVNULL)
        except (OSError, subprocess.SubprocessError):
            continue
        if result.strip():
            return result[-250_000:].decode("utf-8", errors="replace")
    return None


def issue(run, kind, job=None):
    entry = {"kind": kind, "run_id": run["id"], "attempt": run["run_attempt"],
             "workflow_id": run["workflow_id"], "url": job["html_url"] if job else run["html_url"]}
    if job:
        entry["job_id"] = job["id"]
    return entry


def issue_origin(item):
    return tuple(item.get(field) for field in ("workflow_id", "run_id", "attempt", "job_id"))


def published_artifacts(run, artifacts):
    if run["conclusion"] != "success":
        return []
    return [{
        "name": artifact["name"], "id": artifact["id"], "digest": artifact["digest"],
        "size_in_bytes": artifact["size_in_bytes"], "expires_at": artifact["expires_at"], "url": f"{run['html_url']}/artifacts/{artifact['id']}",
    } for artifact in artifacts if artifact["digest"] and (
        re.fullmatch(r"nightprint-verified-qa[0-9]+-apk-.+", artifact["name"])
        or artifact["name"] == "nightcore-phase2-state"
        or artifact["name"] == f"nightcore-recovery-{run['id']}"
    )]


def review_run(run, pr, work, out, prior, source_sha):
    result = {"jobs": [], "issues": [], "artifacts": [], "tests": None, "audit": None}
    if run["status"] != "completed":
        return result
    run_id, attempt = run["id"], run["run_attempt"]
    jobs = api(f"actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=50")["jobs"]
    result["jobs"] = [{key: job[key] for key in ("id", "name", "conclusion", "html_url")} for job in jobs]
    if run["conclusion"] in UNSUCCESSFUL:
        failed = [job for job in jobs if job["conclusion"] in UNSUCCESSFUL]
        for job in failed[:2]:
            log = failed_job_log(job) if pr["head_repo_id"] == pr["base_repo_id"] else None
            diagnosis = issue(run, classify_failure(log), job)
            diagnosis["log_available"] = log is not None
            result["issues"].append(diagnosis)
        if not result["issues"]:
            result["issues"].append(issue(run, "unclassified_ci_failure"))
    artifacts = api(f"actions/runs/{run_id}/artifacts?per_page=50")["artifacts"]
    artifacts = [
        item for item in artifacts if not item["expired"] and item.get("workflow_run")
        and item["workflow_run"]["head_sha"] == pr["head_sha"]
        and item["workflow_run"]["id"] == run_id
        and item["workflow_run"]["repository_id"] == pr["base_repo_id"]
        and item["workflow_run"]["head_repository_id"] == pr["base_repo_id"]
    ]
    # GitHub's artifact metadata binds to a run ID, but has no run-attempt field.
    if attempt != 1 or pr["head_repo_id"] != pr["base_repo_id"]:
        return result
    if run["path"] != NATIVE_WORKFLOW:
        result["artifacts"] = published_artifacts(run, artifacts)
        return result
    try:
        audit, tests = inspect_native(run, jobs, artifacts, work, out, prior, source_sha, pr["checkout_sha"])
    except API_ERRORS as error:
        result["issues"].append(issue(run, "audit_provenance_unverified"))
        result["audit_error"] = str(error)
        return result
    result.update(audit=audit, tests=tests)
    verified = audit and (
        audit["status"] == "PASS" or audit["status"] == "SKIPPED" and audit["cause"] == "reports_present"
    )
    if verified and tests and not tests["failed"] and not tests["skipped"]:
        result["artifacts"] = published_artifacts(run, artifacts)
    elif run["conclusion"] == "success" or audit:
        result["issues"].append(issue(run, "android_evidence_unverified"))
    return result


def review(pr, prior, state, work, output, source_sha):
    number = str(pr["number"])
    runs_data = api(f"actions/runs?event=pull_request&head_sha={pr['head_sha']}&per_page=50")
    matched, stale = current_runs(pr, runs_data["workflow_runs"])
    views = [run_view(run) for run in matched]
    complete = runs_data["total_count"] <= len(runs_data["workflow_runs"])
    old_observation = prior["observation"] if prior else None
    changes = difference(pr, old_observation)
    if prior and views != [{key: run[key] for key in RUN_FIELDS} for run in prior["runs"]]:
        changes.append("ci")
    if prior and (stale != prior["stale_runs"] or complete != prior["ci_window_complete"]):
        changes.append("ci_coverage")
    if prior and prior.get("diagnostics_version") != DIAGNOSTICS_VERSION:
        changes.append("diagnostics")
    if prior and any(datetime.fromisoformat(item["expires_at"]) <= datetime.now(timezone.utc)
                     for item in prior["artifacts"]):
        changes.append("artifact_expired")
    if prior and not changes and number not in state["pending"]:
        return {**prior, "changes": [], "resolved": [], "repairs": []}
    work.mkdir()
    fingerprint = [pr["head_sha"], pr["base_sha"], [[run["id"], run["attempt"]] for run in views]]
    interrupted = state["pending"].get(number) == fingerprint
    state["pending"][number] = fingerprint
    save(output, state)

    checks = summarize_checks(matched)
    issues, artifacts, jobs, resolved, repairs = [], [], {}, [], []
    audit = tests = None
    audit_checkpoint = prior.get("audit_checkpoint", prior["audit"]) if prior else None
    if interrupted:
        issues.append({"kind": "interrupted_review", "url": pr["url"]})
    else:
        for run in matched:
            audit_output = output / f"audit-pr-{number}"
            details = review_run(run, pr, work, audit_output, audit_checkpoint, source_sha)
            if details["audit"]:
                audit_checkpoint = details["audit"]
                # The source log already has a verified artifact link; keep the reports and hashes.
                (audit_output / "recovery" / "evidence" / "native-android-logcat.txt").unlink(missing_ok=True)
            issues.extend(details["issues"])
            artifacts.extend(details["artifacts"])
            jobs[run["id"]] = details["jobs"]
            if run["path"] == NATIVE_WORKFLOW:
                audit, tests = details["audit"], details["tests"]
                if "audit_error" in details:
                    issues[-1]["detail"] = details["audit_error"]
        if audit and audit["attempts"] and audit["status"] == "PASS":
            repairs.append({"action": "restore_missing_audit_json_and_revalidate", "attempts": audit["attempts"],
                            "evidence": f"audit-pr-{number}/recovery/checkpoint.json"})
        passing = {run["workflow_id"] for run in matched if run["conclusion"] == "success"}
        retained_origins = {issue_origin(item) for item in issues}
        unresolved_workflows = {item.get("workflow_id") for item in issues}
        for old in prior["issues"] if prior else []:
            origin = issue_origin(old)
            if origin in retained_origins:
                continue
            workflow = old.get("workflow_id")
            if workflow in passing - unresolved_workflows:
                resolved.append(old)
            elif workflow:
                issues.append({**old, "historical": True})
                retained_origins.add(origin)
    if any(not parent["base_matches"] for parent in pr["dependencies"]):
        issues.append({"kind": "dependency_changed", "url": pr["url"]})
    if not complete:
        issues.append({"kind": "incomplete_ci_window", "url": pr["url"]})
    if not pr["files_complete"]:
        issues.append({"kind": "incomplete_file_inventory", "url": pr["url"]})
    if issues and checks == "PASS":
        checks = "HOLD"
    if any(item["kind"] == "adb_disconnect" and not item.get("historical") for item in issues):
        next_action = "ADB切断の診断ログを確認し、対象コミットのAndroid CIを再検証する"
    elif issues:
        next_action = "保留した問題の実行証拠を確認し、原因が特定できたものから対応する"
    elif checks in ("UNVERIFIED", "PENDING"):
        next_action = "現在のヘッドとベースを対象とするCI結果を待つ"
    else:
        next_action = "新しいコミットまたはCI結果を待つ"
    return {
        "observation": pr, "changes": changes, "checks": checks, "stale_runs": stale,
        "ci_window_complete": complete, "runs": [{**view, "jobs": jobs.get(view["id"], [])} for view in views],
        "issues": issues, "resolved": resolved, "repairs": repairs,
        "artifacts": artifacts, "audit": audit, "audit_checkpoint": audit_checkpoint,
        "tests": tests, "next_action": next_action, "diagnostics_version": DIAGNOSTICS_VERSION,
    }


def save(output, state):
    write_checkpoint(output / CHECKPOINT, state)
    (output / "summary.md").write_text(render_summary(state), encoding="utf-8")


def supervise(args):
    output = args.output
    output.mkdir(parents=True, exist_ok=False)
    state = {"schema": 3, "repository": REPO, "run_id": args.run_id, "run_attempt": args.attempt,
             "status": "INTERRUPTED", "previous_loaded": False, "prs": {}, "pending": {}, "closed": [],
             "release_authorized": False}
    save(output, state)
    try:
        previous, checkpoint = read_previous(BRANCH, args.default_branch, args.run_id, args.attempt)
        if previous:
            state.update(prs=previous["prs"].copy(), pending=previous["pending"].copy(),
                         resumed_from={"run_id": previous["run_id"], "artifact_id": checkpoint})
        state["previous_loaded"] = True
        save(output, state)
        args.work.mkdir(parents=True)
        source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        state["source_sha"] = source_sha
        prs = list_prs()
        for pr in sorted(prs, key=lambda item: item["number"]):
            number = str(pr["number"])
            prior = state["prs"].get(number)
            pr["files"] = collect_files(pr, prior["observation"] if prior else {})
            pr["files_complete"] = len(pr["files"]) == pr["file_count"]
            pr["dependencies"] = dependencies(pr, prs)
            state["prs"][number] = review(pr, prior, state, args.work / f"pr-{number}", output, source_sha)
            state["pending"].pop(number, None)
            save(output, state)
        state["closed"] = sorted(set(state["prs"]) - {str(pr["number"]) for pr in prs}, key=int)
        for number in state["closed"]:
            del state["prs"][number]
            state["pending"].pop(number, None)
        state["counts"] = dict(Counter(pr["checks"] for pr in state["prs"].values()))
        changed = state["closed"] or any(pr["changes"] for pr in state["prs"].values())
        state["status"] = "UPDATED" if changed else "NO_CHANGES"
    except (KeyError, TypeError, *API_ERRORS) as error:
        state.update(status="ERROR", error=f"{type(error).__name__}: {error}")
    save(output, state)
    print(json.dumps({"status": state["status"], "counts": state.get("counts"),
                      "prs": list(state["prs"]), "resumed_from": state.get("resumed_from"),
                      "error": state.get("error")}, ensure_ascii=False))
    return int(state["status"] == "ERROR")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--attempt", type=int, default=int(os.getenv("GITHUB_RUN_ATTEMPT", "1")))
    parser.add_argument("--default-branch", required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return supervise(parser.parse_args())


if __name__ == "__main__":
    raise SystemExit(main())
