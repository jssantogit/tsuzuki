# Tsuzuki Provider Platform — Design

Status: **approved design / implementation contract**

Date: 2026-10-03

## Goal

Replace Tsuzuki's inherited split between Integrations, Mihon Add-ons/extensions, torrent services and future Debrid/P2P plumbing with one product-level concept: **Provider**.

A Provider is an identifiable supplier of one or more typed capabilities. It may supply catalogs, metadata, ratings, account lists, reading content, torrent discovery, Debrid resolution or native acquisition. The user should not need to understand whether the implementation is built into Tsuzuki or executed by the local script runtime.

This redesign must remove downloaded APK/Dex/JAR execution as the long-term extension model while preserving Tsuzuki's source-independent canonical work/chapter model and Reader behavior.

## Decision summary

1. **Provider is the only user-facing extensibility concept.**
2. Provider behavior is described by **versioned capabilities**, never by one exclusive provider type.
3. Runtime implementation is internal. V1 supports **BUILTIN** and **SCRIPT** providers; this is not a product category.
4. Script Providers execute locally with **QuickJS-kt** in an Android isolated process and receive no Android application authority.
5. Privileged/platform work is owned by **Host Services** in the Tsuzuki process: HTTP, browser, DOM, storage, secrets, binary/archive, crypto, image processing and diagnostics.
6. Script Providers never download or execute Dex, JAR, APK or native libraries.
7. Large/binary data remains host-side and is referenced from scripts through opaque bounded resource handles.
8. Provider repositories are static-hostable, signed catalogs. Repository indexes are signed, artifacts are SHA-256 pinned, versions are monotonic, and installs are atomic/rollback-capable.
9. Tsuzuki's canonical work/chapter/runtime remains above Providers. Providers report observations/candidates; they do not own canonical identity.
10. Torrent discovery, Debrid and direct P2P use the same Provider registry but remain separate capabilities and execution responsibilities.
11. HTTPS is a **delivery mechanism**, not a Provider type. Torrent/Debrid/P2P may ultimately produce the same readable HTTP/local resource contract as an ordinary reading Provider.
12. Mihon/Keiyoushi APK compatibility is a temporary development bridge only. The final Provider Platform does not retain APK execution as a permanent runtime.

## Empirical evidence

This design is based on the current Tsuzuki codebase plus the completed Provider Platform spike stack:

- PR #96 — isolated QuickJS runtime foundation.
- PR #99 — Binder host-bridge boundary.
- PR #100 — real HTTP and WebView brokers.
- PR #101 — complex archive/crypto/image reading pipeline using host-side resource handles.
- PR #102 — signed repository/update/rollback supply chain.
- PR #103 — torrent/Debrid convergence plus real selected-file jlibtorrent loopback transfer.

The final spike proves the earlier emulator cases together: isolated execution, bounded host calls, real broker policy, provider-isolated browser state, complex host-side binary processing, current Mihon-extension compatibility fixture, and selective direct BitTorrent acquisition.

Spike APIs are evidence, not automatically the production API described here.

## Non-negotiable Tsuzuki invariants

The Provider Platform must preserve existing domain guarantees:

- A canonical work is independent of any provider/source.
- Metadata provider, reading-content provider and transport/acquisition remain distinct responsibilities even when one visible Provider implements several of them.
- Canonical chapter identity is owned by Tsuzuki, not by provider chapter IDs.
- Unsafe/ambiguous chapter evidence remains fail-closed.
- Existing chapter-0/fractional-chapter safety rules remain intact.
- Editorial chapter counts never become reading inventory.
- Reading discovery remains bounded/progressive rather than fanning out unboundedly.
- Finding a work binding is not proof that a selected chapter is readable.
- Provider changes must not fragment progress, downloads or canonical work identity.
- Cross-provider identity enrichment remains exact/verified; title similarity is not identity proof.
- A Provider failure must fail soft at the provider boundary and must not corrupt canonical state.

## 1. Provider domain model

### 1.1 Provider identity

```kotlin
@JvmInline
value class ProviderId(val value: String)

data class ProviderVersion(
    val name: String,
    val code: Long,
)

data class ProviderDescriptor(
    val id: ProviderId,
    val name: String,
    val version: ProviderVersion,
    val origin: ProviderOrigin,
    val runtime: ProviderRuntimeKind,
    val capabilities: Set<ProviderCapabilityRef>,
    val permissions: ProviderPermissionSet,
    val settings: List<ProviderSettingDescriptor>,
    val contentLanguages: Set<String>,
)
```

Provider IDs are stable across updates. Repository changes must not create a new Provider identity unless the logical Provider itself changed.

### 1.2 Runtime kind

```kotlin
enum class ProviderRuntimeKind {
    BUILTIN,
    SCRIPT,
}
```

This is internal implementation metadata.

Examples:

- MyAnimeList: BUILTIN.
- Kitsu: BUILTIN.
- a manga website port: SCRIPT.
- a torrent indexer port: SCRIPT.
- a Debrid service adapter: normally a separately distributed Provider.
- a P2P acquisition Provider: separately distributed, while privileged BitTorrent transport remains host-owned.

The user sees all of them as Providers. Runtime kind does not define a product category and must not force a concrete service into the application core.

### 1.3 Capabilities

A Provider declares zero or more independently versioned capabilities.

Initial namespaces:

```text
catalog.search@1
catalog.discover@1

metadata.basic@1
metadata.artwork@1
metadata.editorial@1
metadata.staff@1
ratings.read@1
relations.read@1

account.tracking@1
account.lists@1
library.remote@1

reading.lookup@1
reading.chapters@1
reading.pages@1

torrent.search@1
debrid.resolve@1
acquisition.p2p@1
```

The list is extensible. A capability existing in the vocabulary does not imply that every Provider implements it.

Capabilities describe **what the Provider contributes to Tsuzuki**. They are separate from Host Service permissions describing what a script is allowed to use.

A breaking capability contract creates a new capability version. Additive optional fields may evolve within one version when old consumers can safely ignore them.

### 1.4 No exclusive Provider type

Do not introduce:

```text
ProviderType.MANGA
ProviderType.METADATA
ProviderType.TORRENT
ProviderType.DEBRID
```

A single Provider may legitimately expose several unrelated capabilities. Exclusive types would recreate the silos this redesign is intended to remove.

### 1.5 Provider facets

A Provider may expose internal operational facets such as language, mirror, endpoint or edition.

```kotlin
data class ProviderFacetRef(
    val providerId: ProviderId,
    val facetId: String,
)
```

Facets are not separate Providers and do not become canonical work identity.

This replaces the Mihon-specific assumption that internal `CatalogueSource` long IDs are the universal execution identity.

### 1.6 Capability invocation contract

Capabilities use typed request/result envelopes. Script JSON is an external boundary and is validated before it becomes a domain object.

Conceptually:

```kotlin
data class ProviderPage<T>(
    val items: List<T>,
    val nextCursor: String?,
)

sealed interface ProviderCallResult<out T> {
    data class Success<T>(val value: T) : ProviderCallResult<T>
    data class Failure(val error: ProviderError) : ProviderCallResult<Nothing>
}

data class ProviderError(
    val code: ProviderErrorCode,
    val retryable: Boolean,
)
```

Rules:

- capability inputs and outputs have versioned schemas;
- required fields, enum values, collection sizes, identifiers, URLs and numeric ranges are validated at the runtime boundary;
- unknown additive output fields may be ignored when the capability version allows forward-compatible additions;
- malformed results never flow into canonical reconciliation;
- Provider exceptions/stack traces never become the public error contract;
- a Provider may not return Android/framework objects across the boundary.

List-style capabilities use opaque cursor pagination rather than exposing page/offset assumptions:

```text
catalog.search@1      -> ProviderPage<ProviderWorkCandidate>
catalog.discover@1    -> ProviderPage<ProviderWorkCandidate>
reading.lookup@1      -> ProviderPage<ProviderWorkCandidate>
reading.chapters@1    -> ProviderPage<ProviderChapterObservation>
torrent.search@1      -> ProviderPage<TorrentCandidate>
```

The opaque cursor is scoped to Provider ID + capability version + request fingerprint. It is bounded in size, is never interpreted as canonical identity, and must not be reused after a configuration/artifact change unless that capability explicitly guarantees compatibility.

Remote/provider ordering is preserved unless the calling Tsuzuki domain contract explicitly asks for a different sort.

### 1.7 Read-only calls, side effects and idempotency

Capabilities declare whether an operation is read-only or side-effecting.

Read-only operations may be retried within normal bounded network/runtime policy.

Side-effecting operations — for example account tracking writes or Debrid torrent submission — receive a host-generated stable `operationId` representing the user's intent.

Rules:

- the executor does not blindly retry a side effect after an unknown outcome;
- retries of one intent reuse the same `operationId`;
- reusing an `operationId` with a different normalized request payload fails closed;
- adapters persist/reconcile enough operation state to determine whether the remote effect already exists before repeating it when the upstream API lacks native idempotency keys;
- long-running effects expose a stable job/reference ID and typed `PENDING / SUCCEEDED / FAILED / UNKNOWN` state rather than forcing callers to infer success from timeouts.

This requirement applies equally to BUILTIN and future SCRIPT implementations of side-effecting capabilities.

## 2. Provider registry

Tsuzuki converges on one `ProviderRegistry`.

It owns:

- installed/enabled Provider descriptors;
- capability lookup;
- Provider origin/version;
- facet discovery;
- runtime adapter selection;
- settings/config fingerprints;
- Provider lifecycle status.

The first production implementation may adapt the existing `IntegrationRegistry` and current reading backend behind this facade. The end state must not require callers to choose between IntegrationRegistry, AddonRepository, ExtensionManager and future torrent/debrid registries.

Typical caller usage:

```kotlin
val providers = providerRegistry.enabled(capability = ReadingChaptersV1)

val result = providerExecutor.invoke(
    providerId = provider.id,
    capability = ReadingChaptersV1,
    input = request,
)
```

Callers know the capability they need, not the implementation runtime.

## 3. Script Provider artifact

### 3.1 Artifact format

Native script Providers are distributed as `.tsz` artifacts.

V1 `.tsz` is a bounded ZIP container:

```text
provider.tsz
├── manifest.json
├── main.js
├── modules/
│   └── ...
└── assets/
    └── ...
```

Rules:

- ES modules are the executable format.
- TypeScript may be used by authors but is compiled before publication.
- Provider artifacts may contain JavaScript, JSON/text modules and bounded passive assets.
- V1 artifacts may not contain Dex, JAR, APK, `.so` or executable native code.
- Generic WebAssembly execution is not part of V1. It may be introduced later only through an explicit versioned Host Service.
- Archive extraction validates paths, file count, compressed/uncompressed size and rejects traversal/symlink abuse.
- Artifact-level resource limits are centrally configured and conformance-tested.

QuickJS bytecode is **not** a portable artifact. Tsuzuki compiles/caches bytecode locally and keys that cache by artifact hash + QuickJS engine version + relevant Host API version.

### 3.2 Manifest

Example:

```json
{
  "manifestVersion": 1,
  "id": "org.example.mangafire",
  "name": "MangaFire",
  "version": {
    "name": "1.0.0",
    "code": 1
  },
  "minHostApi": 1,
  "entrypoint": "main.js",
  "capabilities": [
    { "id": "catalog.search", "version": 1 },
    { "id": "reading.lookup", "version": 1 },
    { "id": "reading.chapters", "version": 1 },
    { "id": "reading.pages", "version": 1 }
  ],
  "permissions": {
    "network": {
      "origins": [
        "https://mangafire.to",
        "https://*.examplecdn.net"
      ],
      "localNetwork": false
    },
    "browser": {
      "origins": [
        "https://mangafire.to"
      ]
    },
    "storage": {
      "enabled": true
    },
    "secrets": []
  },
  "contentLanguages": ["en"],
  "settings": []
}
```

The repository index, not the artifact manifest, is authoritative for the artifact SHA-256 and repository trust chain.

### 3.3 Script export contract

The entrypoint exports one Provider object:

```js
export default {
  catalog: {
    search,
    discover
  },
  reading: {
    lookup,
    chapters,
    pages
  },
  torrent: {
    search
  }
}
```

Only declared capabilities are callable. During activation Tsuzuki verifies that every declared script capability has the required export shape. Missing exports fail activation and the previous valid version remains active.

Undeclared exports have no authority.

### 3.4 Boundary validation

Provider artifacts and all script-returned DTOs are untrusted input.

Activation validates manifest identity, entrypoint path, capability/export agreement, permission schema and package bounds before any Provider code is callable.

Every invocation then validates its returned schema again before producing a domain result.

Validation is concentrated at these external boundaries; internal Tsuzuki code may trust successfully decoded Provider-domain types.

Provider-supplied labels/descriptions may be displayed only as escaped text. Provider output never injects Compose/UI code, HTML UI, Android Intents or executable callbacks into product surfaces.

## 4. Provider Host API

The script runtime receives one narrow `tsuzuki` host API. It does not receive Android classes, `Context`, raw filesystem access, sockets, JNI, reflection into the app, or access to another Provider's state.

Host modules are versioned independently.

Planned V1 service families:

```text
tsuzuki.http
tsuzuki.dom
tsuzuki.browser
tsuzuki.storage
tsuzuki.secrets
tsuzuki.binary
tsuzuki.crypto
tsuzuki.image
tsuzuki.log
```

Provider product capabilities and Host Service permissions are different axes. A reading Provider may require HTTP and browser permissions; a metadata API Provider may need HTTP only.

## 5. Execution and sandbox model

### 5.1 Android process boundary

SCRIPT Providers execute in `ProviderRuntimeService` with:

```xml
android:isolatedProcess="true"
```

The isolated runtime process must not initialize the normal Tsuzuki application graph, database, network clients, jobs, WebView, account state or telemetry.

The isolated UID has no direct `INTERNET` permission.

All privileged operations cross the Host Service boundary.

### 5.2 V1 runtime lifecycle

V1 uses a **fresh QuickJS VM per Provider invocation**.

The isolated Android service may remain bound/reused by the host, but JavaScript VM state is not persistent between calls.

Reasons:

- this lifecycle is already proven by the spikes;
- memory/script state is deterministically discarded;
- one Provider invocation cannot rely on process-global JS state;
- crash recovery is simple;
- Provider state belongs in the explicit storage service.

Runtime pooling or per-Provider isolated-service instances are future optimizations/hardening options and must be justified by benchmarks before changing this contract.

### 5.3 Execution context

Every invocation is bound host-side to immutable context:

```text
invocationId
providerId
artifact/version
capability + version
permission snapshot
settings/config fingerprint
deadline/budgets
```

The script cannot choose or spoof its Provider identity or permissions.

### 5.4 Resource limits

Every SCRIPT invocation is bounded by:

- wall-clock timeout;
- QuickJS evaluation timeout/interruption;
- QuickJS memory limit;
- stack limit;
- global and per-Provider invocation concurrency;
- Host Service request budgets;
- response-size limits;
- binary-resource quotas.

Cancellation propagates from the caller to QuickJS and broker work.

A runtime crash/death becomes a typed Provider failure; it must not crash the main Tsuzuki process.

## 6. IPC and opaque resources

Binder is an internal transport, not the domain protocol.

The Provider protocol uses small, versioned typed request/response DTOs. Binder adapters serialize those DTOs without exposing Android transport concepts to the domain.

Large payloads must not cross Binder as byte arrays.

The runtime exchanges:

- scalar values;
- bounded strings/JSON;
- small typed lists;
- opaque resource handles.

Large HTML, archives, images, decrypted binaries and torrent payloads stay host-side.

### 6.1 Resource handles

```kotlin
@JvmInline
value class ProviderResourceHandle(val value: String)
```

A handle is bound to:

- Provider identity;
- execution or explicitly promoted lifecycle;
- resource kind;
- byte/operation quotas.

Default resources are invocation-scoped and automatically released when the invocation completes. Explicit release is supported for long pipelines.

A resource can be promoted to a managed Tsuzuki file only through a host-owned operation. Scripts never receive the filesystem path of private app state.

## 7. HTTP Broker

The HTTP Broker owns all SCRIPT network access.

Policy:

- HTTP/HTTPS only by default.
- Every initial request must match a declared allowed origin.
- Every redirect is revalidated.
- Public-network Providers are blocked from loopback/private/link-local destinations after DNS resolution.
- Access to local/private servers requires explicit `localNetwork` permission.
- Provider cookie/session state is namespaced by Provider ID.
- Request time, redirects, body size and concurrency are bounded.
- Sensitive/hop-by-hop headers are controlled by the broker.
- Host errors are typed and sanitized.

Legitimate local-server Providers such as Komga/Kavita/Suwayomi-style sources can request local-network permission; an ordinary public manga Provider cannot use HTTP as a LAN scanner.

## 8. DOM and Browser Broker

### 8.1 DOM service

HTML parsing is a Host Service backed by the app's maintained parser stack.

Providers may create a DOM handle from a bounded HTTP text response/resource and perform selector operations such as:

- select one/all;
- text;
- attribute;
- HTML fragment;
- existence/count.

This covers the common website fetch + DOM parsing pattern without moving very large documents through Binder.

### 8.2 Browser service

WebView stays in the Tsuzuki host process.

Provider browser profiles/cookies/storage are isolated per Provider identity.

The Browser Broker supports bounded operations such as:

- load allowed origin;
- wait for load/selector/condition;
- read text/HTML/attributes;
- read provider-scoped cookies;
- read/write explicitly requested origin storage where required;
- evaluate provider-supplied page-context JavaScript;
- observe a narrowly matched request/URL;
- close session.

Security rules:

- no unrestricted `addJavascriptInterface`;
- file/content access disabled;
- every top-level navigation and subresource origin checked;
- provider origin allowlist enforced on redirects;
- no arbitrary Android object exposure;
- bounded lifetime;
- rendered browser UI/human interaction may be used when a site genuinely requires it.

The Browser Broker is not an automatic CAPTCHA-bypass service.

## 9. Storage, settings and secrets

### 9.1 Storage

SCRIPT Provider storage is key/value and Provider-scoped.

No raw filesystem API is exposed.

Storage has per-Provider quotas and survives runtime disposal.

### 9.2 Declarative settings

Providers describe settings in the manifest rather than creating Android UI.

Initial setting types:

```text
boolean
text
secret
number
select
multi-select
language
mirror/custom-url
account/action
```

Tsuzuki renders the UI consistently.

Settings changes participate in Provider configuration fingerprints so caches/bindings can invalidate safely when behavior changes.

### 9.3 Secrets

Secrets live in a separate local credential store.

A Provider may access only secret keys declared for that same Provider.

A script Provider can never enumerate/read another Provider's credentials or Tsuzuki-wide account tokens.

Secrets are excluded from ordinary sync/export unless a future explicit encrypted-credential feature defines otherwise.

## 10. Binary, archive, crypto and image pipeline

Complex reading sources must not force arbitrary JVM execution.

Binary operations stay host-owned and operate on opaque resource handles.

V1 service families should cover primitives proven by the platform spikes and required by concrete Tsuzuki-native Provider implementations:

- bounded binary fetch;
- ZIP entry listing/extraction/range-oriented archive access;
- common hashes/HMAC/encodings;
- AES modes required by accepted sources;
- ChaCha/other crypto only when backed by a concrete port requirement;
- decompression;
- image decode with dimension/pixel limits;
- crop/compose/tile transforms;
- image format conversion when Reader compatibility requires it.

Rules:

- transforms validate dimensions before full allocation where possible;
- archive expansion is bounded against zip bombs;
- pipelines prefer streaming/host-side processing;
- image/archive bytes do not enter QuickJS;
- each added primitive must have focused conformance tests.

Unknown provider-specific JVM dependencies do **not** justify a generic `loadJar`/native-code escape hatch. They require a JS port, a new reviewed Host Service primitive, or remain unsupported.

Generic WASM is deferred until real coverage data proves it necessary.

## 11. Repository and supply-chain model

### 11.1 Repository enrollment

A Provider repository is a static-hostable signed catalog.

When adding a repository, Tsuzuki stores:

```text
repository URL
repositoryId
pinned signing key/keyId
display metadata
highest accepted sequence
```

First enrollment requires explicit trust of the repository key fingerprint (or an equivalent trusted bootstrap distributed with Tsuzuki). Subsequent index updates must verify against pinned trust.

### 11.2 Signed index

The signature covers the exact raw payload bytes; verification does not depend on JSON canonicalization.

V1 uses:

- ECDSA P-256;
- SHA-256;
- monotonic repository sequence.

Example payload:

```json
{
  "schemaVersion": 1,
  "repositoryId": "org.example.repo",
  "sequence": 42,
  "providers": [
    {
      "providerId": "org.example.mangafire",
      "versionName": "1.3.0",
      "versionCode": 13,
      "artifactUrl": "https://example.org/providers/mangafire-13.tsz",
      "sha256": "...",
      "minHostApi": 1
    }
  ],
  "revokedArtifactSha256": [],
  "nextSigningKey": null
}
```

### 11.3 Verification and activation

Install/update sequence:

```text
fetch signed index
-> verify pinned repository signature
-> reject replay/old sequence
-> check host API compatibility
-> fetch artifact
-> verify signed SHA-256
-> reject revoked/downgraded version
-> validate package/manifest/capabilities
-> store immutable version
-> activation smoke/contract check
-> atomically switch current version
```

The previous version remains available for rollback.

A failed download, verification, unpack or activation never replaces the active version.

### 11.4 Key rotation and revocation

A new repository signing key is accepted only when introduced by an index signed by an already trusted key.

Repositories can revoke artifact hashes. A revoked active artifact is disabled from execution and surfaced to the user; it is not silently executed until a replacement exists.

Downgrades are rejected by default. Explicit manual rollback uses the locally stored previously verified version, not an older untrusted repository response.

### 11.5 Repository transport

Production repository/index/artifact URLs use HTTPS.

Local/dev repositories require an explicit developer-mode exception.

No Tsuzuki backend server is required; GitHub/static hosting is sufficient.

## 12. Built-in and script Providers under one UI

The final product surface is:

```text
Providers
├── Installed
└── Discover

⋮ Repositories
```

Provider details may show:

- enabled state;
- capabilities;
- languages/facets;
- permissions;
- settings/account state;
- repository/origin;
- version/update/rollback state.

Do not expose "APK extension", "plugin", "script runtime" or "integration" as competing user concepts.

Implementation/runtime provenance may appear in diagnostics/advanced details only.

Built-in Providers cannot be uninstalled as artifacts, but may be enabled/disabled/configured when their behavior permits.

## 13. Canonical reading integration

The Provider Platform does not replace canonical reading semantics.

SCRIPT reading Providers adapt into the existing domain seam currently represented by contracts such as `ContentProvider`, `ChapterProbeProvider` and `ContentBinding`.

The new provider-neutral binding identity is conceptually:

```kotlin
data class ProviderBindingRef(
    val providerId: ProviderId,
    val facetId: String?,
    val externalWorkId: String,
)
```

No new domain contract may require a Mihon `Long sourceId`.

Provider output supplies provider observations:

- external work ID/URL;
- title/aliases useful for lookup;
- provider chapter ID;
- raw number/volume/title;
- language;
- dates/groups/edition hints;
- page/delivery candidates.

Tsuzuki then performs existing canonical parsing, evidence reconciliation, safety rules and selection.

A Provider cannot write canonical chapter IDs or claim canonical identity merely because its own IDs match another Provider.

## 14. Reading delivery model

Reading Providers return a validated **delivery plan**, not arbitrary Android objects.

V1 delivery shapes:

```text
PageList
  -> validated HTTP/page resource requests
  -> optional host transform pipeline

Archive/File
  -> host resource / managed HTTP file / managed local file
  -> CBZ/ZIP page extraction

LocalFile
  -> managed Tsuzuki file reference
```

Initial archive support should prioritize CBZ/ZIP because it naturally matches manga pages.

PDF/EPUB/CBR are future delivery-format capabilities and do not block the Provider Platform foundation.

All provider-supplied URLs/resource operations are validated by the Host Services before becoming Reader input.

## 15. Torrent discovery

Torrent discovery is a Provider capability, not a transport implementation.

A `torrent.search@1` Provider returns candidates such as:

```kotlin
data class TorrentCandidate(
    val infoHash: String?,
    val magnetUri: String?,
    val torrentUrl: String?,
    val displayName: String,
    val sizeBytes: Long?,
    val seeders: Int?,
    val peers: Int?,
    val languages: Set<String>,
    val files: List<TorrentCandidateFile>?,
)
```

At least one resolvable torrent identity (infoHash/magnet/torrent URL) is required.

File metadata may be absent at search time. A host-owned metadata resolver can acquire the torrent metadata before chapter mapping/acquisition.

The torrent search Provider does not own Debrid or direct-P2P policy.

## 16. Torrent chapter/file mapping

Manga torrents commonly contain chapter packs. File selection therefore belongs to Tsuzuki.

Flow:

```text
canonical chapter request
-> torrent candidates
-> torrent metadata/files
-> TorrentChapterMapper
-> zero/one/many safe file matches
```

The mapper may use:

- parsed chapter/volume number from path/name;
- canonical requested chapter identity;
- provider/release hints;
- archive/document extension;
- language/edition hints.

Automatic selection is allowed only when the mapping is unambiguous.

Ambiguous packs fail closed to explicit file selection rather than guessing. A confirmed mapping may be remembered scoped to the torrent infoHash + file identity.

Torrent labels are never treated as canonical work identity evidence by themselves.

## 17. Debrid

Debrid is represented by Provider capabilities. Wave 6 defines the contract and execution infrastructure; it does **not** hard-code TorBox, AllDebrid, Real-Debrid or another vendor into the Tsuzuki core. Concrete Debrid services are expected to be implemented and distributed as independent Providers, including community-authored SCRIPT Providers when their API can be expressed through the bounded Host Services.

A Debrid contract needs to cover:

```text
authentication/account state
cache check
add magnet/torrent
list torrent files
select requested file(s)
poll transfer state
resolve final HTTP download
```

The output converges on a validated HTTP file resource.

No Tsuzuki-hosted backend is required where the service supports direct device/user authentication and API use. Credentials/settings remain scoped to the concrete Provider and are never shared across Providers.

## 18. Direct P2P acquisition

Direct BitTorrent is exposed through the versioned `acquisition.p2p@1` Provider capability. Concrete P2P Providers may be distributed separately, including SCRIPT Providers, but the privileged BitTorrent engine remains a Tsuzuki Host Service backed by a maintained libtorrent binding. Provider code never receives JNI/native-library, raw socket or unrestricted filesystem authority.

V1 host behavior:

- obtain metadata;
- select only the file(s) required for the requested chapter;
- assign ignored priority to unrelated pack files;
- download the selected file into Tsuzuki-managed cache/storage;
- expose a managed local-file resource to the Reader;
- stop the reading acquisition session after required data is complete;
- do not run an always-on torrent daemon;
- do not seed indefinitely by default.

The loopback spike proved selective file download with jlibtorrent 2.0.12.9 on Android API 35.

Production packaging must include only supported Android ABIs and preserve current 16 KiB-page compatibility.

Background transfers must use an Android lifecycle appropriate to user-initiated transfers. Long-running jobs must not assume an unlimited background `dataSync` foreground service.

Direct P2P exposes the user's network address to peers. Enabling/using direct P2P must communicate that difference from Debrid clearly.

## 19. Acquisition policy

Torrent discovery and acquisition route are separate.

V1 supports:

```text
DEBRID_ONLY
P2P_ONLY
DEBRID_THEN_P2P
```

If a Debrid Provider is enabled and the user has not chosen otherwise, the default may be `DEBRID_THEN_P2P`; without a usable Debrid Provider the effective path is direct P2P.

Failover to P2P must respect the user's direct-P2P preference because it changes privacy/network behavior.

Both routes converge to the same Reader-facing resource abstraction:

```text
Debrid -> HttpFile
P2P    -> LocalFile
```

## 20. Diagnostics

Provider Platform diagnostics integrate with Tsuzuki Diagnostics v2.

Record only bounded/sanitized facts such as:

- invocation ID;
- provider ID;
- capability;
- runtime/broker stage;
- result category;
- timeout/budget category;
- duration;
- bytes/count buckets;
- resource-handle lifecycle;
- repository/update/rollback outcome.

Do not persist raw titles, queries, private-server URLs, cookies, tokens, page URLs or provider secrets in normal diagnostics.

Provider console logging is bounded and sanitized before joining exports.

## 21. Threat model and security boundaries

The platform explicitly considers:

### Malicious/buggy Provider JavaScript

Mitigations:

- QuickJS execution limits;
- fresh VM per invocation;
- isolated Android UID/process;
- no direct network/filesystem/app graph;
- capability + permission checks on every host operation;
- bounded host resources.

### Compromised repository/artifact hosting

Mitigations:

- pinned repository identity/key;
- signed raw index;
- monotonic sequence;
- artifact SHA-256;
- revocation;
- downgrade protection;
- atomic activation/rollback.

### Malicious remote website

Mitigations:

- origin policy;
- redirect revalidation;
- private-network blocking;
- provider-scoped browser profiles;
- no unrestricted JavaScript-to-native interface;
- file/content access disabled.

### Resource exhaustion

Mitigations:

- execution/memory/stack timeouts;
- network/redirect/body limits;
- binary/archive/image quotas;
- image dimension/pixel limits;
- bounded concurrency;
- cleanup on invocation end/process death.

### Credential isolation

A Provider receives only its own declared secrets. Built-in Provider credentials are not readable by script Providers.

Residual risk: a Provider can send its own accessible data/secrets to network origins that the user trusted for that Provider. Permission review and repository trust remain part of the security model.

## 22. Legacy Mihon/Keiyoushi retirement

### 22.1 No permanent APK compatibility runtime

The final platform does not execute Keiyoushi/Mihon APKs.

`index.pb` is therefore **not** treated as a native executable Tsuzuki Provider repository. Its entries point to APK artifacts and cannot safely become script Providers by renaming them.

Keiyoushi may remain an external implementation reference when useful, but **porting or compatibility with the Keiyoushi repository is not a Provider Platform goal**. Tsuzuki will validate the ecosystem by creating its own Providers from scratch in separate Provider repositories.

### 22.2 Tsuzuki-native Provider development

Provider SDK/tooling should optimize for authoring a new Provider directly against Tsuzuki contracts:

- project/package templates;
- manifest and capability validation;
- local/conformance fixtures;
- shared JavaScript helpers for generic HTTP/DOM/browser/archive patterns;
- repository signing/publishing tooling;
- actionable diagnostics for missing Host Service primitives.

Host Services expand only when a concrete Tsuzuki-native Provider demonstrates a reusable requirement. There is no source-to-source migration analyzer and no promise of extension-by-extension parity with Mihon/Keiyoushi.

### 22.3 Transition

Because Tsuzuki currently has one user, no public compatibility migration is required.

During development the old Mihon backend may coexist temporarily so reading remains testable. It is not a supported steady state.

After representative Tsuzuki-native Providers are physically accepted:

- remove Mihon APK install/update paths;
- remove dynamic extension Dex/APK loading;
- remove extension-store UI;
- remove `REQUEST_INSTALL_PACKAGES` and other APK/package-management permissions no longer needed;
- remove Mihon-specific Provider-domain leakage such as `mihonSourceIds`;
- remove the temporary `app.cash.quickjs` compatibility shim if no remaining code requires it;
- migrate UI terminology to Providers/Repositories only.

Canonical works, progress and library state must survive this removal. Old source bindings may be invalidated/re-resolved without redefining canonical work identity.

## 23. Built-in Integration migration

The existing nine active Integrations become BUILTIN Provider descriptors behind the same registry.

This is primarily a registry/UI convergence, not a rewrite of their provider-specific clients.

Existing capability/policy gates and field-level provenance remain authoritative.

The "no new metadata integrations for now" decision remains intact; Provider Platform work does not imply adding more editorial services.

## 24. UI model

Primary surface:

```text
Settings
└── Providers
    ├── Installed
    └── Discover
        ⋮ Repositories
```

Examples in one list:

```text
MyAnimeList
Kitsu
MangaUpdates
MangaFire
Torrent indexer X
TorBox
BitTorrent
```

They remain visually one concept while exposing different capabilities/settings.

A Provider detail page may group capabilities for readability, but must not recreate separate product silos named Integrations/Add-ons/Extensions/Debrid Plugins.

## 25. Performance model

V1 performance rules:

- compile/cache script bytecode locally by artifact+engine version;
- fresh VM per invocation;
- bounded global/per-Provider concurrency;
- cancellation propagates when Search/Detail/Reader request becomes obsolete;
- Provider storage/cookies live outside the VM;
- HTTP/browser sessions are host-managed;
- canonical discovery budgets remain authoritative;
- repeated broker work may use normal Tsuzuki cache/single-flight infrastructure where semantics allow.

Runtime process pooling/per-Provider process isolation is deliberately not part of V1. Revisit only with measurements showing a meaningful latency or security benefit.

## 26. Failure model

Provider failures are typed at the platform boundary, for example:

```text
UNAVAILABLE
PERMISSION_DENIED
HOST_API_UNSUPPORTED
SCRIPT_ERROR
TIMEOUT
RUNTIME_DIED
NETWORK_POLICY
NETWORK_ERROR
BROWSER_ERROR
RESOURCE_LIMIT
MALFORMED_RESULT
AUTH_REQUIRED
ACQUISITION_FAILED
```

Raw provider exceptions do not escape into canonical-domain decisions.

Provider failures may reduce available candidates; they do not invent fallback identity.

## 27. Implementation waves

### Wave 1 — Provider core + trusted repository

- Provider IDs/descriptors/capability refs/permissions.
- ProviderRegistry facade.
- signed repository enrollment/index verification;
- artifact verification/store/rollback;
- manifest/package parser;
- tests for replay, downgrade, revocation, rotation, corruption.

### Wave 2 — production script runtime + Host API

- QuickJS-kt as the single QuickJS engine;
- isolated process runtime;
- typed IPC;
- HTTP/DOM/browser/storage/secrets/logging;
- resource-handle manager;
- binary/archive/crypto/image primitives needed by first ports;
- malicious/timeout/resource conformance fixtures.

### Wave 3 — reading vertical slice

Port representative sources covering:

1. API/JSON;
2. HTML/DOM;
3. Browser-required;
4. complex archive/crypto/image pipeline.

Adapt them through current canonical reading contracts and physically verify chapter discovery + Reader.

### Wave 4 — Providers UI + repository UX

- Installed/Discover;
- repository management/trust fingerprint;
- install/update/rollback;
- permissions/settings presentation;
- provider enablement/facets/languages.

### Wave 5 — built-in Provider convergence

- register current Integrations as BUILTIN Providers;
- move user-facing Integrations UI into Providers;
- preserve existing capability policy/provenance/account behavior.

### Wave 6 — torrent/debrid/P2P

- `torrent.search@1`, `debrid.resolve@1` and `acquisition.p2p@1` contracts executable by independently distributed Providers;
- torrent metadata/file mapper;
- conformance/reference Provider fixtures rather than a vendor-specific Debrid integration in core;
- direct P2P Host Service implementation with privileged native transport remaining host-owned;
- acquisition policy;
- CBZ/ZIP Reader convergence;
- user-visible privacy/storage/lifecycle controls.

### Wave 7 — Tsuzuki-native Provider ecosystem tooling

- Provider SDK, templates and shared JavaScript modules;
- conformance/test fixtures for independently developed Providers;
- repository signing/publishing developer tooling;
- create representative first-party/reference Providers in separate Provider repositories from scratch;
- expand Host Services only from concrete Provider requirements;
- no Keiyoushi portability analyzer or extension-by-extension porting program.

### Wave 8 — Mihon APK removal

After physical parity is sufficient:

- remove APK extension manager/installer/loader path;
- delete obsolete permissions/components/UI;
- delete Mihon-specific domain leakage;
- remove temporary compatibility shim;
- run full canonical/Reader regression + clean-install migration smoke.

## 28. Conformance and acceptance

The Provider Platform is not complete until all of the following are proven.

### Runtime/security

- SCRIPT execution uses a distinct isolated UID/process.
- The script process has no direct Internet permission.
- Infinite loop and memory/resource abuse are bounded.
- Provider runtime death does not crash Tsuzuki.
- One Provider cannot read another Provider's storage/secrets.
- Disallowed public/local origins and redirects fail closed.
- Browser cross-origin/subresource policy is enforced.
- Large images/archives do not cross Binder as byte arrays.

### Supply chain

- tampered index rejected;
- wrong repository key rejected;
- replayed sequence rejected;
- artifact hash mismatch rejected;
- downgrade rejected;
- revoked artifact blocked;
- signed key rotation accepted;
- failed update preserves current version;
- previous verified version can roll back atomically.

### Reading

- API/JSON Provider reaches canonical Detail/Reader.
- HTML/DOM Provider reaches Reader.
- Browser-required Provider reaches Reader without Android access in script.
- complex archive/crypto/image Provider reaches Reader with bytes staying host-side.
- canonical chapter/source regression suite remains green.
- source-independent progress survives Provider switching.

### Torrent/debrid

- one torrent candidate can resolve through a conforming Debrid Provider to HTTP without vendor-specific core code.
- the same candidate/file can resolve through a conforming P2P Provider to a local resource while native transport remains host-owned.
- unrelated pack files remain unselected for selected-file acquisition.
- ambiguous chapter/file mapping fails closed.
- CBZ/ZIP output opens through the same Reader abstraction.
- direct-P2P privacy behavior requires/obeys the acquisition preference.

### Product migration

- native Provider install/update requires no Android APK installer flow.
- user-facing surfaces use Provider terminology only.
- current built-in Integrations remain functional under the unified registry.
- after final Mihon removal, no downloaded Dex/JAR/APK/native provider execution remains.
- obsolete package-install permissions are removed.
- clean-install physical smoke passes.

### Quality gates

- focused unit tests for every capability/host contract.
- Android instrumentation for isolated process, browser and native/binary/torrent paths.
- malicious/malformed Provider fixtures.
- full CI green.
- physical APK smoke for representative Providers and Reader paths.
- no local Gradle as project validation policy requires.

## 29. Alternatives considered

### Keep Mihon APK extensions permanently

Rejected as the destination architecture. It preserves Android package/dynamic-code coupling, broad JVM authority and the exact UX/product abstraction Tsuzuki wants to remove.

### CloudStream-style downloaded Dex/JVM plugins

Better installation UX than Mihon but retains dynamically loaded JVM/Dex code. Useful as an ergonomics/provider-API reference, not as the runtime security model.

### Traditional remote Stremio add-ons as the primary Provider model

Rejected as the universal foundation because it creates mandatory hosting/availability/rate-limit cost for ordinary website providers and loses the local-client IP/session advantages useful for manga scraping. Remote services may still be represented by a Provider when a specific capability naturally belongs remotely.

### QuickJS with direct unrestricted fetch/filesystem

Rejected because an interpreter alone is not a sufficient security boundary. The chosen model keeps authority in Host Services.

### One giant Provider interface

Rejected. Capabilities remain independently versioned so Providers implement only what they actually support.

### Generic JVM/native escape hatch for difficult Providers

Rejected. Difficult Provider requirements are solved through ordinary Provider code or reviewed Host Services, not arbitrary classloading.

## 30. Non-goals for V1

- Automatic APK/Kotlin-to-JavaScript conversion.
- Keiyoushi/Mihon extension-by-extension porting or compatibility parity.
- Generic WebAssembly execution.
- Arbitrary Provider native libraries.
- A public hosted Tsuzuki marketplace/backend.
- Automatic CAPTCHA solving.
- Indefinite direct-torrent seeding.
- Progressive page streaming from incomplete torrent archive files.
- PDF/EPUB/CBR support as a launch gate.
- Guaranteeing Play Store acceptance of every future provider/torrent behavior.
- Redesigning canonical work/chapter identity.
- Adding new editorial/metadata integrations merely because the Provider registry can host them.

## 31. Spec freeze

This document freezes the V1 architecture of the Provider Platform.

Implementation may tune constants, UI composition and internal class names without reopening the design.

A spec amendment is required for changes that would:

- reintroduce downloaded Dex/JAR/APK/native execution;
- give SCRIPT Providers direct Android/network/filesystem authority;
- collapse canonical work/chapter identity into Provider identity;
- remove repository signature/hash/replay guarantees;
- introduce a new persistent script-state model;
- make torrent discovery own acquisition policy;
- bypass explicit direct-P2P privacy preference;
- make Provider capabilities mutually exclusive types.

The completed spike branches remain empirical references. Production code should intentionally port/cherry-pick proven pieces into the implementation waves rather than merging experimental APIs wholesale.
