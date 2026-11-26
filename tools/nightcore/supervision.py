"""Project-neutral pull request observations and human-readable reporting."""


def current_runs(pr, runs):
    """A stacked PR's base and head must both match the recorded CI inputs."""
    latest = {}
    stale = 0
    for run in runs:
        if run["head_sha"] != pr["head_sha"] or run["event"] != "pull_request":
            continue
        if not any(
            item["number"] == pr["number"]
            and item["head"]["sha"] == pr["head_sha"]
            and item["base"]["sha"] == pr["base_sha"]
            for item in run.get("pull_requests", [])
        ):
            stale += 1
            continue
        workflow = run["workflow_id"]
        if workflow not in latest or run["id"] > latest[workflow]["id"]:
            latest[workflow] = run
    return sorted(latest.values(), key=lambda run: run["workflow_id"]), stale


def dependencies(pr, prs):
    return [
        {"number": parent["number"], "head_sha": parent["head_sha"],
         "base_matches": parent["head_sha"] == pr["base_sha"]}
        for parent in prs
        if parent["head_ref"] == pr["base_ref"] and parent["head_repo_id"] == pr["base_repo_id"]
    ]


def difference(observation, prior):
    if prior is None:
        return ["new_pr"]
    return [key for key in observation if observation[key] != prior.get(key)]


def summarize_checks(runs):
    if any(run["conclusion"] in ("failure", "timed_out", "cancelled", "action_required", "startup_failure")
           for run in runs):
        return "FAIL"
    if any(run["status"] != "completed" for run in runs):
        return "PENDING"
    return "PASS" if runs and all(run["conclusion"] == "success" for run in runs) else "UNVERIFIED"


def render_summary(state):
    lines = ["# NIGHT CORE · 開発監督", "",
             f"判定 **{state['status']}**",
             f"対象 `{state['repository']}`", ""]
    if state["status"] == "NO_CHANGES":
        lines += ["変更なし", ""]
    if state.get("resumed_from"):
        previous = state["resumed_from"]
        url = f"https://github.com/{state['repository']}/actions/runs/{previous['run_id']}/artifacts/{previous['artifact_id']}"
        lines += [f"[前回のチェックポイント]({url})から再開", ""]
    if state.get("error"):
        lines += [state["error"], ""]
    if state.get("closed"):
        lines += ["監視対象から外れたPR " + ", ".join("#" + n for n in state["closed"]), ""]
    for pr in state.get("prs", {}).values():
        item = pr["observation"]
        files = ", ".join("`" + " ".join(file["path"].replace("`", "").split()) + "`" for file in item["files"][:5])
        lines += [f"### [PR #{item['number']}]({item['url']}) · {pr['checks']}",
                  f"- コミット `{item['head_sha'][:12]}` / ベース `{item['base_sha'][:12]}`",
                  f"- 変更ファイル {item['file_count']}件" + (f" — {files}" if files else ""),
                  "- 前回からの差分 " + (", ".join(pr["changes"]) or "変更なし")]
        if item["dependencies"]:
            lines.append("- 依存先 " + ", ".join(f"#{parent['number']}" for parent in item["dependencies"]))
        for run in pr["runs"]:
            lines.append(f"- [CI {run['id']}]({run['url']}) · {run['conclusion'] or run['status']}")
        if pr.get("tests"):
            tests = pr["tests"]
            lines.append(f"- Androidテスト 合格 {tests['passed']}／失敗 {tests['failed']}／スキップ {tests['skipped']}")
        for issue in pr["resolved"]:
            lines.append(f"- 解消 `{issue['kind']}` · [前回の実行証拠]({issue['url']})")
        for repair in pr["repairs"]:
            lines.append(f"- 復元後の再検証済み `{repair['evidence']}`")
        for issue in pr["issues"]:
            lines.append(f"- 未解決 `{issue['kind']}` · [実行証拠]({issue['url']})")
        for artifact in pr["artifacts"]:
            lines.append(f"- 成果物 [{artifact['name']}]({artifact['url']})")
        if pr.get("audit"):
            audit = pr["audit"]
            lines.append(f"- 監査証拠 {audit['status']} · 今回の修復 {audit['attempts']}回")
        lines += ["- 次の作業 " + pr["next_action"], ""]
    return "\n".join(lines) + "\n"
