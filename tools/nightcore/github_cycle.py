"""Discover NIGHT PRINT CI and run one read-only NIGHT CORE cycle."""
import argparse
import hashlib
import io
import json
import re
import subprocess
from pathlib import Path
from zipfile import ZipFile

from operational_loop import cycle
from recover_evidence import LOG
from verify_native_evidence import FILES

REPO = "kurenn46-max/u1-slicer-for-android"
API = f"repos/{REPO}"
REPOSITORY_ID = 1390875009
NATIVE_WORKFLOW = "nightprint-arm64-stage3.yml"


def api(path):
    return json.loads(subprocess.check_output(["gh", "api", f"{API}/{path}"], timeout=30))


def archive(artifact, run_id, head_sha):
    if not 0 < artifact["size_in_bytes"] <= 2_000_000:
        raise ValueError("evidence archive is outside the size budget")
    source = artifact["workflow_run"]
    if (artifact["expired"] or source["repository_id"] != REPOSITORY_ID
            or source["head_repository_id"] != REPOSITORY_ID
            or source["id"] != run_id or source["head_sha"] != head_sha):
        raise ValueError("artifact provenance mismatch")
    result = subprocess.run(
        ["gh", "api", f"{API}/actions/artifacts/{int(artifact['id'])}/zip"],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60, check=True,
    )
    if "sha256:" + hashlib.sha256(result.stdout).hexdigest() != artifact["digest"]:
        raise ValueError("artifact SHA256 mismatch")
    bundle = ZipFile(io.BytesIO(result.stdout))
    contents = bundle.infolist()
    if (sum(member.file_size for member in contents) > 8_000_000
            or len(bundle.namelist()) != len(set(bundle.namelist()))):
        bundle.close()
        raise ValueError("evidence archive has invalid entries or size")
    return bundle


def find_artifact(run_id, name):
    return next((item for item in api(f"actions/runs/{run_id}/artifacts")["artifacts"]
                 if item["name"] == name and not item["expired"]), None)


def previous_checkpoint(branch, current_run):
    workflow = "actions/workflows/nightcore-evidence-gate.yml/runs"
    isolated = api(f"{workflow}?branch={branch}&per_page=10")["workflow_runs"]
    scheduled = api(f"{workflow}?branch=main&event=schedule&per_page=5")["workflow_runs"]
    for run in sorted(isolated + scheduled, key=lambda entry: entry["id"], reverse=True):
        if run["id"] >= current_run or run["status"] != "completed":
            continue
        artifact = find_artifact(run["id"], "nightcore-phase2-state")
        if artifact:
            with archive(artifact, run["id"], run["head_sha"]) as bundle:
                previous = json.loads(bundle.read("phase2.json"))
            if previous.get("schema") != 1 or not isinstance(previous.get("history"), dict):
                raise ValueError("invalid previous checkpoint")
            return previous
    return None


def discover(work):
    prs = {str(number): api(f"pulls/{number}") for number in (2, 3, 4)}
    snapshot = {number: {"state": pr["state"], "head_sha": pr["head"]["sha"]}
                for number, pr in prs.items()}
    branch = prs["3"]["head"]["ref"]
    head = prs["3"]["head"]["sha"]
    runs = api(f"actions/workflows/{NATIVE_WORKFLOW}/runs?branch={branch}&head_sha={head}&per_page=10")
    match = next((run for run in runs["workflow_runs"] if run["head_sha"] == head), None)
    observation = {"prs": snapshot, "head_sha": head}
    if match is None:
        return observation, None
    run_id = match["id"]
    observation.update(run_id=run_id, run_attempt=match["run_attempt"],
                       conclusion=match["conclusion"], run_url=match["html_url"])
    if match["status"] != "completed":
        return observation, None
    jobs = api(f"actions/runs/{run_id}/attempts/{match['run_attempt']}/jobs")["jobs"]
    observation["instrumentation"] = next(
        (step["conclusion"] for job in jobs if job["name"].startswith("native-e2e /")
         for step in job["steps"] if step["name"].startswith("Real native Android instrumentation")),
        None,
    )
    if observation["instrumentation"] != "success":
        return observation, None
    artifact = find_artifact(run_id, f"nightprint-native-android-e2e-{run_id}")
    if artifact is None:
        return observation, None
    evidence = work / "input-evidence"
    evidence.mkdir()
    with archive(artifact, run_id, head) as bundle:
        for name in (*FILES, LOG, "tested-source.txt"):
            path = f"nightprint-e2e-ci/{name}"
            if path in bundle.namelist():
                (evidence / name).write_bytes(bundle.read(path))
    checkout = (evidence / "tested-source.txt").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{40}", checkout):
        raise ValueError("invalid Android checkout SHA")
    observation.update(checkout_sha=checkout,
                       artifact_url=f"https://github.com/{REPO}/actions/runs/{run_id}/artifacts/{artifact['id']}")
    return observation, evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.work.mkdir(parents=True, exist_ok=False)
    previous = previous_checkpoint(args.branch, args.run_id)
    observation, evidence = discover(args.work)
    recovery_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    state = cycle(observation, evidence, args.output, previous, recovery_sha)
    print(json.dumps({name: state[name] for name in ("status", "cause", "attempts", "changed_fields")}))
    return 1 if state["status"] == "FAIL" else 0


if __name__ == "__main__":
    raise SystemExit(main())
