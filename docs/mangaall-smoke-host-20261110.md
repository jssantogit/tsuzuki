# MangaAll 0.5.0 / Host smoke baseline — 2026-11-10

This is an APK build trigger only; the Host source tree equals PR #196 at
`26238555771cee79aa24d0e8ff41e53dd1a7750b`. This documentation file is not shipped as provider code.

Physical test gate:
- Install **release-lane, arm64-v8a signed APK** over existing app; preserve data.
- Provider 0.5.0 code5 is **not** published. Do not install unsigned files or enroll
  a newly generated smoke repository.
- Do not mistake 0.4.0 from ephemeral smoke-v4 key for the persistent official
  `app.tsuzuki.providers` signed repository: smoke-v4 signer was intentionally discarded.
- Confirm repo enrollment and installed provider provenance before any migration.
- Inspect MangaAll candidate relevance (Monster/Dragon Ball), canonical inventory
  (Fullmetal), and Reader diagnosis (Grand Blue).
- Record actual page render and source language separately from HTTP success.
- Leave both Draft PRs open until on-device acceptance.
