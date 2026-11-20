# MangaAll code9: official P-256 signed-index Host Android acceptance

**Status: ACTUAL SIGNED PACKAGE INSTALLED AND EXECUTED IN DISPOSABLE ANDROID EMULATOR.**
This statement is supported by two green Android runs, not just Gradle
compilation or an unsigned preview. **Source HTTP was synthetic in the Host
emulator; live CDN image pixels and physical Galaxy A54 remain UNTESTED.**

## Published artifact and verified trust origin

The operator explicitly authorized the real publication
[Provider #44738222894](https://github.com/jssantogit/providers-tsuzuki/actions/runs/44738222894).
The official index now has **sequence 35** under the original P-256 root
`tsuzuki-provider-root-2026-02`, a *single* MangaAll `0.5.4/code9` entry
and SHA-256
`d9c223146faa9fea0e5b1593711b74836bc0af3c2939b2c6f246dbb2e4646622`.
The original code8 `0.5.3/code8` (index sequence 34) remains archived and
cryptographically verifiable.

[Provider PR #83](https://github.com/jssantogit/providers-tsuzuki/pull/83),
[CI #44740746693](https://github.com/jssantogit/providers-tsuzuki/actions/runs/44740746693)
prove real signed-index verification, original code8/hash parity, pinned root,
unchanged unrelated provider descriptors and **byte-for-byte equality** of
the code9 artifact to its independently reviewed three-file source overlay.
No fake signing key, forged Host provenance or self-declared Provider signature.

## Host Android verified outcomes

[Host PR #226](https://github.com/jssantogit/tsuzuki/pull/226),
[Android #44739129032](https://github.com/jssantogit/tsuzuki/actions/runs/44739129032):
**SUCCESS**, compiled and ran the authentic signed-index install test
introduced by [Host PR #225](https://github.com/jssantogit/tsuzuki/pull/225).
The test used `ProviderRepositoryManager` to install code8 from real P-256
seq34, upgrade using genuine P-256 seq35, then checked installed SHA,
`StoredProviderScriptPackageSource`, trust fingerprint, Host-derived
`SIGNED_INSTALLATION`, the actual packaged JS executing in isolated QuickJS
with MangaDex, MangaDot and MangaFire inside one V2 call, 403 challenge
attribution, rollback and V2 rejection under code8.

[Host PR #227](https://github.com/jssantogit/tsuzuki/pull/227),
[Android #44743043672](https://github.com/jssantogit/tsuzuki/actions/runs/44743043672):
**SUCCESS**. Extended the same authentic signed-install test beyond empty
source responses. Under **synthetic** source HTTP, MangaDex returns Naruto
plus the alternate label `Naruto (Colored)`, MangaDot returns a separate
Naruto candidate with a distinct source ID, and MangaFire returns HTTP403.
The real QuickJS/Host V2 audit preserves the two identities as
**read-only competing observations**, marks the third source
`CHALLENGE_BLOCKED`, continues the bounded cursor history and never
picks an editorial edition or grants a Reader binding. Rollback to code8
still disables the V2 entrypoint. The full instrumented test and both
Gradle jobs were green.

Both runs used disposable **offline** Android emulators, intercepted source
HTTP, the actual P-256-indexed Provider packages and real Host ledger/QuickJS.
Neither run used the user's physical device, production library, external
source HTTP or CDN pixels.

## Complementary real upstream evidence, not Android proof

[Provider PR #84](https://github.com/jssantogit/providers-tsuzuki/pull/84),
[real upstream runner #44741273417](https://github.com/jssantogit/providers-tsuzuki/actions/runs/44741273417)
queried the real **signed code9 source bytes** against all three sources in
one MangaAll lookup for each of Naruto, Death Note and Bleach. Status-only
results preserve healthy-source candidate counts and incomplete states:
MangaFire was HTTP403 for all three titles; MangaDot had an
`INVALID_RESPONSE` for Death Note. All three were **partial/inconclusive**.
The runner did **not** install a Provider or verify chapters/CDN images.
See [sanitized Provider evidence](https://github.com/jssantogit/providers-tsuzuki/blob/main/docs/mangaall-code9-real-three-source-run-20261120.md).

## Safety after evidence collection

The one-time signed-install branch triggers used for Host #226/#227 are
retired by the follow-up cleanup. The official workflow
`.github/workflows/mangaall-code9-official-signed-install-android.yml` now
executes the real signed-install emulator **only on explicit
`workflow_dispatch`**; ordinary PRs compile instrumentation only.
`.github/scripts/test_mangaall_signed_install_manual_only.py` prevents
accidentally reintroducing automatic signed-install triggers.

The signed code9 V2 endpoint is still **experimental, read-only and opt-in**.
A cryptographically verified installation does not by itself corroborate
canonical title identity, authors, chapters, source choice, Reader navigation
or display real CDN pixels. Such features require separate evidence and
fail-closed handling of 403 challenges; no captcha bypass or cookie borrowing.

Tracking: [Host #207](https://github.com/jssantogit/tsuzuki/issues/207),
[Provider #72](https://github.com/jssantogit/providers-tsuzuki/issues/72).
