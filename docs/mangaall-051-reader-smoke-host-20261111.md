# MangaAll 0.5.1 Host signed smoke — November 11 2026

APK mirror contains exactly the executable tree of [Host Draft #196](https://github.com/jssantogit/tsuzuki/pull/196) at `0ca9ec34b48b018b2df264ae3962c5396d90cf95`, plus this unshipped checklist.

* Install over existing Tsuzuki 0.20.4 without uninstall or data reset.
* The official signed repository `app.tsuzuki.providers` has MangaAll 0.5.0/code5 until Provider PR #62 is verified and published; use its same identity, key and update flow for 0.5.1/code6, **never** a new smoke root.
* Confirm already-installed providers remain only under Installed and updates there, not duplicated in Discover.
* On a chosen MangaDex chapter, check Reader `provider_page_failure_stage` instead of opaque `SCRIPT_ERROR` after updating MangaAll 0.5.1.
* Grand Blue: collect `provider_reader_mapped_missing_provider_family` on `PROVIDER_NOT_REGISTERED`. Do not synthesize or migrate editorial bindings automatically.
* Check Monster, Jujutsu Kaisen, Kaguya-sama, Grand Blue, and a regular control Reader work with actual image rendering, not just page URLs.
* MangaDex search HTTP `RESPONSE_LIMIT` must be rechecked only after provider 0.5.1 is installed, because the Host deliberately retains its 192 KiB response ceiling.
* No Host merge until physical smoke.
