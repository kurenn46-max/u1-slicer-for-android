# Stable regression track — 2026-11-25 JST

Separate experimental branch; do not replace installed APK or the native candidate branch.

Evidence from archived reports (not raw physical-print proof):
- NIGHT PRINT V3.2 report 2026-10-09: Android real native 9 tests passed, PETG 235 C executable startup, 180 mm A1 mini, profile retention and stale state clearing. Historical repository kurenn46-max/a1mini-auto commit 6d3b8105701e85c66ec6e4eb3117ee31565fa1ca, runs 38001688555 and 38001688558. Recheck old artifacts before reuse.
- QA3 AYU ID24 report 2026-11-24: TPU 225/50, 160 layers, Z32 verified in exported G-code; requested recipe not fully matched. Physical print and fit not confirmed by the report.
- User reports some real prints succeed; exact successful file hash and app version not yet linked. Do not designate either archived test as physical success.

Minimal patch: accept both observed layer marker dialects in heat and retraction checks; retain all temperature and material limits. Test good and wrong retraction; test high layer temperature even with startup consent. Do not rewrite executable G-code, loosen gate, change machine macros or replace bundled binary.
Next: reproduce historic 9 cases on stable reference; compare STL vs embedded 3MF, one variable at a time. Treat metadata and actual motion separately. No release until appropriate native tests and user confirmation of physical results.
