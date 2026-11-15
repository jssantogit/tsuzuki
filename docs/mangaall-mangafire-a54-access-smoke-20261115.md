# MangaAll — Galaxy A54 MangaFire HTTP/Browser/Reader acceptance (2026-11-15)

## Why this run exists

The Provider CI runner received HTTP 403 with `cf-mitigated: challenge` for both MangaFire homepage and signed API before candidate discovery ([isolated diagnostic #43716043952](https://github.com/jssantogit/providers-tsuzuki/actions/runs/43716043952)). That does **not** prove the MangaFire source is unavailable on the physical Android device. Android Browser Host Service exists only on-device. In the latest A54 smoke, some MangaFire chapter-1 Reader sessions were `READY`, but that proves only prepared page URL/session metadata, **not rendered image pixels**.

Host [PR #204](https://github.com/jssantogit/tsuzuki/pull/204), merged as `b6260bcb437363f05f83345d98072eeb41f9a919`, now detects **main-frame HTTP errors** in the isolated Browser Host Service and emits closed diagnostic categories:
- `UPSTREAM_CHALLENGE` when the HTTP 4xx/5xx response carries `cf-mitigated: challenge`;
- `HTTP_REJECTED` for other main-frame HTTP 4xx/5xx replies;
- sanitized `MANGAALL_NETWORK_SOURCE=MANGAFIRE` for allowlisted MangaFire origins, paired with the existing direct `PROVIDER_HTTP_REQUEST` family/status events.

This distinguishes a challenged browser read from a successful text return, **without** solving the challenge, widening allowed origins or reading/exposing source bodies/cookies/VRF tokens. A Browser `SUCCEEDED` is still only text-level success; downstream Provider acceptance and real Reader pixels must be checked separately. The PR branch [CI #43721472510](https://github.com/jssantogit/tsuzuki/actions/runs/43721472510) passed App, Format and CI Gate; release/native were change-planner skipped. Post-merge main CI and the APK build are separate gates.

## Install contract

Use **only the standard signed `main` release APK**; update the existing `app.tsuzuki` *in place* on Samsung Galaxy A54 / Android 14. Keep library, progress, preferences, app data, signed repository enrollment and installed providers. Do not uninstall, clear storage, switch to `app.tsuzuki.dev`, install separate MangaFire/MangaDex/MangaDot provider packages or downgrade the signed repository index.

The existing single signed `app.tsuzuki.mangaall` **0.5.2/code7**, official index **seq33**, remains unchanged. The new APK changes the Host only.

## Physical check — shortest useful sequence

1. Confirm Host build commit from App information and the same installed MangaAll code7. Start a *bounded, detailed* Tsuzuki diagnostic capture before the source attempts.
2. Open the **existing** `Oyasumi Punpun` entry. Refresh canonical chapters; if the source appears in candidates/reading options, inspect the real source `mangafire:zk12` in diagnostics and record whether the preferred `MangaDex/en` can be complete-empty while alternate MangaFire evidence is provisional. Do **not** silently change an editorial edition to force a result.
3. Open Chapter 1 through the eligible MangaFire reading option if one is available. Record (a) whether source selection/Reader becomes READY, (b) whether images visibly render on the A54 screen, (c) whether retry works after a normal failure. Do not equate page count, URLs or READY with pixels. If a challenge is shown in a normal browser, any human verification must occur only in the normal site experience; do not automate or share cookies.
4. Repeat the same read path with existing **`Koe no Katachi`**, known MangaFire HID `mangafire:7p56`; record exact requested chapter number and whether it opens the correct work. A source challenge or timeout is **not** evidence that its chapter inventory is empty.
5. Perform one additional refresh, close/reopen the app and refresh again. Inspect sanitized `PERSIST/REUSED` provisional chapter cursor evidence and whether the previously observed chapters remain; do not declare a total canonical inventory from partial pages.
6. Optional in the **same** capture: inspect `Akame ga Kill!` and `Ao no Hako` for the new verified canonical `AUTHOR_ONLY/ARTIST_ONLY/AUTHOR_AND_ARTIST/NONE` credit basis, source edition conflicts and correct rejection when still ambiguous. They do **not** authorize cross-edition selection by name or chapter count.
7. Stop capture and export Tsuzuki Logs. Note manually whether real image pixels appeared for each attempted chapter and source, and attach a **redacted** screenshot only if useful. Do not export authentication/session materials, request URLs, VRF signatures, raw response HTML or source image bodies.

## Outcomes to decide from the evidence

| Stage | Pass condition | Still inconclusive / failure distinction |
| --- | --- | --- |
| Direct Host HTTP | MangaFire response received and status classified | HTTP 403/challenge; no candidate-inventory conclusion |
| Browser fallback | Source text obtained without main-frame HTTP failure, then accepted by Provider | `UPSTREAM_CHALLENGE`, `HTTP_REJECTED`, `TIMEOUT`, `SELECTOR_MISSING`; Browser success alone is not valid JSON |
| Source chapters | Actual HID, chapter numbers and page/cursor evidence, completeness explicitly classified | Partial pagination, unrelated HID, unverified creator/edition |
| Reader | Correct canonical chapter, eligible source, and **visible decoded pixels** | `READY` URLs without real pixels, CDN errors, wrong mapped chapter |
| Persistence | Existing library/progress and durable cursor survive app restart | Repeated first page or lost checkpoint |

Classify each observed failure by the earliest failing boundary; do not claim the MangaFire CI challenge is a verified Android failure. If no source response is obtainable, do not populate the 67-work **real-source** ambiguity corpus with synthetic candidates. Only after actual source evidence exists should separately reviewed, minimum-necessary real metadata capture and deterministic replay proceed.

## References

- [Host Browser challenge PR #204](https://github.com/jssantogit/tsuzuki/pull/204)
- [MangaFire runner challenge root cause](https://github.com/jssantogit/providers-tsuzuki/blob/main/docs/mangafire-ci-403-root-cause-20261115.md)
- [Previous physical A54 code7 smoke](https://github.com/jssantogit/tsuzuki/blob/main/docs/mangaall-a54-code7-smoke-20261114.md)
- [Previous Host/Reader acceptance](https://github.com/jssantogit/tsuzuki/blob/main/docs/mangaall-host-journey-emulator-20261112.md)
