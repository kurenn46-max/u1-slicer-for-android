"""Record PR-bound Android product evidence without implying release approval."""
import argparse
import json
import os
from pathlib import Path
import re
import sys


def log_lines(directory, filename):
    path = directory / filename
    return path.read_text(encoding="utf-8", errors="replace").splitlines() if path.is_file() else []


def ui_evidence(directory):
    outcomes = [
        match[1] for line in log_lines(directory, "export-logcat.txt")
        if (match := re.fullmatch(
            r"I/NIGHTPRINT_PETG_E2E\s*\(\s*\d+\): (status=HOLD reason=unqualified_startup_heat|status=VERIFIED export_bytes_match=true)",
            line.strip(),
        ))
    ]
    if len(outcomes) != 1:
        raise ValueError("Expected exactly one PETG UI outcome from the current instrumentation")
    outcome = outcomes[0]
    (directory / "petg-outcome.txt").write_text(outcome + "\n")
    print(outcome, file=sys.stderr)
    return outcome.split()[0].split("=")[1]


def quality_evidence(directory):
    quality = {}
    for line in log_lines(directory, "native-android-logcat.txt"):
        match = re.fullmatch(r"I/NIGHTPRINT_QUALITY\s*\(\s*\d+\): (\{.*\})", line.strip())
        if not match:
            continue
        item = json.loads(match[1])
        kind = item["format"]
        if kind in quality or kind not in ("stl", "3mf"):
            raise ValueError(f"Unexpected or duplicate native quality evidence: {kind}")
        if (item["engine"] != "real_android_native_jni" or item["geometry_passed"] is not True
                or item["recipe_passed"] is not True or item["safety"] not in ("HOLD", "VERIFIED")
                or not re.fullmatch("[0-9a-f]{64}", item["gcode_sha256"]) or item["gcode_bytes"] <= 1000):
            raise ValueError(f"Invalid native G-code audit: {kind}")
        quality[kind] = item
    if set(quality) != {"stl", "3mf"}:
        raise ValueError("Missing STL or 3MF native quality evidence")
    for kind, item in quality.items():
        (directory / f"petg-{kind}-quality.json").write_text(json.dumps(item, indent=2) + "\n")
    return quality


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("directory", type=Path)
    mode = cli.add_mutually_exclusive_group(required=True)
    mode.add_argument("--verify", action="store_true")
    mode.add_argument("--summary", action="store_true")
    args = cli.parse_args()
    directory = args.directory
    directory.mkdir(exist_ok=True)
    status, quality, errors = "UNVERIFIED", {}, {}
    for name, read in (("ui", ui_evidence), ("native", quality_evidence)):
        try:
            if name == "ui":
                status = read(directory)
            else:
                quality = read(directory)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            errors[name] = str(exc)
    if args.verify:
        for name, error in errors.items():
            print(f"{name}: {error}", file=sys.stderr)
        return int(bool(errors))

    exit_file = directory / "exit-code.txt"
    exit_code = exit_file.read_text().strip() if exit_file.is_file() else None
    passed = exit_code == "0" and not errors
    result = "PASS" if passed else "FAIL" if exit_code not in (None, "0") else "UNVERIFIED"
    if not (directory / "ui-junit-passed").is_file():
        status = "UNVERIFIED"
    run_url = f"{os.getenv('GITHUB_SERVER_URL', 'https://github.com')}/{os.getenv('GITHUB_REPOSITORY')}/actions/runs/{os.getenv('GITHUB_RUN_ID')}"
    unknown = ["物理A1 miniでの印刷", "造形後の嵌合寸法", "外部受信アプリへのG-code共有"]
    if status == "HOLD":
        unknown.append("対象PETG材料と対応ファームウェアでの240/250℃開始条件と235℃復帰の実測")
    if not passed:
        next_task = "欠落または失敗したJUnit XML、G-code監査とADB診断ログを照合する。"
    elif status == "HOLD":
        next_task = "メーカーの開始レシピと対象材料の仕様を照合し、実機検証の承認を得てから開始昇温と復帰を測る。"
    else:
        next_task = "外部受信アプリへの共有と造形寸法を別の試験で検証する。"
    record = {
        "project": os.getenv("GITHUB_REPOSITORY"), "commit_sha": os.getenv("NIGHTPRINT_PR_HEAD"),
        "checkout_sha": os.getenv("GITHUB_SHA"), "ci_url": run_url,
        "instrumentation": result,
        "junit": {"native_tpu": 2, "native_petg": 2, "ui": 8} if passed else {},
        "petg_ui": status, "geometry": quality, "evidence_errors": errors,
        "release_authorized": False, "unverified": unknown, "next_codex_task": next_task,
    }
    (directory / "product-verification.json").write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n")
    print("### NIGHT PRINT 製品検証")
    print(f"- 対象 {record['commit_sha']} · [CI]({run_url})")
    print(f"- Android計測 {record['instrumentation']}／PETG出力 {status}")
    for name, item in quality.items():
        print(f"- {name.upper()} {item['gcode_bytes']} B、{item['layers']}層、出力監査 {item['safety']}")
    print("- 未検証 " + "、".join(unknown))
    print("- 次の指令 " + next_task)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
