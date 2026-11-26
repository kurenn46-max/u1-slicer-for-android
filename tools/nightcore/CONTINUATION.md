# NIGHT CORE continuation contract

Reuse checkpoint.py and existing CI. Do not introduce another scheduler or paid API.

1. Read latest branch SHA and CI evidence before selecting work.
2. Prioritize native arm64 rebuild and Android JNI slice over feature additions.
3. For each iteration record input SHA, patch SHA, test command, exit code, artifact URL, and next action. Maximum three attempts per invocation. Never retry an identical deterministic failure without a changed hypothesis.
4. JVM_GATE_ONLY is not native or physical verification. Missing evidence is a blocker, not a pass.
5. Persist handoff in existing development records; Issues are disabled. Resume from evidence rather than conversation summaries.
6. Supervisor checks progress without competing writes. No automatic production merge, paid services, or printer operation.

Acceptance: demonstrate three evidence-backed iterations without an intervening user prompt. Direct model restart remains unverified; scheduled invocation is a fallback, not proof of continuous execution.

## Native evidence recovery

For a completed Android instrumentation run, use the audit directory from that
run's artifact. Determine the instrumentation exit from the trusted run log.
Use the Android checkout SHA and the current NIGHT CORE checkout SHA.

```bash
python3 tools/nightcore/recover_evidence.py INPUT_DIR --output NEW_OUTPUT_DIR \
  --source-sha ANDROID_CHECKOUT_SHA --recovery-sha NIGHT_CORE_CHECKOUT_SHA \
  --artifact-url ANDROID_ARTIFACT_URL --instrumentation-exit 0
```

The command snapshots the evidence, diagnoses missing or invalid reports, makes
one deterministic repair when fresh logcat can supply missing reports, then
reapplies the existing verifier. The original evidence stays intact. Reuse the
checkpoint for the same run instead of retrying an unchanged failure.
`EVIDENCE_PASS` means the reports passed the validator. It does not establish
authenticity on its own, rerun Android, or authorize release.

The hourly NIGHT PRINT worker and NIGHT WATCH already exist. This adapter
does not add scheduling or mutate application code. The experimental PR's
existing evidence CI exercises recovery using a SHA256-pinned Android artifact.
Automatic source repair, scheduled adapter execution and physical printing
require separate verification.
