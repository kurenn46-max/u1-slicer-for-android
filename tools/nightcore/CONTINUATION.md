# NIGHT CORE continuation contract

Reuse checkpoint.py and existing CI. Do not introduce another scheduler or paid API.

1. Read latest branch SHA and CI evidence before selecting work.
2. Prioritize native arm64 rebuild and Android JNI slice over feature additions.
3. For each iteration record input SHA, patch SHA, test command, exit code, artifact URL, and next action. Maximum three attempts per invocation. Never retry an identical deterministic failure without a changed hypothesis.
4. JVM_GATE_ONLY is not native or physical verification. Missing evidence is a blocker, not a pass.
5. Persist handoff in existing development records; Issues are disabled. Resume from evidence rather than conversation summaries.
6. Supervisor checks progress without competing writes. No automatic production merge, paid services, or printer operation.

Acceptance: demonstrate three evidence-backed iterations without an intervening user prompt. Direct model restart remains unverified; scheduled invocation is a fallback, not proof of continuous execution.
