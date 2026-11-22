# NIGHT PRINT GENESIS — mandatory end-to-end QA gates
Status: incomplete (as of 2026-11-23). This document supersedes any suggestion that a successful JVM/CodeQL run proves physical slicing.

## 1. Static model verification — DONE for the user's rod butt guard
Source `NIGHT_PRINT_AYU_GUARD_TPU95A_BLACK_COLORFIX_V1.npjob` was inspected in the development workspace (not published to this public repository).
- Nested 3MF extracted and parsed; 12,288 vertices, 24,576 triangles.
- Watertight, consistent winding, extents 27 × 27 × 32 mm, volume 2,995.467 mm³.
- Geometric intersections at 160 planes, spaced 0.2 mm from Z=0.1 to 31.9 mm: no empty sections, two closed contours per plane.
- These are **mesh-plane intersections**, NOT real Android native slicing, G-code generation, flow simulation, support verification, or physical printer testing.
- Local evidence artifact `nightprint_ayu_geometry_audit.json` generated and supplied in the development conversation.

## 2. Android/Kotlin tests — DONE for QA3 HEAD before this documentation commit
- Historical HEAD: `bcde82c1b34d0b7919ee5f19844fe486ab778b83`.
- GitHub Actions run 45079352860: 2,002 JVM tests, 0 failures, 0 skipped; debug APK built and signed, package/native ABI contract passed.
- CodeQL 45079352865: success.
- **These do not constitute an actual native slice on Android.**

## 3. True slicing automation — REQUIRED before calling GENESIS release-ready
Run an end-to-end Android-compatible test that:
1. Loads a real 3MF or .npjob, selects Bambu A1 mini 0.4 and eSUN TPU-95A black.
2. Applies recipe nozzle 225°C, bed 50°C, layer 0.2 mm, verifies physical filament mapping != PLA.
3. Invokes the **actual native slicer used in the app**; collects produced G-code or sliced .gcode.3mf; records native return code / error messages.
4. Parses real emitted G-code: machine tag, TPU in declared and active material G-code, temperature commands (including startup purge), bounds, 160 expected layers, speed, feature lines, extrusion presence, and no invalid print moves.
5. Verifies startup-heat review is shown ONLY when consent is sole blocker; rejects 250°C after layer start, foreign material, foreign machine, unsafe bed and startup 270°C.
6. Tests rejection AND approval paths. Approval can only re-audit an unchanged G-code and **must not send or start a print**.
7. Exports logs, G-code summary, screenshots, release SHA, and explicit pass/fail artifacts.

An independent Bambu Studio/OrcaSlicer CLI smoke test is useful as a **reference slice**, but is not proof that the Android GENESIS native implementation works.

## 4. Android device / emulator verification — REQUIRED
The installed APK currently contains **arm64-v8a only** native libraries. Any emulator/instrumentation runner must support that ABI and the actual JNI slicer. An x86_64-only emulator test is insufficient. This repo has no verified automated arm64 Android device farm yet.

## 5. Hardware print — REQUIRED for a production safety claim
User-supervised physical A1 mini test confirms external TPU feed, nozzle/bed temperature, adhesion, actual dimensions, and fitting; this cannot be inferred from JVM tests or mesh plane sections. No autonomous printer commands in unattended QA.

### Release decision
- QA APK downloadable: yes, if CI build checks pass.
- True generated G-code from the **same** APK: **NOT VERIFIED**.
- Android end-to-end/physical printing: **NOT VERIFIED**.
- Production release: **HOLD**.

Test status must not be declared PASS unless raw execution evidence is available. The user should not be expected to manually reproduce every new software defect: prioritize true Android native E2E automation and retain human involvement for final hardware checks.
