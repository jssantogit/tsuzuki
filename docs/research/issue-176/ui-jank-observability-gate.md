# #176 — UI jank observability gate for canonical chapter refresh

Physical report (Galaxy A54, release c149800f, MangaFire code9): the prior 801-event capture had 44 completed chapter pages and no structured failures, yet the user experienced UI stutters. That capture did not contain frame timing, main-thread delay or detailed chapter UI preparation timing. HTTP and Binder success **cannot** certify UI responsiveness.

## Current instrumentation candidate

Branch: `tsuzuki/provider-platform-issue-176-ui-jank-diagnostics-20261102`, based on Host PR #182; do not merge while unverified.

- `APP_FRAME_STALL` — when foreground Choreographer callback gap is >= 200 ms, with actual `durationMillis` and timestamp. The event is visible only in a user-triggered detailed capture and capped at 64 per capture. Event does NOT assert exact root cause: rendering, GC and process scheduling can also delay callbacks. When capture is disabled or Activity pauses, baseline is cleared.
- `CHAPTER_RECONCILE_PHASE` — slow (>= 16 ms) typed checkpoints: `DELTA_WRITE`, `PROVIDER_SNAPSHOT_WRITE`, `CACHE_INVALIDATION`, `UI_RELOAD_CALLBACK`; include item count and duration, no sensitive title or HTTP data.
- `CHAPTER_UI_PHASE` — slow (>= 16 ms) typed checkpoints: `CHAPTERS_QUERY`, `ROWS_BUILD`, `METADATA_ROW_MERGE`, `CHAPTER_SORT`. Includes pseudonymous canonical title reference, item count and duration. Chapter row build and sort run synchronously in the presentation layer, so this helps correlate UI work to frame gaps.
- Strict sanitization prevents arbitrary phase strings, URLs, tokens, stack traces and content in these new events. Provider/ICE identity, partial evidence, Reader and progress logic unchanged.

## Acceptance criteria before any new performance APK

1. Unit regression validates opt-in gating, first frame/foreground baseline, 200-ms threshold, nonmonotonic timestamps and bounded event volume.
2. Structured sanitizer tests demonstrate that only typed phases and safe counts survive; untrusted arbitrary strings are removed.
3. Full CI, Android instrumented gate and signed install-over Release must pass **on the same tree**. Do not use the previous performance APK #41364823464 as a UI diagnostic.
4. Galaxy A54 physical capture: run a heavy title (e.g. Bleach) with detailed diagnostics enabled from **before** opening it; capture frame stalls and neighboring chapter/UI phases by timestamp. Repeat with a previously cached title. Keep counts and source configuration comparable.
5. If a stall occurs without nearby slow phase evidence, collect a bounded opt-in Perfetto trace before assigning a root cause. A Choreographer gap alone is not a call-stack profile.

Release blocker: eliminate reproducible major jank or positively attribute its source before accepting the performance APK and merging Host PR #182.
