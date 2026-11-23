# NIGHT PRINT GENESIS — Engineering team workboard

Updated 2026-11-23. This is a **role-based task board**, not a claim that six independent people or six parallel AI models are working. GitHub Issues are disabled on this repository (API 410), so review work packages and evidence are maintained here and in Draft PR #1.

**Current release verdict: HOLD (not production-ready).** No autonomous physical printing.

## Required review passes (all separate review records)

| ID | Technical owner role | Priority | Verification objective | Status | Verifiable evidence / blocker |
| --- | --- | --- | --- | --- | --- |
| NP-NATIVE-01 | Native C++/Kotlin integrator | P0 | Rebuild ARM64 `.so` with pinned NDK/Orca dependencies; true `.npjob`+3MF and raw STL native slices MUST declare TPU and issue bed 50°C when specified | **FAIL / BLOCKED** | E2E 45084208399: TPU→PLA on raw STL; 3MF bed 50→35. Source changed but packaged `.so` not rebuilt; release gate 45084208486 FAIL. Requires native deps ~1.8GB. |
| NP-MATERIAL-02 | TPU/PETG specialist | P0 | Material identity and manufacturer-supported TPU95A temps/speeds, drying/feeder and A1 mini 250°C purge; compare eSUN vs SUNLU and distinguish PETG | **NOT RUN** | Official A1 mini startup G-code has a conditional 250°C purge; this alone cannot certify a particular spool. Material-specific report not yet signed off. |
| NP-SAFETY-03 | G-code safety and adversarial tester | P0 | Validate startup vs layer heat, 50°C bed, PLA-in-TPU, machine, malformed syntax, cancellation, changed file SHA. Failed cases must block; approval never prints | **FAIL / OPEN** | The G-code audit caught bed 35°C in a 50°C job, proving current output is not safe to release. Expanded negative suite needed on real native output. |
| NP-CAD-04 | CAD, FDM and geometry reviewer | P1 | Rod-butt guard ID25/OD27/H32/wall1/bottom hole4; watertightness, clearances, 0.4 nozzle printability, actual fit | **PARTIAL** | Prior geometric audit used 160 cross sections. No proven toolpath or physical fit; user model must not be publicly uploaded without authorization. |
| NP-ANDROID-05 | Android ARM64 integration QA | P1 | Direct file-manager and in-app .npjob import, color black, A1 mini 180³, QA3 225/250/50°C review, cancel/approve, no implicit print | **PARTIAL** | User screenshot confirms QA3 heat modal, but actual G-code and real device upload/print path are not validated end-to-end. |
| NP-RELEASE-06 | Independent release QA / security | P0 | Stable signer and true app updates; confirm actual APK/so hashes, source reference, all gates, test logs, separate reviewer evidence | **HOLD** | QA3 uses a throwaway signer; Draft PR #1 remains unmerged; failures mean release is prohibited. |

## Definition of working as a "team"

Each role produces a **separate finding record** using this format:

```
Review ID:
Reviewer role:
Performed by: [structured single-model review / automated CI / independent human / independent model]
Exact source SHA and artifact SHA:
Inputs and assumptions:
What I tried to falsify:
Observed log or file evidence:
Verdict: PASS / FAIL / BLOCKED / NOT RUN
Open concerns and owner:
```

A single assistant can coordinate the roles, but **must never call several role passes "independent agents" unless independent execution really happened**. Real CI evidence counts as an independent executable test of the code; the final interpretation still needs critical review.

## Sequence (dependencies, not arbitrary features)

1. NP-NATIVE-01: provision native dependencies/rebuild the real A1 mini arm64 slicer library. Source-only fixes do not make installed APKs safe.
2. NP-MATERIAL-02 and NP-SAFETY-03: trace real emitted nozzle/bed commands and validate TPU settings and startup purge safely. These two must disagree with any unsupported assumption.
3. NP-CAD-04 and NP-ANDROID-05: use synthetic fixture first; then the exact rod-butt guard in private controlled input; verify UX, geometry, print feasibility and E2E G-code.
4. NP-RELEASE-06: reconcile all evidence, test persistent signature/update, review regressions and hold/release candidate.
5. Only after release-quality software evidence, supervised A1 mini physical print and dimension/fit test. No autonomous printer command in CI.

## Immediate veto triggers

- **C++ modified, arm64 `.so` unchanged → FAIL (no APK release).**
- **Actual emitted G-code says PLA while recipe says TPU → FAIL.**
- **Actual bed command 35°C vs requested 50°C → FAIL.**
- **Wrong machine or excessive heating in layers, missing commands, unknown temperature syntax → FAIL.**
- **Review modal appears but no subsequent re-audit → FAIL.**
- **Test executed neither on emulator nor on actual device → NOT RUN, not PASS.**
- **Developer and only reviewer is the same model → no claim of independently approved.**

See [review council](NIGHT_PRINT_REVIEW_COUNCIL.md) and [E2E QA gate](NIGHT_PRINT_E2E_QA_GATE.md). This board may evolve, but verdict changes **must include real evidence** and commit/test identity.
