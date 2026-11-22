# MangaALL — future code11-only Host chapter snapshot bridge (NONSHIPPING)

This is **preparatory Host infrastructure only**, not an authorized code11 Provider package, a signed release or a production app feature. Official P-256 MangaALL 0.5.5/code10 seq36 SHA256 `bcdfd03ac18c3cfdd0517e08b58dde7efa1f5baa874e93002af28e518860f95f` stays byte-identical. No real network requests, Reader pixels, user-data migration or 30-title smoke are performed by this change.

## Why this is a separate permission

The current JS Host `http.request/get` must keep its 192KiB HTTP text and 256KiB IPC budgets. The internal `MangaAllFutureHostChapterSnapshot` added in [Host #253](https://github.com/jssantogit/tsuzuki/pull/253) can privately obtain bounded 16MiB source arrays, compact to 512KiB/provider and emit ≤48KiB pages; [Host #254](https://github.com/jssantogit/tsuzuki/pull/254) proves Host broker transport for >2MiB offline with origin/403 enforcement. Neither capability was exposed to installed Provider JS.

The future manifest v1 gains optional `permissions.mangaAllChapterSnapshot.enabled` (default false). When true, **package validation rejects** anything except:

- provider ID exactly `app.tsuzuki.mangaall`
- proposed *future* `version.code >= 11`, `minHostApi >= 2`
- explicit MangaDot `https://mangadot.net` in the declared network origin set
- local network disabled

This is a **separate, narrower permission than generic storage**. It does not grant `tsuzuki.storage` or arbitrary large HTTP bodies. Merely passing an injectable native Host object into `ProviderScriptPackageRuntime.invoke` never suffices: that runtime checks manifest ID, version, Host API, exact source origin, and permission before adding `tsuzuki.mangaAllChapterSnapshot` to the QuickJs environment. Signed code10 and all other packages lack it.

A Host caller may in the future explicitly inject `ProviderMangaAllChapterSnapshotHostService`, with `start(workId, language)` and `next(workId, language, cursor)` that return bounded JSON. `MangaAllFutureChapterSnapshotHostBridge` adapts the internal typed pager to this shape; the QuickJs binding independently enforces ≤48KiB UTF-8/characters, strict JSON lexical grammar (not merely permissive JsonElement parsing) and the exact `items[]` / `nextCursor` envelope. Unquoted tokens, malformed JSON values, wrong outer structure and oversized pages fail as Host errors. It cannot change existing reading identities or implicitly authorize source selection.

## Still missing before production

- The installed app must explicitly construct the private service from a verified signed future package and its capability grants. **No production factory wires the service in this change**.
- Future unsigned Provider source code must call the new bridge only after reviewing exact MangaDot normalization/ID/date parity; original signed code10 continues its old code unchanged. Prior tests found a legacy numeric-ID dedup difference that needs reviewed correction in future Provider code.
- Host API >=2, new permission policy and signature/index updates require separate security and operator approval; no release/publication is allowed here.
- Further multi-process locking, real Host Kotlin/SQLite work-level editorial corroboration, Android Reader CDN pixels and physical A54 validation remain.
- Only after integrated three-source Naruto/Death Note/Bleach functional evidence may the existing explicit opt-in 30-work runner be executed, without weakening identity, provenance or rate-limit gates.
