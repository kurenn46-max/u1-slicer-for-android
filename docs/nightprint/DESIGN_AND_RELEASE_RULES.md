# NIGHT PRINT GENESIS — design and release standard
Version: 1.0 (2026-11-24)  
Scope: Bambu Lab A1 mini, 0.4 mm nozzle; extend by explicit, independently tested machine/material profiles.

## Goal
Design a model and a one-click Android-compatible .npjob/.3mf **without altering printer-owned safety commands**, without silent fallback to PLA/U1 defaults, and without labeling untested output as release-ready. Freeze previously successful QA3/print data as a baseline; never overwrite a stable APK automatically.

## Three-layer ownership — mandatory

### M: Machine profile (Bambu Lab A1 mini, locked)
- **Source of truth:** record the official Bambu Studio A1 mini 0.4mm machine profile with source URL, version/commit/hash, machine ID, firmware assumptions, nozzle diameter and bed type. Derive identity and allowed volume 180x180x180mm from that verified profile.
- Owned by machine: printer model/ID, printable volume, exclusion zones, nozzle hardware, motion/acceleration limits, start/end/change-filament G-code, homing/bed leveling/calibration/purge/wipe instructions.
- A project 3MF, .npjob, foreign printer profile, or generated model **must not overwrite these**. Use an explicit allowlist and fail closed for unknown keys.
- **Safety discrepancy gate:** QA3 native code currently sets `machine_max_acceleration_x/y = 20000 mm/s^2` while the previously consulted official A1 mini published maximum acceleration is `10000 mm/s^2`. The meanings and actual firmware clamping must be verified against the machine-profile release before accepting a new native binary; do not blindly change code or claim certified safety.
- If vendor profiles evolve, rebase against a pinned official profile and review the diff, never silently reset user-validated process/material conditions.

### F: Filament profile (material-specific, editable with evidence)
- Separate profiles for **SUNLU regular PETG (gray)**, **SUNLU TPU95A (black)** and any other variant. Do not conflate PETG HF, PLA, another maker, or another color/lot.
- Owned by filament: extruder/nozzle & bed temperatures (including first layer / active plate), color for preview only, flow/volumetric ceiling, fan/cooling, pressure advance, retraction and material-specific limits.
- Label each setting with evidence state: **manufacturer specification**, **verified A1 mini real print**, **other user's corroborated test**, or **unverified candidate**.
- `color_hex` changes **preview only**, not the physical spool. Prevent PLA fallback when a .npjob explicitly selects PETG/TPU. Conflicts with machine physical limits block processing.

### P: Part/process profile (per-model editable)
- Owned by the part: orientation, layer height, first-layer height, wall loops, infill %, infill speed, outer/initial/travel speeds, supports, brim, seam/support contact considerations and expected dimensional tolerance.
- Maintain parametric source and revisions (dimensions, fit clearance, hole diameters, hinge and elastic anchoring positions) and produce real .3mf/.stl outputs, not app Kotlin source disguised as a job.
- Never treat a synthetic FEA/virtual fit screen as a verified real load/fit test.
- Approved changes to P must not overwrite M. Approved changes to F must respect M's machine envelope and validated start macros.

## Deterministic pipeline
1. **Design intake:** material variant, hardware, measured mating dimensions, use case, load/exposure, printer nozzle, target fit, risk/hard-stop.
2. **CAD:** unit=mm, make all production parts independent valid solids; test normals, watertightness, minimum wall, interference, hinge motion and bed placement. Preserve fit dimensions of a successful design unless changed intentionally.
3. **Packaging:** `.npjob` = exactly `model.3mf` (or `model.stl`) and `settings.txt` using `NIGHTPRINT-SETTINGS/V1`; no scripts, extra files or unsupported keys; ensure nested 3MF ZIP integrity.
4. **Parse and compose:** validate requested settings, then resolve ownership with precedence `official machine M (locked) + material F + process P`. Expose values and provenance to reviewers; user overrides only explicitly supported non-machine keys.
5. **Real native slicing:** on Android, load the *actual model*, run JNI OrcaSlicer, export actual G-code. A synthetic cube/tetrahedron test may be used as an additional regression but never replaces a user's part.
6. **Evidence/audit:** parse emitted metadata + actual G0/G1/E/M commands; compare target, material, bed-plate heater, nozzle temp, fan, layer, wall/infill, extrusion speeds and real retraction moves with selected values. Fail on missing/mismatch. Inspect travel coordinates and startup safety macros; flag geometry/support and hardware-clearance risks.
7. **Release gate:** compile current Kotlin + C++ native sources into the **same** APK; NDK26/clang17 native build, verify .so hash/symbols, 16K-page and release optimization. Full JVM tests, instrumented A1 mini JNI STL + 3MF tests, real target 3MF test and CodeQL all pass. `app/build.gradle` currently embeds a prebuilt `.so` and disables `externalNativeBuild`; the gate **must reject a C++ source-only fix** until the new binary is committed and tested.
8. **Real-world validation:** owner runs A1 mini print only after explicit review/permission. Record spool/lot, drying, real printer/firmware, photo, measured part dimensions, insertion/retention and strength test; reject or mark provisional when untested.

## Required per-job report
| Category | Required evidence |
|---|---|
| Job/model | CAD/3MF/npjob file hashes; part version; input dimensions and approved diff |
| Machine | official machine profile reference/hash, printer and nozzle, unlocked/mismatched machine keys |
| Material | maker/variant/color, official vs field-tested vs provisional settings |
| Process | selected F/P values with justification |
| Software | source commit + built .so hash + APK build identifier; Android ABI/device |
| Actual output | G-code hash and audit table: **expected / actual / pass or fail / evidence line** |
| QA | parser, mesh, placement, virtual interference, real Android slice, safety audit and overall state |
| Physical result | real A1 mini print, measurements, fit/retention result, pass/fail and known limitations |

State labels only: **SOURCE-ONLY**, **STATIC-PASS**, **ANDROID-SLICE-PASS**, **GCODE-AUDIT-PASS**, **PRINT-VERIFIED**, or **HOLD/FAIL**. Never promote without the evidence for that level.

## Current A1 mini gap list (2026-11-24)
- The old A1 mini native path omitted explicitly applying some selected print/travel/first-layer/sparse infill/retraction settings; a draft source fix is on this PR.
- A prior JNI E2E found TPU->PLA/220C fallbacks and misplaced 3MF speed/retraction defaults. Fix test coverage and actual native values together.
- Native `.so` rebuild is blocked in ordinary GitHub CI by missing gitignored prebuilt NDK dependencies; **no APK release** on the source-only patch.
- PETG gray GAFF V9 .npjob package parser passed, but the GAFF V9 **real Android slice and generated G-code audit are not yet passed**, and physical fit remains unverified.
- Review firmware/motion baseline, especially X/Y acceleration ownership, against the exact official A1 mini profile version; do not automatically treat generic machine capability as intended print feature speed.

## Default NIGHT PRINT GENESIS project behavior
Apply these rules before any new 3MF or Android app change. Prefer single-scope corrections and compare the original successful test file against the candidate. If a tool or test environment cannot perform a gate, label it **NOT RUN** and preserve the previous stable release. Do not claim a virtual test is equivalent to actual Android JNI slicing or physical printing.
