# Default Skin F6D implementation plan

Goal: refine F6C composition and scroll without changing reading/provider contracts.
Authority: user F6D implementation brief, superseding F6C phone grid fallback.
Baseline: cde46e656875d834628ca9898141600a2b0fd9a0 / tree b5c25b6a42cdeed2c87626e046e233bf5a47ad28.
Branch: design/default-skin-f6d-refinement; independent worktree created with pinned Orchestra candidate 4090da254168eb6df9ec7d1cc767cf48fb74292b.

- [x] Inspect canonical Git, Notion state/design/decisions/bugs and runtime candidate.
- [x] Isolated install/Doctor/native startup attempt; hooks not observed, no lease. Safely closed native sessions and uninstalled from external control after successful dry-run. Continue native fallback; field test PARTIAL, never claim native hooks PASS.
- [x] Reproduce stale year with same IDs in App CI; introduce batched SQLDelight snapshot observation, fingerprint filtering, no network.
- [x] Home scoped 48dp content-sized CTA; Details title/three genres, editorial information, inline saveable synopsis, continuous toolbar.
- [x] Compact Collections hierarchy and shared minimum-three-column policy; responsive covers and optional factual year.
- [x] One Search vertical scroll owner, preserve query/IME/horizontal rails; document header audit.
- [x] Focused behavioral regressions, lightweight preflight, full CI v2.1 checkpoint and reports.
- [ ] Final delivery-commit full CI, explicit Data test discovery and verified Notion synchronization.

Review focus: same-ID snapshot updates and config changes; missing/invalid dates; narrow cells/font scaling; long titles/genres and missing artwork; scroll anchoring/IME. No local heavy Android checks, APK, canonical promotion, main changes, runtime updates, source/provider/Reader changes, artificial workers or recovery events.


Evidence ledger:
- RED3fe15eda6 / CI39996232149:627 App tests, only same-ID year update fails.
- Implementation2a82dfa61 / CI39997703158: Format/Domain/Core/SQLDelight accepted; Data map type inference blocked downstream compilation.
- Correction8956f590a / CI39998397849: explicit batch-map type and contextual browse status titles; full gate SUCCESS.
- Independent read-only review: one P3 browse-status title omission, corrected. Device/IME/rendered cases explicitly unjudged.
- Ruling: keep the existing two-column cover-free Folder navigation gallery; mandatory three columns apply to work-poster grids, preserving the distinct domain hierarchy.
- Ruling: no Home Folder cover preview; available local projection has only ID/title and decoration does not justify new List execution or network.
- Ruling: safely remove quiescent, non-operational candidate from external controller and use native fallback; never claim Orchestra hooks/authority/continuity passed.
