# NIGHT PRINT Code V1 — prototype (NOT an APK release)

Adds **Settings > NIGHT PRINT Code (experimental)** to the existing Android Kotlin settings screen on this experimental branch.

## Workflow
1. Back up current printer settings using the existing **Settings > Backup & Restore > Export Settings**.
2. Paste a `NIGHTPRINT-SETTINGS/V1` settings code.
3. Tap **Validate and preview**. Check the before/after data and selected machine.
4. Tap **Apply settings (no printing)**. This updates local SliceConfig and SlicingOverrides, and saves them to DataStore.
5. In **Prepare**, confirm machine **A1 mini**, active external spool **TPU**, filament mapping, model placement and slicer settings.
6. Slice and inspect the resulting G-code, especially `M1002 set_filament_type:TPU`, temperature, speeds, prime/start G-code and 180x180mm target bed. **Do not print until these checks pass.**

## Example: eSUN TPU 95A black on A1 mini
```text
NIGHTPRINT-SETTINGS/V1
printer=BAMBU_A1_MINI
material=TPU
nozzle_c=225
bed_c=50
print_mm_s=30
travel_mm_s=150
first_layer_mm_s=20
retract_mm=0.5
retract_mm_s=25
layer_mm=0.20
wall_count=2
infill_percent=15
infill_mm_s=25
support=false
brim_mm=0
```

## Supported in V1
- Material label for SliceConfig: TPU / PETG
- Nozzle and bed temperature
- Outer wall / generic print speed, travel, first-layer speed
- Retraction length/speed
- Layer height, wall count, infill percentage, sparse infill speed
- Support on/off and brim width

## Not automatically controlled in V1
- Printer selection and actual A1 mini machine profile / bed size
- External spool / Bambu filament slot mapping, and project-specific per-object assignment
- Inner-wall speed, cooling fan, max volumetric flow, pressure advance
- Start/end G-code, plate type, physical printer temperature or movements

The parser uses an allowlist of fields, rejects unknown commands, duplicates, malformed or excessive values, and never executes arbitrary code. Passing validation does NOT certify a safe G-code file or a successful print.

## Validation status (2026-11-22)
- Parser: Kotlin smoke-compiled with JDK 21; 1 valid TPU example accepted, 7 adversarial input classes rejected.
- GitHub branch: parser + Settings UI wiring + 8 JUnit test cases committed.
- Full Gradle/Android APK build: **not performed** in this environment (no configured Android SDK).
- Android interactive/e2e test and real printer test: **not performed**.
- Existing phone app remains unchanged. No release/tag created.

This branch is an experiment, not a replacement APK. Compare upstream v5 changes and port only after full build, integration testing, and backup verification.
