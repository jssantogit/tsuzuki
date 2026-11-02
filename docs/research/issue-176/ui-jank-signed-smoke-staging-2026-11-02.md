# Signed Release staging candidate — #176 UI jank diagnostics

This branch is an **unapproved, non-merged** install-over Release APK candidate for physical diagnosis, not a performance-acceptance build.

Code tree comes from PR #183 head `82eaf89c7d810c152664ce3f7da76431e1a665fd`; this staging commit adds only this documentation trigger, no app logic.

Required gates on the code commit:
- Full CI https://github.com/jssantogit/tsuzuki/actions/runs/41370433122
- Android UI actual stall instrumentation https://github.com/jssantogit/tsuzuki/actions/runs/41370166836 (this job tests the equivalent code at `068ac7bd`, before an import-only Spotless fix)
- Compare APK signing certificate with the physical installed `app.tsuzuki` / 0.20.4 code29 lineage and check install-over eligibility.

**Do not hand off or merge if any gate is red**. The user reported visible freezes while loading canonical chapters despite zero structured errors in the old 801-event log; signed release success alone does not prove correct UI instrumentation. Capture from Settings → Diagnostics → Logs **before** opening a chapter-heavy title such as Bleach; observe `APP_FRAME_STALL`, `CHAPTER_RECONCILE_PHASE`, and `CHAPTER_UI_PHASE`, and compare event timestamps.
