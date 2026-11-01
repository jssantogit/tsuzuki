# #176 — integrated multi-HID release gate (2026-11-01)

This is a temporary **staging branch**, not a merge, a release, a user acceptance, or a closure of [#176](https://github.com/jssantogit/tsuzuki/issues/176). It mirrors the Host Draft PR #182 code at the 2026-11-01 multi-HID provisional-evidence checkpoint and inherits the stacked Provider Platform base. The `tsuzuki/provider-platform-*` prefix is deliberate: `.github/scripts/apk_lane.py` maps it to the **signed release** lane for installed `app.tsuzuki`; no Dev C or new sandbox.

## Preconditions for APK generation

- CI v2.1 full mode **must pass** on this exact code: SQLDelight migration `51.sqm`, Domain, Data, App, Supabase, release compilation and full gate. An unaffected/skip job is not PASS.
- Verify the provider repository [MangaFire PR #55](https://github.com/jssantogit/providers-tsuzuki/pull/55), v0.1.8/code9 (unpublished), against signed repository and conformance. Existing code8 lacks the new verified-author/coverage preview response; smoke cannot validate this path with code8 alone.
- Provider/HID observations are **additive and provisional**; complete inventory is false whenever editorial editions are ambiguous. Preferred reading binding is preserved. A source attestation never grants cross-HID chapter or page identity.
- Canonical progress, tombstones, backup, Supabase sync and persisted Reader mappings must remain untouched by shadow work associations. No forced deletion of old HIDs or migration reinterpretation of progress.

## Manual physical acceptance (Galaxy A54, Android 14, in-place update)

- Check signed APK package/certificate and update existing `app.tsuzuki` without resetting provider settings, library, downloads, or progress. Install-over and persistence gate.
- Activate diagnostics detailed capture **only for the smoke**. Inspect per-HID work assessment, edition qualifier, first/last chapter IDs, page1 counts, follow-up page latency and timeout reasons. Normal logs remain pseudonymous.
- Verify Fullmetal Alchemist, Chainsaw Man, Naruto and Bleach with real code9 MangaFire. Each must gain real canonical chapter evidence from legitimate HIDs while color/omnibus/foreign facet remains distinct; never fill numbering gaps synthetically.
- Open a canonical chapter whose pages are served by a **nonpreferred** HID. Confirm the physical content and chapter number match the canonical selection; switching source must not redirect to another chapter/edition.
- Recheck Vagabond (normal vs official colored; second page timeout), Neverland chapter1, Haikyuu missing numbered chapters, Nanatsu no Taizai wrong-content chapter and Berserk prologue/0.1. Record **observed**, **failed** or **not exercised** individually.
- Exercise a partial paginated refresh, disabled provider, failed challenge/403 and malformed response. `item_count` reflects reconciled provisional observations while `completed=false`; no destructive replacement or false empty successes.
- Confirm read progress, backup/restore, Cloud sync/tombstones and library navigation unchanged. A successful compilation is not physical-Reader verification.

## Stop conditions

No PR merge, provider code9 publication, issue closure or claim of physical success without direct gate evidence. Record unresolved differences in Known Bugs and this issue. This plan is not an authorization to weaken work/chapter identity to increase apparent coverage.
