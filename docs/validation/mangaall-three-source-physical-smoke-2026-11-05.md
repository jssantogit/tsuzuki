# MangaAll — physical smoke (2026-11-05)

This branch is based on Tsuzuki `main` after merging Host PR #195 (`c5443c36f69ebff30ae48704c9df4b86342b9ed2`). The only additional change is this test plan; the `[apk]` commit requests the existing **release** lane so the APK remains `app.tsuzuki` and keeps persistent signing.

Provider repo PR #60 was squash-merged (`908c6a41f9b01264de697ffe5f0f6025a93f8374`), but MangaAll still has `publication=development`, so there is no automatic signed repo publication and the APK alone does not install the new provider.

## Smoke acceptance

- Install the signed `release` APK over the current `app.tsuzuki` without uninstalling; check that library, providers, repository setup and session remain intact.
- Install/enable one *MangaAll* provider package by the development test path once that signed/authorized package is supplied. Do not install individual MangaDex/MangaDot/MangaFire providers as substitutes.
- For each of MangaFire, MangaDot and MangaDex: locate a canonical chapter; open Reader > Fontes, inspect source, language and any attested scanlation group; select exactly the source and verify pages/headers render, without cross-source or cross-language fallback.
- Reopen Reader, navigate adjacent chapters, test different available languages, retry an unavailable source, and confirm previous pages/progress survive a failed switch.
- Capture provider candidate identities, exact failure types, page count and timing in diagnostic logs. Do not claim MangaDex@Home image support physically confirmed until observed.
- A live smoke does not grant permission to promote MangaAll from `publication=development`. Publication requires scanlation-group removal handling and compliance/attribution review.
