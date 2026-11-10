# MangaAll 0.5.0 — Host A54 physical smoke

Source mirror of Host Draft PR #196 at commit `8b0c4be73d546cf6009d1a37f9cc076cbc0c6715`. This markdown
is not included in the Android binary; release-lane APK must be signed with
the existing app signing identity.

## Regressions and diagnostics

- On **Discover**, only not-installed Providers appear. Installed and
  update-available Providers remain in **Installed**.
- Official MangaDex/MangaDot legacy adapter packages must not appear as
  separate Discover options.
- Reader `SCRIPT_ERROR`: record closed `mangaall_network_source`
  (MANGADEX, MANGADOT or MANGAFIRE) in `reader_pages_ready` event.
- Chapter lookup failure: record bounded source in
  `provider_reading_chapter_attempt`.
- Grand Blue: check `NO_ELIGIBLE_MAPPED_SOURCE` known/active mapped
  references separately; no artificial Chapter/Reader binding.
- Confirm 0.5.0/code5 from signed official `app.tsuzuki.providers` *after*
  provider rollout; do not silently repin or overwrite v4 smoke trust root.
- Never uninstall the Host app, reset its data or reinstall unrelated providers.

Provider PR #61 remains independent; this APK alone does **not** distribute
MangaAll 0.5.0.
