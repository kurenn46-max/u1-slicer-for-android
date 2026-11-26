"""One NIGHT CORE cycle using GitHub observations and existing audit recovery."""
import hashlib
import json

from recover_evidence import run
from verify_native_evidence import FILES, validate


def write_checkpoint(path, state):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def cycle(observation, evidence, output, previous, recovery_sha):
    output.mkdir(parents=True, exist_ok=False)
    history = previous["history"] if previous else {}
    checkpoint = output / "phase2.json"
    state = {
        "schema": 1,
        "observation": observation,
        "previous_observation": previous["observation"] if previous else None,
        "history": history.copy(),
        "status": "HOLD",
        "attempts": 0,
        "release_authorized": False,
    }
    state["changed_fields"] = [
        name for name, value in observation.items()
        if (state["previous_observation"] or {}).get(name) != value
    ]
    run_id = observation.get("run_id")
    issues = validate(evidence) if evidence else []
    cause = (
        "instrumentation_not_successful" if observation.get("instrumentation") != "success"
        else "evidence_unavailable" if evidence is None
        else "audit_evidence_invalid_or_incomplete" if issues
        else "reports_present"
    )
    # Commit and fault class identify a repeated fault across CI reruns.
    fingerprint = hashlib.sha256(json.dumps(
        [observation["head_sha"], cause, issues], sort_keys=True
    ).encode()).hexdigest()
    state.update(cause=cause, fingerprint=fingerprint, failures=issues)
    if fingerprint in history:
        state.update(status="SKIPPED", prior_result=history[fingerprint],
                     next_action="Continue when the observed commit or fault changes.")
    else:
        state["next_action"] = "Inspect the original CI run or obtain complete evidence."
        if cause in ("reports_present", "audit_evidence_invalid_or_incomplete"):
            result = output / "recovery"
            missing = [name for name in FILES if not (evidence / name).exists()]
            if issues and len(issues) == len(missing):
                state["history"][fingerprint] = {"status": "INTERRUPTED", "run_id": run_id}
                state["next_action"] = "Inspect any interrupted repair before reprocessing this fault."
            write_checkpoint(checkpoint, state)
            code = run(evidence, result, observation["checkout_sha"],
                       observation["artifact_url"], 0, recovery_sha)
            recovery = json.loads((result / "checkpoint.json").read_text())
            state.update(
                status="PASS" if code == 0 else ("FAIL" if recovery["attempts"] else "HOLD"),
                attempts=len(recovery["attempts"]), exit=code,
                next_action=recovery["next_action"],
            )
        state["history"][fingerprint] = {"status": state["status"], "run_id": run_id}
    write_checkpoint(checkpoint, state)
    return state
