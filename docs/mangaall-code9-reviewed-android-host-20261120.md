# MangaAll code9 — real reviewed archive / isolated Host QuickJS offline gate

Status: **REVIEWED CODE9 SOURCE, NOT SIGNED, NOT INDEXED, NOT INSTALLED**.

The official public repository `app.tsuzuki.providers` still has an
ECDSA-P256 signed index at sequence **34** pointing to MangaAll
**0.5.3/code8**. The promoted `0.5.4/code9` provider source is
`publication=release`, but must still be **manually authorized** through
`PUBLISH_MANGAALL_CODE9` and signed/indexed. The source flag itself
is never evidence of a verified installation.

This test extends the existing disposable Android emulator gate by
checking out the exact **Provider source-promotion commit**
`1314775fd0637aef5b4d153eea423e35bc0e3ac3`, verifying the
**existing** code8 index signature and artifact digests, then building
a code9 package for test assets. Its bytes must exactly match the
audited code9 review overlay over the original code8 archive.
It is labeled `code9-reviewed-unsigned.tsz` and is NOT included in
an official new index.

Instrumented test
`ScriptProviderReadingGatewayIsolationTest.mangaAllCode9ReviewedArchive_executesUnifiedV1AndRejectsUnsignedV2`
passes the **actual packaged code9** to the real Host
`ProviderRuntimeClient` / isolated QuickJS via a synthetic
OkHttp interceptor. The V1 lookup contacts MangaDex, MangaDot
and MangaFire **in one call**, preserves per-source retry isolation
when MangaDot is HTTP 403, and never sends external packets.
The V2 call is denied `UNAVAILABLE` even though the archive has
version `0.5.4`, official provider/repository IDs and a neutral wire
marker: a valid signed-index-backed `StoredProviderScriptPackageSource`
is required. A caller forging installed SHA and pinned root metadata
is also denied. No server credentials or cookie sharing occurs.

The gate does **not** pretend that an unsigned code9 is equivalent
to an officially installed signed-index-bound package. Positive
`SIGNED_INSTALLATION` cannot be proven until publication of the
real signed sequence 35 and installation through the authentic
Host ledger with the original pinned root. Neither synthetic source
replies nor the earlier code8 Reader pixel fixture demonstrates
live upstream/CDN traffic or source identity corroboration.
Reader/editorial grants remain prohibited for V2 diagnostic results.

Next actual release acceptance:
1. Operator explicitly authorizes first code9 publication.
2. Verify published signature, code9 artifact SHA, sequence 35
   and ledger-backed code9 installation.
3. Exercise code9 three-source live status under an authorized
   Android network, validate external editorial identities, and
   render real permitted CDN bytes in the Reader without
   challenge bypass or fabricated completeness.

See [Host #207](https://github.com/jssantogit/tsuzuki/issues/207),
[Provider #72](https://github.com/jssantogit/providers-tsuzuki/issues/72)
and [Provider PR #80](https://github.com/jssantogit/providers-tsuzuki/pull/80).
