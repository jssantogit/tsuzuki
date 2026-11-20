# MangaAll code9: official signed-index Host Android acceptance

**Status: prepared but intentionally NOT RUN / NOT PASSED.** As of this
change, the official Provider index remains **seq34, MangaAll code8**.
The source for code9 is reviewed and promoted, but code9 has no
authenticated publication. Fake index signatures and self-declared
signed provenance must never satisfy this gate.

## True prerequisites

1. The provider operator reviews and authorizes an actual
   `Publish repository` workflow_dispatch with literal
   `mangaall_code9_authorization=PUBLISH_MANGAALL_CODE9`.
2. The real P-256 root-signed official index then exposes **seq35**
   binding `app.tsuzuki.mangaall` version **0.5.4/code9** with
   the exact reviewed .tsz SHA. This must be corroborated through
   the separate Provider `MangaAll code9 — verify officially signed
   release (read-only)` audit, not assumed from manifest version.

The new Host workflow `MangaAll code9 — official signed index and installed
Host V2 Android` is **manual-only for actual installation**. Normal
pull requests merely compile the Kotlin instrumentation and check the
test script's shell syntax. It must FAIL CLOSED before the emulator if
the official signed seq35 isn't found and P-256 verified.

## Actual Android signed-install test (once prerequisites are true)

- Retrieves *current official* signed Provider index and code9 package,
  plus immutable signed code8 seq34 archived under git commit
  `99f3565a1e1edc0d8ed249b7597006c897f02636`.
- Verifies both original and new P-256 signatures, root fingerprint,
  each archive SHA, all unchanged unrelated providers and code9 exact
  reviewed-source byte parity. Copies the **real** packages and index
  envelopes into temporary AndroidTest assets.
- Runs a disposable **offline** emulator with `app.tsuzuki.dev` and
  invokes only
  `MangaAllSignedUpgradeIsolationTest.officiallySignedCode9FromIndex35_installsWithPinnedRootAndAuditsThreeSources`.
- Uses real `ProviderRepositoryManager`, the *actual original*
  signed index to install code8, then refreshes with the *actual new*
  signed index seq35 and upgrades to code9 using the original P-256
  repository key. No fake signature or non-original signing key.
- Reads the installed ledger through `StoredProviderScriptPackageSource`,
  requiring the precise artifact SHA and pinned root.
- Through the real Host `ProviderRuntimeClient`, real isolated QuickJS
  and production-style `ScriptProviderReadingGateway`, invokes code9
  V2 diagnostic lookup; source HTTP is **synthetic** and intercepted,
  all three MangaDex/MangaDot/MangaFire adapters run in the **same**
  lookup with one source responding HTTP403. Requires attributed
  partial evidence, no Reader/canonical authority, and Host-derived
  `SIGNED_INSTALLATION`. After rollback to authentic code8, V2 must
  be unavailable again.
- Both emulator process and its file-backed artifact ledger are
  disposable, with airplane mode enabled. Never writes a user's library,
  installs onto Galaxy A54, or sends remote API/CDN requests.

**Strict distinction:** this eventual PASS can certify *real signed
package installation* and a synthetic-source V2 runtime, **not**
live upstream availability, editorial matching, single-session Reader
source switching, CDN rendering or true physical device stability.
Those remain separately required and must preserve 403 fail-closed.

The workflow does not publish, sign, upload packages, edit the
signed index, require secrets, nor trigger a release. Current
main/code8 index does not meet its prerequisites.

Refs [Host #207](https://github.com/jssantogit/tsuzuki/issues/207),
[Provider #72](https://github.com/jssantogit/providers-tsuzuki/issues/72),
[Provider PR #80](https://github.com/jssantogit/providers-tsuzuki/pull/80)
and [Host PR #224](https://github.com/jssantogit/tsuzuki/pull/224).
