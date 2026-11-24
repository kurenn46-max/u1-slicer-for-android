# NIGHT PRINT GENESIS QA3 — Gaff V9-M1 TPU Root-Cause Review
Date: 2026-11-24 (JST) | Status: **RELEASE HOLD**
Participants: **simulated technical roles** (A1 mini vendor-profile reviewer, Orca/JNI engineer, filament engineer, QA engineer). **No Bambu Lab employee participated or endorsed this review.**

## Evidence
**Source A — one real Android A1 mini user's slice**, `nightprint_job_6686982954588052335.gcode` (~8.85 MB / 349,829 lines, Snapmaker Orca 2.3.3). The generated file is private and not committed.
- Input `.npjob`: TPU95A (black), A1 mini, 225/50 C, outer 30 mm/s, travel 150, first-layer 20, infill 25, retract **0.5 mm at 25 mm/s**, support on, 6mm brim, 5 walls / 30% infill.
- Output: `; retraction_length = 0.8` at line 349642; `; retraction_speed = 30` at line 349644. Pattern `G1 E-.8 F1800` (plus some `E-0.8` variants) occurs **3,790** times (non-comment motion instructions) at 0.8 mm/30mm/s; not the expected 0.5 mm/25mm/s. **These are real execution instructions, not just metadata.**
- Output: `; total layer number: 220`, `; max_z_height: 44.00`, exactly **220 `;LAYER_CHANGE`** markers; QA3 UI says 425 layers.
- Output startup `M104 S250` and `M109 S250` at lines ~225/231; output filament header `; nozzle_temperature_range_high = 240`, print setpoint 225 C.

**Source B — Bambu Lab official Bambu Studio machine profiles:**
- A1 mini 0.4 nozzle `inherits` `fdm_bbl_3dp_001_common`: https://raw.githubusercontent.com/bambulab/BambuStudio/master/resources/profiles/BBL/machine/Bambu%20Lab%20A1%20mini%200.4%20nozzle.json
- Common profile sets **retraction_length = ["0.8"]** and **retraction_speed = ["30"]**: https://raw.githubusercontent.com/bambulab/BambuStudio/master/resources/profiles/BBL/machine/fdm_bbl_3dp_001_common.json
- Same common profile contains `machine_max_acceleration_x/y = ["20000", "20000"]`; it is incorrect to describe QA3's 20000 alone as a mismatch with Bambu Studio's official profile, notwithstanding separate published physical speed/acceleration marketing figures. Compare exact semantics/revisions before modifying motion constraints.

**Source C — installed-project GitHub development branch** (not a rebuilt, verified APK): 
- `app/src/main/java/com/u1/slicer/bambu/BambuImportedConfigComposer.kt` admits `retraction_length` and `retraction_speed` as **safe** fields in Kotlin.
- `app/src/main/cpp/src/sapil_print.cpp`'s **`bambu_safe_profile_keys[]` excludes both retraction fields**, although it allows outer/travel/infill speeds. This can discard an otherwise-valid user-supplied setting when copying embedded Bambu 3MF configuration into the native config.
- `applyBambuConfigToPrusa()` now has draft explicit overrides for both fields, but the Android app ships a **pre-built arm64 `.so`** and the source fix is **not present in that binary**. The CI release gate intentionally rejects source/binary mismatches.
- The user-interface code can retain 0.5/25 in Kotlin and show it in Settings while the native output still uses 0.8/30.
- Android E2E/quality CI on draft branch is failing; security scan CodeQL passes. Never claim the source fix has been exercised in a rebuilt APK.

**Source D — upstream layer semantics:**
- Orca-family `PrintObject::total_layer_count()` returns `layer_count()+support_layer_count()`; ref: https://tangled.org/logik.al/Orcaslicer-bambulab-standalone/blob/fe4a72ec94d1675949e55ee334a7aa373dc5cce4/src/libslic3r/Print.hpp
- QA3 `sapil_print.cpp` sets `result.total_layers = max(obj->total_layer_count())`. With support on, this can overcount the displayed number relative to G-code's *actual distinct print Z layers*. 425 - 220 = 205: likely support-layer entries, **inference, not yet measured from native support-layer array**.

**Source E — material and machine startup:**
- `BambuA1MiniMachineGcode.kt` pins a compressed machine startup/end/change template to a compatible Orca engine. The real exported startup includes 250 C common-flush heat; this command is separate from the 225 C steady printing temperature.
- `NightPrintGcodeAudit.kt` allows a **generic TPU startup ceiling 250 C** but for PETG defaults to the print setpoint itself. This allows a material-specific TPU high-temp check to be bypassed even if emitted profile says `nozzle_temperature_range_high=240` and the manufacturer recommends lower temps. The correct response is **fail closed and review**, not lift the threshold blindly.
- SUNLU 95A/90A product settings have variant/speed dependent recommended temperatures up to 230 or 240 C; do not treat 250 C as vendor approved: https://store.sunlu.com/products/moq-3kg-tpu-3d-printer-filament-1kg

## Cross-disciplinary decisions

| Owner | Finding | Required engineering action |
|---|---|---|
| Bambu machine-profile reviewer (simulated) | 0.8mm/30mm/s is an ordinary inherited printer/extruder default, **not a hard-coded safety prohibition**. Start G-code has separate 250 C flush. | Preserve machine homing/calibration/start macro ownership. Support explicit approved retraction overrides *after* inheritance; validate the source profile and temperature limits independently. |
| Orca/JNI engineer (simulated) | Kotlin allowlist accepts retraction, native embedded-profile whitelist does not; old `.so` ignores new source-only override. | Add explicit native whitelist/override coverage with provenance, **rebuild** NDK26 clang17 `.so`, match APK hashes; repeat real TPU 3MF and STL G-code diff, do not assume display=execution. |
| Filament engineer (simulated) | 225 C print normal but startup reaches 250 C vs emitted profile high 240 C; flex filament may degrade and manufacturer confirmation required. | Enforce spool/lot-specific max safe startup heat; evaluate compatible official startup sequence, never delete G-code housekeeping blindly or merely relax audit. |
| QA engineer (simulated) | UI counts support layers and actual layers together; native toolpath shows 220 real layers not 425. | Report real Z layers from actual G-code (header & layer markers), never `PrintObject::total_layer_count` as physical-layer truth; regression test supports-on/off and multi-object cases. |

## Release acceptance tests (all gates required)
1. Assert `.npjob` parser returns correct recipe and geometry-only 3MF still preserves correct material/nozzle/bed.
2. Assert JNI receives selected `retractLength=0.5`, `retractSpeed=25`, `bambuExplicitOverrides` contains both, and native config records final `retraction_length=0.5`, `retraction_speed=25` immediately prior to print generation.
3. Parse **actual generated TPU G-code**; require both metadata and actual print-phase negative E moves to match approved recipe. Distinguish start/end machine-specific purge moves from print-phase retractions; do not miscount template retractions.
4. Assert nominal spool max nozzle temperature >= every actual startup M104/M109 target, with active-plate bed limits; fail if 250 > material's confirmed max. No bypass toggle without adequate verified material safety evidence.
5. Compare physical layer count from G-code header/unique layer markers against UI (expected 220 for this model); support layers must not be counted twice.
6. Rebuild native `.so` with NDK26 Release, run source/binary provenance checks + real Android E2E (STL and 3MF) + regression suite; keep prior stable QA3 binary unchanged until pass.
7. User-supervised actual A1 mini print/fit test only after gates; no remote printing or upload/start authorized by this review.

## Outcome
**Root-cause confidence: high** for retraction default/whitelist/stale native binary and layer counting definition; **high** for separate startup 250 C command; **not verified** whether manufacturer-approved SUNLU TPU95A can safely tolerate the brief 250 C flush. **HOLD** on printing this G-code and on releasing a patched APK until binary rebuild and full audit.
