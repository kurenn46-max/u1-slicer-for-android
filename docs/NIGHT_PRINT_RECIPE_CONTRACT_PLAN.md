# NIGHT PRINT GENESIS — one-click recipe contract / engineering review

Status: SOURCE PROTOTYPE, RELEASE **HOLD** (2026-11-24). No claim of independent engineers or agents; the five workstreams below are structured review responsibilities for one development assistant and executable CI, not five employees.

## Evidence / why this exists

An actual Android QA3 exported G-code file \`nightprint_job_4215274153950852672.gcode\`, generated 2026-11-24, was reviewed *outside the public repository* (user's file not uploaded here).
- Correct: A1 mini, TPU, nozzle 225°C, heated bed 50°C, 160 layers, wall count 2, sparse infill 15%.
- Divergent effective Orca metadata: \`outer_wall_speed=60\` (requested 30 mm/s); \`travel_speed=120\` (requested 150); \`initial_layer_speed=30\` (requested 20); \`initial_layer_infill_speed=60\` (requested 20); \`retraction_length=0.8\` (requested 0.5 mm); \`retraction_speed=30\` (requested 25); \`filament_colour=#F2754E\` (requested black). The speed of individual extrusion moves may be slower still due to volumetric flow / acceleration; that is distinct from a configured speed mismatch.
- Historical native JNI E2E additionally emitted PLA and incorrect 35°C bed heating in other test paths; those releases remain prohibited. One working TPU job does **not** prove every input path.

## Team responsibilities and concrete veto criteria

| Role | Challenge | Required evidence / veto |
|---|---|---|
| Recipe/schema engineer | Valid V1 keys, types, ranges, material identity | Reject unknown settings; never execute embedded scripts |
| 3MF/composition integrator | Explicit user recipe must win against a foreign 3MF or PLA defaults | Verify precedence of all recipe-owned SAFE_IMPORTED_KEYS without overriding target-owned start/end G-code |
| Native slicer integrator | JNI and compiled arm64 .so must agree; raw STL differs from embedded 3MF | Rebuild/verify pinned ARM64 .so, real E2E for both STL and 3MF |
| G-code/material adversarial tester | Temperatures, material, speeds, retraction and color may silently change | G-code heater/material safety audit AND strict recipe metadata contract must both pass; startup 250°C still requires user review |
| Android/release QA | No accidental print, APK signature and stable upgrade, clear mismatch report | Actual device import/preview/native slice, reproducible CI artifacts, supervised hardware print only after gates pass |

## Implementation

\`app/src/main/java/com/u1/slicer/data/NightPrintRecipeContract.kt\`:
- One explicit key map shared by 3MF embedding and JNI override intent.
- Selects user-requested \`TPU/PETG\`, temperature variants, layer, wall/infill, outer wall / travel / first-layer / infill speeds, retraction length / speed, support, brim and optional preview color.
- Does **not** touch foreign printer geometry, machine limits, firmware commands or start/end macros.
- After real native slicing, parses Orca's emitted effective settings from the *actual G-code* and returns every mismatch. Missing entries or contradictory duplicates are failures.
- Existing \`NightPrintGcodeAudit\` still independently checks executable heater commands and material/machine identity. Metadata matching alone is NOT a safety guarantee.

\`SlicerViewModel.kt\` now merges these explicit overrides after safe source and user process defaults for A1 mini, requests native JNI override intent, and refuses to expose a print file if any selected value is absent/mismatched. The existing explicit startup-heat approval / SHA recheck is retained.

\`NightPrintRecipeContractTest.kt\` covers the seven user-observed regressions, expected map fields, imported project precedence, missing/duplicate metadata and optional color.

## Test evidence and limitations

- Local Kotlin \`kotlinc\` contract smoke test against the user-provided QA3 G-code: PASS **as a mismatch detector** (the seven known mismatches were flagged). A modified *metadata-only* synthetic copy matched all expected config values; this is a parser test, **not proof of corrected toolpaths**. No actual printer commands executed.
- Android/Gradle CI at this exact source SHA: consult the Actions results, do not label PASS without actual completed logs.
- The PR's earlier C++ source changes are still not reflected in the bundled ARM64 \`libprusaslicer-jni.so\`. The release gate intentionally blocks APK distribution until that is rebuilt. Do NOT bypass.
- Raw STL without embedded recipe may still fail mapping until the native JNI code path is rebuilt and verified. This is expected **HOLD**, not a failed QA process.
- This V1 contract covers only declared recipe fields; fans, filament density, accel, full cooling/flow and brand-specific settings need a separately versioned extension and verification before claiming full printer profile reproduction.
- Physical TPU spool, filament hardness, actual feed and dimensional fitting require user-supervised test; neither metadata nor CI can certify them.

## Definition of done

1. Real Android ARM64 E2E from the exact candidate APK generates both \`.npjob(model.3mf)\` and \`.npjob(model.stl)\` output with correct materials, temperature commands and **all recipe contract keys**.
2. Bad/foreign/missing settings and unsafe heat, material, machine, file modifications **block** before print, including a consent-only startup heat review path.
3. Full JVM + CodeQL + native CI / signed APK & stable signing verification pass with saved evidence and exact source/binary SHA.
4. User checks black physical TPU, 0.4 nozzle, speeds/temps and 3MF preview, then runs supervised A1 mini print; measured fit and material outcome recorded.
5. Only then consider releasing a new version. Keep prior QA3 and previous working profiles untouched.
