# NIGHT PRINT GENESIS — Multi-perspective engineering review council

Version: 2026-11-23. **Status: active process, not a claim that independent people or autonomous agents have been recruited.**

## Why this exists
One assistant acting as designer, programmer, tester and approver creates a dangerous self-certification loop. This project separates **responsibility, evidence and veto rights**. A role can be performed as a structured analysis pass, by a specialized tool, or by a separate reviewer, but role names are not evidence of independent review. No one may report that a real second human/AI agent signed off unless there is an actual independent review artifact.

The lead coordinates; the lead **does not get to override test failures**. No release is authorized merely because JVM tests or CodeQL pass.

## Functional team and veto authority

| Workstream | Owner / reviewer responsibility | Evidence needed before PASS | Rejection authority |
| --- | --- | --- | --- |
| Geometry & CAD | Validate 3MF structure, correct units and model dimensions, watertightness, 0.4 mm nozzle-compatible walls, fit/clearance, print orientation | Original 3MF hash, mesh QA output, cross-sections + print feature analysis | Hold geometry and print jobs |
| Materials & print process | Separate eSUN TPU95A, SUNLU TPU95A, regular PETG and PETG HF; inspect manufacturer guidance, drying, extruder feed, speed, bed adhesion and high-temp start purges | Exact filament identity, manufacturer vs user-supplied vs community settings separated; documented temperature allowance for actual spool | Hold material-specific profiles |
| Native slicer & integration | Ensure Kotlin `SliceConfig` reaches native Orca engine, rebuild ARM64 `.so` from pinned sources, invoke actual native slice, inspect generated layer count/filament and toolpath | Source and binary hashes, linker/ABI/JNI/16K alignment checks, raw G-code summary and instrumentation logs | Hold APK release |
| Safety & adversarial reviewer | Deliberately challenge all assumptions: printer identity, material metadata, start/purge vs printing temperatures, bed temperature, layer-phase overrides, corrupted or truncated G-code, cancelled jobs; never disable safety checks to obtain green | Negative tests demonstrating fail-closed behavior; full list of unexpected commands and blocking decisions | **Absolute no-print veto** |
| Android UX / QA | Verify file picker and direct `.npjob` opening, 3MF preview color, A1 mini 180³ bed, material applied, review modal, cancellation, no accidental printer submission | Android instrumented run and screenshot/log evidence for APK SHA; device-only items explicitly marked not run | Hold APK installation / UX |
| Release & independent QA | Independently reconcile all evidence and changes, stable signing identity, update compatibility, packaging, security results | Versioned evidence register with each gate marked PASS/FAIL/NOT RUN; exact signed APK hash, no test skips | **Absolute release veto** |

**Lead (NIGHT PRINT coordinator):** triage issues and arrange review order; never certify their own implementation solely on a narrative. When the same model performs multiple passes, explicitly state "single-operator structured review", **not** independent sign-off. An actual independent human or other agent can be added only when available and authorized.

## 6 mandatory release gates

1. **Design:** mesh/3MF integrity, expected dimensions, nozzle-width feasibility, tolerances, supports. An exported mesh and a watertight check alone are not "sliced".
2. **Materials:** correct TPU95A source, maximum/minimum temps and extrusion limits. The A1 mini startup template may temporarily request 250°C for purge, but that fact **does not** prove every TPU spool tolerates it; the bed and nozzle must be verified in **generated G-code**, not UI state.
3. **Native:** compile and package a new `libprusaslicer-jni.so` after source changes. A changed C++ file with an unchanged .so is automatic FAIL. Native test must produce actual G-code with `TPU`, correct bed/nozzle, extrusion and machine; both plain STL and `.npjob`+3MF profile paths.
4. **Red-team:** failure cases MUST reject wrong machine, PLA metadata, wrong bed heater targets, 270°C layer-phase nozzle, malformed temp commands, missing layers, changed SHA during approval. Approval never sends a print.
5. **Android:** APK installed with stable cert; verified Android arm64 runtime; genuine file import and preview. Emulator evidence does not equal physical printer evidence.
6. **Release:** an independent reviewer verifies gates 1–5 against artifacts; final supervised A1 mini print checks feed, dimensional fit and appearance before claiming production-safe.

Mark each gate: `PASS` (actual reproducible evidence), `FAIL` (specific defect), `NOT RUN` (no test), or `BLOCKED` (missing prerequisites). **NOT RUN is not PASS**. No release if any gate is FAIL/BLOCKED/NOT RUN.

## Defects known on 2026-11-23 (grounded in actual CI)
- `NIGHT PRINT native A1 mini E2E` run [45084208399](https://github.com/kurenn46-max/u1-slicer-for-android/actions/runs/45084208399) **FAIL**: raw STL scenario produced G-code with `PLA` when `TPU` requested; synthetic 3MF scenario emitted 35°C bed command when 50°C requested.
- `NIGHT PRINT release-gate tests` run [45084208486](https://github.com/kurenn46-max/u1-slicer-for-android/actions/runs/45084208486) **FAIL by design**: modified C++ has **no rebuilt checked-in arm64 .so**.
- CodeQL run [45084208406](https://github.com/kurenn46-max/u1-slicer-for-android/actions/runs/45084208406) **PASS**: static security scanning only, not native/material safety.
- Bambu A1 mini machine startup purge templates include transient heating at 250°C for some filament branches. This is not an unconditional material safety waiver. The installed QA3's approval dialog is not proof that print parameters are safe.

## Concrete artifacts required per change
- Source SHA, compiled APK SHA, native .so SHA and signer certificate fingerprint.
- Exact input model (may be a synthetic public fixture; private user models are not uploaded to this public repo without explicit permission).
- Machine + material brand, hardness, slicer profile, nozzle/bed targets, actual first-layer and startup G-code.
- Native E2E pass/fail and report path, automated regression pass/fail, screenshots/logs where meaningful.
- Independent adversarial notes with **at least one attempted falsification**, and outcome.
- Remaining device tests, owner, blocker and recovery route.

## Release decision
**HOLD** until source/binary mismatch is fixed and native TPU/G-code and Android E2E tests pass. Do not rename an unverified QA APK "final", auto-start physical prints, silence errors, or infer success from rendered 3MF alone.
