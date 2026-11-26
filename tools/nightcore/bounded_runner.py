"""Bounded local command runner. No AI credentials or automatic deployment."""
import argparse
import json
import subprocess
import time
from pathlib import Path

def run(command, output, attempts=3, timeout=120):
    if not 1 <= attempts <= 3 or timeout <= 0:
        raise ValueError("invalid execution budget")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    records = []
    for i in range(1, attempts + 1):
        started = time.time()
        try:
            p = subprocess.run(command, capture_output=True, timeout=timeout)
            code, log = p.returncode, p.stdout + p.stderr
        except subprocess.TimeoutExpired as e:
            code, log = 124, (e.stdout or b"") + (e.stderr or b"")
        (output / f"attempt-{i}.log").write_bytes(log)
        records.append({"attempt": i, "exit": code, "seconds": round(time.time()-started, 2)})
        state = {"attempts": records, "release_authorized": False}
        tmp = output / "state.tmp"
        tmp.write_text(json.dumps(state, indent=2))
        tmp.replace(output / "state.json")
        # Retry only timeouts; deterministic failures require a changed hypothesis.
        if code != 124:
            break
    return code

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("command", nargs="+")
    args = parser.parse_args()
    raise SystemExit(run(args.command, args.output, timeout=args.timeout))
