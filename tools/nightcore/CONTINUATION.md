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

## Phase 2 operational cycle

The read-only Actions job discovers the current PR #2–#4 SHAs, then inspects
the newest native CI run for the exact PR #3 head. It reads the latest
available Phase 2 checkpoint from the same workflow and recovers missing
audit reports only when the Android instrumentation step succeeded. Archive
ownership, run ID, SHA256 and size are checked before reading selected files.
Result `PASS` concerns the archive audit only. `HOLD` records unresolved
conditions, `FAIL` records unsuccessful repair, and `SKIPPED` references an
earlier outcome for the same head and fault. One repair per run is sufficient
for the only supported correction. Deduplication depends on retained Actions
checkpoints (14 days). Expired evidence or a failed archive request must not
be treated as successful verification.

The existing workflow has a manual dispatch and a twice-daily schedule
(00:17/12:17 UTC, 09:17/21:17 JST). The operational job serializes invocations,
does not enqueue other workflows, and only reads GitHub using the job's
built-in read token. A scheduled run reads the operational branch while
GitHub requires the schedule definition on the default branch. The draft PR
does not enable that default-branch trigger. Existing ChatGPT tasks and
NIGHT WATCH can continue their broader development and supervisory work.

## Phase 3 PR supervision

The supervisor discovers open PRs through the GitHub API. It excludes its own
experimental branch to avoid observing and reporting its own output. A result
applies only when the PR number, head and base SHA match the workflow run.
The generic observation and reporting rules live in supervision.py. Android
diagnostics, JUnit parsing and audit recovery live in nightprint_monitor.py.
A second project can keep the first set of rules and supply its own evidence
adapter.

The manual entry point is the existing evidence workflow on
experiment/nightcore-supervisor-v3. Read phase3.json and summary.md from the
nightcore-phase3-state artifact. It includes the previous checkpoint's artifact
ID when resuming. If a copy of the same run is requested, the previous attempt
can be read from its separately named artifact. Checkpoints expire after 14
days. Restart after expiry requires a fresh observation. An interrupted repair
with a saved reservation is held when the input stays the same.

The only automatic repair is reconstructing missing audit JSON from vetted
Android logcat and rerunning the audit validator. This does not change the app,
rerun its build, or approve a release. ADB disconnects, failed tests, provenance
gaps and unknown log patterns require investigation. The supervisor never
dispatches a different workflow. It reads at most 25 open PRs, keeps one current
run per workflow, and records evidence gaps when listing limits are reached.

The proposed nightly invocation is 02:17 JST. GitHub requires the workflow on
the default branch to deliver schedule events. Activation also requires the
repository variable NIGHTCORE_PHASE3_SCHEDULE_ENABLED to be true. Review the
default-branch proposal and confirm an actual schedule run before treating it
as operational. The job checks out the triggering commit, serializes
invocations, caps its scan at four minutes and uploads evidence even after
ordinary failures. A forced runner stop can prevent uploads; the next run then
uses the most recent retained checkpoint.
