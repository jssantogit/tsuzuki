# MangaALL code10 — future signed-only V2 diagnostics readiness

**2026-11-21. Status: PREPARATION ONLY; NOT DEPLOYED, NOT SIGNED.**

Provider [#97](https://github.com/jssantogit/providers-tsuzuki/pull/97)
contains a reproducible **unsigned** `0.5.5/code10` review package.
A code10 release has NOT been approved or P-256 signed. The currently
installed officially published Provider is **code9/0.5.4**, sequence35.

## Host V2 gate (experimental, off by default)

The Host's existing V2 read-only diagnostic gateway was hardcoded to
code9, and otherwise blocked the code10 source regardless of package
quality. This preparation introduces a **closed allowlist**:

- Signed code9: exact runtime `0.5.4`, version code 9.
- Future signed code10: exact runtime `0.5.5`, version code 10.
- Code8 and code11+, wrong code/name pair, foreign repository ID and
  mismatched parsed-manifest code: denied.

For BOTH signed versions, **all** existing checks remain mandatory:
`StoredProviderScriptPackageSource` (installed artifact ledger), exact
SHA256 equality between executed package bytes and installed digest,
pinned P-256 repository trust-anchor fingerprint, correct Provider ID,
separate `enableExperimentalMangaAllV2=true` opt-in, and post-invocation
registry/package revocation recheck. The JS string
`V2_READONLY_OBSERVATION` cannot establish installed-package trust.

The **only** synthetic unsigned V2 fixture still allowed for testing is
the historical test-only `code9/0.5.4-dev-unsigned` when an inaccessible
production-only fixture flag is explicitly enabled. Unsigned code10
`0.5.5-dev`, `0.5.5-dev-unsigned` and even a forged locally constructed
`0.5.5/release` archive remain **UNAVAILABLE**, including with a forged
installed SHA/root in the test gateway. No new network permissions,
Reader pages, editorial authorization, SQLite associations, or production
auto-selection arise from these diagnostic changes.

## Required before claiming code10 V2 integration

1. Finish independent code10 source and release ZIP review, including
   the exact SHA of the final package (the previous candidate was
   superseded after a real MangaDex cross-work chapter relationship fix).
2. Obtain separate explicit operator approval for publication, prepare
   an approved-artifact lock and an authorized manual dispatch; verify
   signed index sequence36 and the actual installed code10 SHA under
   the pinned P-256 trust anchor.
3. Only then execute the existing real-install/QuickJS V2 audit with
   code10 and its real three-source responses. Existing code9 positive
   installed-package test remains the regression baseline.
4. Run unified Naruto, Death Note, Bleach Host source-to-canonical/SQLite/
   Reader smoke before the 10–30-work sweep or physical Galaxy A54 tests.

The V2 parser returns **read-only** evidence. Even a signed-installed
code10 V2 package must never grant a chapter's canonical edition,
verified count, source association or Reader permission.
