# Wave 6 — Reader Default Skin

Baseline: `a4add0c02216c29812f65e93e68f65e35890d69a`.
Scope: Reader overlay, session tools, canonical chapter presentation, source/recovery
cards, session-only comfort tint and localization. Existing viewers, gestures,
loaders, canonical progress/history and atomic source publication stay owners.

Sequence: focused behavioral RED → existing runtime integration → floating chrome
and cards → focused/affected CI → report. No APK or physical-phone automation.
No Provider/P2P/security changes. Physical Android acceptance remains pending.

RED: `c462d0ec3`, CI `38142794145`: Format passed; App compilation reached
the new tests and failed on the absent session/chapter/recovery APIs as expected.
The runtime uses one-based currentPage; the test and slider adapter preserve it.
Local focused Gradle stopped before Android compilation at missing SDK location.
