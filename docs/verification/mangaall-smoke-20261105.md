# MangaAll post-smoke verification · 2026-11-05

Physical smoke from app.tsuzuki 0.20.4 (26a2a88) found unrelated work candidates,
missing eligible Reader source options and redundant source badges.

This Release smoke branch is based on Host PR #196 at 341a4ac, plus only a
nonfunctional CI regression assertion, verified by CI v2.1 #42088417139
and Android gate #42088417050 (both successful).

For the next smoke: install this persistently signed **app.tsuzuki** APK over
the existing app (never uninstall the Host); install the new provider archive
from the separate, test-only MangaAll v2 repository following its exact
enrollment file. The prior one-off smoke index cannot sign a new release:
its signing private key was ephemeral and not retained. If the installed
MangaAll v1 is tied to the old repository trust origin, uninstall **only**
MangaAll first and enroll the v2 test repository; retain existing other
providers and app data. Test canonical chapters/images from MangaFire,
MangaDex, and MangaDot; distinguish work candidates from chapter sources.
MangaDex and MangaDot must not appear until verified Reader source evidence
is available. Collect diagnostic capture and screenshots. Do not publish
MangaAll to the official index before physical acceptance.
