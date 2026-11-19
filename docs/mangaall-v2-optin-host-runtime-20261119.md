# MangaAll code9 V2 Host opt-in, isolated runtime proof — NOT enabled

Date: 2026-11-19. Status: **EXPERIMENTAL HOST ONLY, NO SIGNED CODE9, NO LIVE CDN**.

The signed MangaAll 0.5.3/code8 is unchanged. Provider
[PR #74](https://github.com/jssantogit/providers-tsuzuki/pull/74)
provides a local temporary code9 **UNSIGNED** staging package that
is not in the repository index and cannot be installed by production.

## Strict opt-in lookup

`ScriptProviderReadingGateway.lookupMangaAllV2ReadOnly` reuses the
existing `call()` boundary (Provider registry, enabled SCRIPT
capability, manifest permissions, isolated process invocation and stale
registration rejection), rather than inventing a second invocation
path. A pre-invocation package gate requires:

- explicit `enableExperimentalMangaAllV2=true` in the constructed
  gateway; all app default constructors retain **false**
- exact `app.tsuzuki.mangaall` provider identity
- trusted repository ID `app.tsuzuki.providers`
- package version code **9** and version name in the reviewed **0.5.4**
  family (future versions must be reviewed separately)
- the common three-source `en` facet, and existing enabled
  `reading.lookup` capability
- an actual installed package whose parsed manifest and source
  metadata agree with the current registry, including a second
  post-invocation check for revoked/replaced registrations

Calling this method on code8, a foreign repo, a code10+ package or a
gateway with default opt-in disabled returns `UNAVAILABLE` before
executing JS. The existing `ProviderReadingGateway.lookup()`,
`chapters()`, and `pages()` routes are not changed.

The V2 return type is `ReadOnlyAudit`, never `ProviderPage`.
The strict decoder denies V1 responses, unknown fields, malformed
source IDs, forged Reader/canonical eligibility and contradictory
completeness. `auditMangaAllV2ReadOnly` invokes V2 pages through
the **same** Host policy gate, feeds already-validated pages into the
bounded three-source cursor accumulator, and returns only
`IN_PROGRESS`/`INCONCLUSIVE`/`COMPLETE_OBSERVED` metadata.
No SQL associations, canonical editions, chapter IDs, source grants,
UI options or Reader pages are created.

## Android instrumentation proof, not live source availability

`.github/scripts/run_mangaall_host_journey_android.sh` now runs
the following offline tests on a disposable emulator:

- `UNSIGNED_CODE9_V2_HOST_INVOCATION_VERSION_GATED`: executes a
  synthetic code9 .tsz inside the actual SCRIPT/QuickJS runtime,
  verifies exact V2 closed tri-source envelope, V1 compatibility,
  code8 downgrade rejection, foreign repository, disabled opt-in,
  future code10, wrong language and a malformed legacy V1 response
- `UNSIGNED_CODE9_V2_HOST_RUNTIME_CURSOR_AUDIT`: performs a two-page
  synthetic SCRIPT audit (MangaDex success, MangaDot HTTP 403 with
  bounded retry, MangaFire success; then MangaDot success), preserving
  the per-source identity, candidate counts and historical 403
  without giving Reader or chapter authority

The existing V2 strict parser, bounded history and signed code8 Reader
HTTP-synthetic pixel regressions remain mandatory.

## Unfinished gates

1. The **test fixture is not a trusted published package**. The
   production artifact store and repository trust-root checks are
   still the only source of actual installed package trust. The
   development-only constructor opt-in does not sign a package.
2. Complete a security/release review and sign/publish code9+
   through a separately approved index update, with explicit V2
   provenance that distinguishes actual signed provider code9 from
   the current experimental `UNSIGNED_CANDIDATE_NOT_LIVE` marker.
   Currently the read-only decoder intentionally accepts only the
   latter marker.
3. Test real three-source host availability without bypassing
   HTTP403/challenges. A captured/replayed envelope and synthetic
   QuickJS/HTTP fixture are **not direct-live CDN pixels**.
4. Validate editorial identity independently, and then test all
   eligible sources in **one actual Reader UI session** while
   preserving the original chapter and progress.

Keep [Host #207](https://github.com/jssantogit/tsuzuki/issues/207)
and [Provider #72](https://github.com/jssantogit/providers-tsuzuki/issues/72)
open until these gates genuinely pass.
