"""NIGHT CORE pilot checkpoint classifier. No network or source mutation."""
import json
import re
import sys
from pathlib import Path

def classify(raw):
    attempts = re.findall(r"^attempt=(\d+) exit=(\d+)$", raw, re.M)
    commits = re.findall(r"^commit=([0-9a-f]{40})$", raw, re.M)
    if len(commits) != 1 or not attempts or len(attempts) > 3:
        return "INVALID_CHECKPOINT"
    if [int(a) for a, _ in attempts] != list(range(1, len(attempts)+1)):
        return "INVALID_CHECKPOINT"
    if any(int(code) == 0 for _, code in attempts[:-1]):
        return "INVALID_CHECKPOINT"
    code = int(attempts[-1][1])
    if code == 0:
        return "JVM_GATE_ONLY" if "JVM_PASS_ONLY;" in raw else "INVALID_CHECKPOINT"
    if "JVM_PASS_ONLY;" in raw:
        return "INVALID_CHECKPOINT"
    if code == 124:
        return "RESUME_WITH_BUDGET"
    if "DETERMINISTIC_FAILURE;" in raw:
        return "PROPOSE_PATCH"
    if "UNCLASSIFIED_FAILURE;" in raw:
        return "INVESTIGATE"
    if "TRANSIENT_FAILURE;" in raw:
        return "REVIEW_RETRY_BUDGET"
    return "INVESTIGATE"

if __name__ == "__main__":
    raw = Path(sys.argv[1]).read_text(encoding="utf-8")
    decision = classify(raw)
    print(json.dumps({"decision": decision, "release_authorized": False}))
    sys.exit(2 if decision == "INVALID_CHECKPOINT" else 0)
