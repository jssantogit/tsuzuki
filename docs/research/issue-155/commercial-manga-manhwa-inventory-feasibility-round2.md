# Issue #155 — Round 2: feasible chapter inventory sources for manga, manhwa and manhua

**Research date:** 2026-10-25  
**Status:** evidence and design options, **NOT** authorization to build new adapters or widen the promotion policy.  
**Scope:** Tsuzuki Architecture A; [issue #155](https://github.com/jssantogit/tsuzuki/issues/155), [PR #156 Draft](https://github.com/jssantogit/tsuzuki/pull/156); follow-up to [primary-editorial-source-feasibility.md](./primary-editorial-source-feasibility.md) and #154 Reports A/C.

## Non-negotiable product invariants

- Tsuzuki ships with **no active catalog**. User-enabled integrations discover works; a chapter-inventory capability may enrich only explicitly resolved works. **No seeded chapters, titles, publisher services or universal publisher API bundled into Core**, no automatic global network sweeps, and no required reading Provider.
- Chapter existence, source trust, editorial coverage, edition/language, and reading availability are separate dimensions; a number or aggregate release is not a chapter identity. Preserve existing `tsuzuki:count:*` IDs and all history/downloads/progress/overrides, v1 sync/backup compatibility, zero accidental tombstones; no v2 activation.
- Assess **four independent gates** for each candidate: (1) individual units are actually enumerated; (2) exact work + edition identity is attainable; (3) programmatic acquisition/reuse is documented/permitted; (4) payload structure, rate budget, and changes are technically maintainable. A public web page can pass (1) without passing (3).
- Reports #154 C (149 controlled queries) found MU/MD release feeds, Kitsu count-derived placeholders, Bangumi book `episodes` largely absent in samples; do not treat these as ground truth of real editorial inventory.

## Source-by-source feasibility (primary references)

| Candidate | Individual units observed? | Allowed machine acquisition established? | Role / current verdict |
| --- | --- | --- | --- |
| **Shueisha volume tables of contents** | **Yes.** Black Clover v21 identifies 195–205 and v31 304–314, with individual Japanese titles and page references; Tokyo Ghoul v1 explicitly lists #001–#009, :re v16 labels 165–179. | **No:** publicly readable does not establish permission to crawl or retain/reuse. Investigate direct licensed/approved feed or partner path; no scraper. | High-quality **edition-scoped ground truth and fixture**, gated collector. ISBN/JDCN identifies volume/format, not a globally unique serialization chapter. |
| **MANGA Plus** | **Partial.** Official page for Black Clover lists early and latest entries; official FAQ acknowledges first/latest-only displays and differences between web/app and regions. | **Not established** for undocumented title-detail API. | Partial individual observed entries from a publisher, not proof that the middle chapters do not exist; no unsupported API calls. |
| **Kakao Webtoon / KakaoPage** | **Yes on public pages.** Solo Leveling has a chapter 179 finale and 21 numbered extra episodes; KakaoPage product listing includes chapter numbers, prologue, video prologues and reports `전체 203` entries. | **Not established** for automated extraction / private app APIs. | Valuable Korean editorial ground truth; `203` is not `203 main chapters`. |
| **WEBTOON official** | **Yes on pages.** Tower of God web list has `[Season 3] Ep. 235` and global site item `#652`, with other seasons restarting at Ep. 0/1. Omniscient Reader official list shows `Episode 308` vs site `#309` — distinct counters. | **No blanket permission.** WEBTOON Terms prohibit **unauthorized** crawling/scraping/harvesting. A visible `Rss` link points to an official `/rss?title_no=95` URL; endpoint exists, but XML content/fields/retention and permission for an inventory client **were not validated** (research tools reject RSS content type). Ask publisher or review express RSS terms before use. | Edition/language specific proof; RSS might serve **incremental** updates, not historical full inventory. Do not add HTML crawler. |
| **Wikimedia/Wikipedia** | **Yes, for selected manga.** Wikipedia chapter-list pages for Black Clover and Tokyo Ghoul organize chapters into volumes; Tokyo Ghoul original, :re and one-shots are separately described. Coverage for manhwa/manhua is uneven, unknown until work-level validation. | **Yes, conditionally** via public MediaWiki APIs with compliant User-Agent, 2026 rate limits, CC BY-SA article-specific attribution, revision link and share-alike where relevant. No scraping necessary. | Best feasible **opt-in community-curated candidate index**, NEVER `PRIMARY_EDITORIAL` by virtue of citing a publisher. Automated canonical promotion under current #155 is forbidden; any user-adopted chapter proposal would require separate policy/design approval. |
| **Open Library** | Schema supports edition-level `table_of_contents[]` `toc_item`; ISBN and edition JSON API is officially documented. **Not validated** that Black Clover / Tokyo Ghoul editions have populated usable ToCs. | Official work/edition JSON API exists, usage/attribution must be respected. | Promising *opportunistic* edition-specific enrichment; not an inventory backbone and cannot infer chapter count from edition metadata. |
| **KMAS (Korea Manhwa Contents Agency / 만화규장각)** | Documented JSON API exposes manga/books/webtoon bibliographic records, ISBN, source country, dates, platform/edition descriptors. **No individual episode array established in published response schema.** | **Official, approval required:** account + identity verification, API key and admin approval; documented standard limit 1,000 requests/day. | Useful Korean metadata and series/edition crosswalk, **not yet** independently enumerating chapters; explore partnership/request extra fields. |
| **AniList, MAL, Kitsu / Bangumi** | AniList `Media.chapters` is a completed-work scalar; Kitsu chapter resources can be synthesized from counts. Bangumi Archive exports episode schema, but #154's book corpus did not establish chapter coverage. | Provider-specific terms, authentication and APIs as already managed by Tsuzuki. | Metadata/identity and reported counts only by default, never fill 1..N. |
| **MangaUpdates / MangaDex** | Release records with ranges, repeated uploads, languages and edition uncertainty. | Optional existing bounded native collectors in #156; permission/attribution remain source-specific. | Shadow release corroboration; no direct promotion, irrespective of `INDIVIDUAL`-shaped release labels. |
| **Kuaikan (Chinese manhua)** | Official site offers chapter lists and distinguishes e.g. 112 total contents vs 99 main story items on one series; represents Chinese market with extras/revisions. | **No official public metadata API or automated-use permission established here.** | Chinese editorial evidence of the same count != chapters problem; research/permission-gated, not automatic adapter. |
| **Local user-owned EPUB 3** | Normative EPUB navigation document has a `toc nav` hierarchy; entries may correspond to sections rather than chapters. | Parsing a lawfully accessible, non-DRM local document does not require scraping a publisher website. Respect file/DRM rights and do not automatically upload or copy comic assets. | Attractive **user-imported edition ToC** for supported files. Each heading needs structural validation and explicit edition selection; NEVER infer global serialization from a single volume. |
| **OPDS 2.0** | A catalog can enumerate publications/volume editions and metadata, but OPDS 2.0 does **not** guarantee per-chapter listings. | Standards-based feeds where users authorize access. | Edition discovery, not automatically editorial chapter inventory; only use ToC if underlying publication/manifest actually supplies it. |
| **ONIX 3.1 / bibliographic supply chain** | Rich product/volume metadata and product identifiers; optional publication components, not guaranteed individual manga chapter contents. | Standard is open; **actual publisher/retailer data feeds are not inherently public or licensed to Tsuzuki**. | Potential future authorized partner feed for crosswalk; not a free worldwide chapter API. |
| **Third-party scraped API/data mirrors** | May expose chapter URLs and large coverage (including English translations), but often contain reading releases, not editorial original; data could be scraped from restricted services. | **Not established** by third-party selling an API or posting a repo license; upstream rights/terms still apply. | Exclude from default canonical editorial path; at most evaluate separately under provenance, permission, and distribution restrictions. |

### Directly examined examples: ground truth vs claims

1. **Black Clover v31:** publisher editorial TOC has labels `ページ304` through `ページ314`, each with a named unit, plus `EXTRA PAGES`. Test: extras never silently become 315; volume is edition-specific. https://books.shueisha.co.jp/items/contents.html?jdcn=08X10000000018964700
2. **Tokyo Ghoul v1 / Tokyo Ghoul:re v16:** publisher provides discrete units `#001..#009` for original and `165..179` in the different series. Test: separate works/series, special and one-shot units, Japanese vs translated volume editions; no shared chapter `1` by title similarity. https://books.shueisha.co.jp/items/contents.html?jdcn=08879272879272345501 and https://books.shueisha.co.jp/items/contents.html?jdcn=08891050879272315501
3. **Solo Leveling:** Kakao lists `179화(완결)` and explicitly `외전 1화..21화`; KakaoPage reports `전체 203` products including separate video prologues, prologue, 1화 etc. Test: count is not 203 story chapters and extra branch has its own label. https://webtoon.kakao.com/content/2320/2320 and https://page.kakao.com/content/50866481/product-list
4. **Tower of God:** Official English WEBTOON `[Season 3] Ep. 235` has site `#652`, while season 1 / 2 both start at Episode 0; Fandom community list includes part and arc numbering. Test: `season/part + local ordinal + upstream episode key` are separate from site global count and translated site numbering. https://www.webtoons.com/en/fantasy/tower-of-god/list?page=1&title_no=95 and https://towerofgod.fandom.com/wiki/List_of_Chapters
5. **Omniscient Reader:** Official WEBTOON listing `Episode 308` and site `#309` is an observed off-by-one between episode label and total item sequence. Test: do not treat provider's site item counter as canonical episode number. https://www.webtoons.com/en/action/omniscient-reader/list?page=1&title_no=2154

**Method:** manually review publicly indexed, publisher/standards/API documentation and catalog pages by work. **Not** an automated corpus crawl, not tests of unauthenticated direct JSON REST endpoint responses for all sources, and not a completeness measurement. Counts above are what the cited public pages report as of research time and can change. The legal evaluation is risk classification, not legal advice.

## Important negative and positive discoveries

- The presence of an official website **does not** authorize a third-party metadata crawler. VIZ terms prohibit unauthorized automated retrieval; WEBTOON explicitly prohibits unauthorized scraping/harvesting. MANGA Plus publishes regional, incomplete subsets and has no approved official read-only episode API established in this study.
- The official WEBTOON UI offers an RSS URL, which is a **new investigation lead** distinct from unofficial Naver endpoints. However, a feed proves neither historical enumeration nor license for the intended use. Require feed XML sample, item GUID/date/episode identity, retention and explicit usage permission before adapter.
- The Korean **KMAS** API is a genuine government/institution-supported, key/approval-based API. It helps identify **Korean book/webtoon works and editions** but published fields do not prove any per-episode enumeration. Request extended episode dataset rather than invent missing fields.
- Open Library has a **real, explicit `table_of_contents[]` field** at *edition* level. The field is optional and no relevant manga case has been empirically verified positive, so count this as an *unproven corpus* route.
- Wikimedia has published **new 2026 API rate limits**, and both identifier requirements and attribution/ShareAlike are operational design requirements. Store page ID, exact revision, source URLs, license/author attribution and observed timestamp (or attribution pointers) for any reused data. Scope to a user-confirmed work; do not enumerate Wikipedia globally.
- Wikipedia list-of-chapter pages are not guaranteed uniform. **A generic HTML table selector across every article is not a trustworthy global parser.** Test selected page/template layouts, allow only explicitly labeled individual entries, surface parse conflicts and refuse inference over gaps. Wikimedia/Wikidata sit **below publisher authority** even when references cite publishers.
- Two appearances of the same upstream content (Wikipedia, Wikidata, a mirror of the same page) are **one dependency**, not independent confirmation.
- Local EPUB `toc nav` is a **standardized accessible publication navigation structure**, not an assertion that all headings are discrete comic chapters. Publisher volume TOCs, verified edition identity and user consent are prerequisites for adoption.

## Proposed domain distinctions (not yet approved)

```text
Source reliability / provenance:
  PUBLISHER_VERIFIED (authorized first-party individual record, verified exact edition)
  COMMUNITY_CURATED (e.g. Wikipedia, revision/attribution retained)
  USER_SUPPLIED_EDITION_TOC (locally inspected, edition confirmed by user)
  RELEASE_OBSERVATION (MU, MD, language/scanlation uncertainty)
  DECLARED_TOTAL (AniList/Kitsu etc; scalar only)

Canonical status:
  observed / candidate
  user-adopted (possible future feature; explicit acknowledgement, NOT publisher verified)
  editorially-confirmed (existing strict #155 gate, plus exact identity)
  contradicted / corrected / unknown

Separate from read availability and from technical cursor exhaustion.
```

Do **not** grant `COMMUNITY_CURATED` or `USER_SUPPLIED_EDITION_TOC` the existing `PRIMARY_EDITORIAL` flag to circumvent `InventoryClaimPolicy.mayPromote`. The alternative "user-adopted canonical unit" is a substantial new product/identity policy needing user approval and explicit sync/backup preservation tests. No false status badges of "official", "complete" or "readable" for these candidates.

## Investigation gates to *actually* prove production feasibility

| Gate | Minimum bounded proof | What counts as failure |
| --- | --- | --- |
| A. No seeded catalog | Fresh database, all integrations disabled; no catalog/work import, no background inventory HTTP or Reader fetch | Hidden built-in title seed or auto enablement |
| B. Community acquisition | With user-enabled Wikimedia integration, fetch 1 *verified* page/revision of Black Clover/Tokyo Ghoul via documented API; record exact source revision, license, mapping and explicit chapter labels | Generic title fuzz match, missing licensing, incomplete pages represented as complete |
| C. Authoritative acquisition | Obtain written/use-policy-approved sample feed or publisher-provided structured metadata for edition IDs and individual units (or valid locally imported edition TOC); run shadow-first | Scraped site, undocumented client API or no chapter unit IDs |
| D. Korean material | Run deterministic fixtures from Kakao `179`, `외전 21` and WEBTOON season-local/global numbering; if trying KMAS obtain approved key and verify response shape first | Merge specials into 180–200, trust product count, assume KMAS episode API |
| E. Core identity | Verify edition/language boundaries, zero and decimal chapters, volume-local resets, progress historical `count:*` row matching without destructive rekey | Any silent promotion/creation by count or unverified translation/release |
| F. Offline/revocation | Disable integration after saving *permitted* evidence, restart offline, verify chapters and metadata retention policy, Reader correctly unavailable without reading provider | Delete rows/progress, tombstones, data retained contrary to license |
| G. UX / Android | Smoke on physical APK only after integrated code and CI exact SHA; explicit opt-in and source/provenance distinction shown | Claiming physical success from tests/CI |

**Recommended next spike:** Wikimedia API / selected work-level user-confirmed page identity in shadow mode, plus **local EPUB navigation fixture**, with zero catalog bootstrap. Simultaneously investigate publisher's partner/permissions and KMAS data scope, but do not block Core correctness on agreements with those companies. Keep `#155` Draft until real editorial admissibility and Reader/Android acceptance are demonstrated; future `USER_ADOPTED` path remains a product decision.

## Sources

- Shueisha Black Clover https://books.shueisha.co.jp/items/contents.html?jdcn=08X10000000018964700
- Shueisha Tokyo Ghoul v1 https://books.shueisha.co.jp/items/contents.html?jdcn=08879272879272345501
- Shueisha Tokyo Ghoul:re v16 https://books.shueisha.co.jp/items/contents.html?jdcn=08891050879272315501
- MANGA Plus official partial chapter policy https://mangaplus.shueisha.co.jp/faq/eng/
- Kakao Webtoon https://webtoon.kakao.com/content/2320/2320 and KakaoPage https://page.kakao.com/content/50866481/product-list
- WEBTOON Tower of God https://www.webtoons.com/en/fantasy/tower-of-god/list?page=1&title_no=95
- WEBTOON Omniscient Reader https://www.webtoons.com/en/action/omniscient-reader/list?page=1&title_no=2154
- WEBTOON policy https://www.webtoons.com/legal/terms/
- Korean KMAS official Open API https://www.kmas.or.kr/guide/openapi
- Wikimedia access https://www.mediawiki.org/wiki/Wikimedia_APIs/Access_policy
- Wikimedia 2026 rates https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits
- Wikimedia reuse https://www.mediawiki.org/wiki/Wikimedia_APIs/Content_reuse
- Wikipedia Tokyo Ghoul chapters https://en.wikipedia.org/wiki/List_of_Tokyo_Ghoul_chapters
- Wikipedia Black Clover chapters https://en.wikipedia.org/wiki/List_of_Black_Clover_chapters
- Open Library API https://openlibrary.org/dev/docs/api/books and edition schema https://openlibrary.org/type/edition
- EPUB core official W3C https://w3c.github.io/epub-specs/epub33/core/
- OPDS official specification https://specs.opds.io/opds-2.0
- AniList official GraphQL Media schema https://docs.anilist.co/reference/object/media
- Kuaikan official site https://www.kuaikanmanhua.com/web/topic/2177/
- Tower of God community chapter list https://towerofgod.fandom.com/wiki/List_of_Chapters
