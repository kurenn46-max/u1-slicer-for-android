# NIGHT CORE — architecture v0.1

Status: design contract; existing QA pilot is unverified. No production deployment.

## State machine
DISCOVER -> PLAN -> PATCH -> TEST -> REVIEW -> CHECKPOINT -> DISCOVER.
On deterministic failure, generate a new hypothesis, not an identical retry.
On transient failure, retry at most three times. On budget exhaustion, checkpoint and stop.
On all automated gates passing, produce a QA candidate; physical validation and release remain human-approved.

## Interfaces
- Project adapter: repository, isolated branch, pinned base SHA, allowed paths, test commands, release gates.
- Runner: immutable attempt ID, commit SHA, exit code, sanitized log, artifact references.
- AI worker: receives bounded evidence, proposes a patch and hypothesis; never receives secrets.
- Reviewer: independent evidence review; reject weakened tests, unrelated changes, secret access and release-gate bypass.
- Coordinator: one active attempt per project; deduplicate by commit and failure fingerprint; persist state across restarts.
- Trigger: verified CI completion event; ignore unrelated repositories and branches. No unbounded self-triggering.
- Budget: explicit compute and API limits; no paid service activation without approval. Default pilot manual only.
- Permissions: read-only CI, narrowly scoped patch writer on experimental branch; no main writes, automatic merge, deployment or printer access.

## Initial project
NIGHT PRINT GENESIS: preserve native ARM64/16 KiB/JNI, material contract and thermal gates. Reproduce TPU fallback, verify PETG and TPU effective settings. Physical Android/A1 mini tests remain separate.
HYBRID research stays separate until licensing and comparative evidence support integration.

## Milestones
1. Run existing bounded QA pilot and inspect evidence.
2. Implement deterministic checkpoint parser and fixture tests.
3. Connect an authorized AI execution provider with explicit budget and credential isolation.
4. Implement event-driven patch/test loop with duplicate detection and independent review.
5. Demonstrate recovery from a deliberately failing fixture before real source changes.
6. Add project adapters for other applications.

No claim of continuous operation until a real end-to-end loop is demonstrated.
