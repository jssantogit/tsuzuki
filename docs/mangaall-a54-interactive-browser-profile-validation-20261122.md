# MangaALL — same-profile MangaFire verification on Galaxy A54 (test only)

Status: **debug instrumentation, not production functionality and not a confirmed physical test** (2026-11-22).

## What differs from generic WebView

The Host creates the MangaALL WebView profile with SHA-256 of `app.tsuzuki.mangaall` and the name `tsuzuki-provider-<24 hex>`. The generic Tsuzuki WebView does not select that profile. This experiment extracts that unchanged profile derivation into `ProviderBrowserProfiles` and uses it in both the production Host and a **debug-only, non-exported** interactive Activity. Release APKs do not contain the Activity.

The browser is opened at `https://mangafire.to/` and the user may complete normal site verification on-screen. The test does **not** automate CAPTCHAs, spoof the browser, rotate IPs or transfer cookies. After the user presses `Validar sessão`, the Activity closes. The on-device instrumentation then calls the original signed code10 real three-source lookup for **Oyasumi Punpun** and **Koe no Katachi**, obtains the actual MangaFire API URL in **ephemeral memory** from the Host's OkHttp interceptor, and asks the real `AndroidProviderBrowserHostService.readText` to access that same signed endpoint using the same WebView profile.

Only a returned JSON object with a real `items` array and no `error` establishes `SIGNED_API_JSON_VALID` on each work. HTML, an open homepage, cookies, generic Cloudflare challenges, HTTP errors or two-source MangaDex/MangaDot responses cannot pass. The separate metric `mangafireCandidates` reports how many source-HID records the actual signed code10 returned. The test never binds a canonical edition or claims Reader pixels, and does not call it 30-title acceptance.

**Important limitation:** the debug Android package is `app.tsuzuki.dev` and has its *own Android application sandbox*. Although the Host and test UI share an identical profile **within that debug app**, their cookie state cannot be imported into the installed release `app.tsuzuki`. This trial proves or disproves the feasibility of the mechanism, not access persistence in the release build.

## Prepare exact debug artifacts

Run [MangaALL A54 browser-profile APK workflow](../.github/workflows/mangaall-a54-browser-profile-apks.yml) on the reviewed branch. GitHub Actions only compiles. It checks the original P-256 signed sequence36 code10 archive and stages that exact `.tsz` into `androidTest/assets`, not into the application or shipping provider. The artifact contains a universal `app.tsuzuki.dev` APK plus a corresponding test APK, retained for three days. Nothing is deployed to a real phone from CI.

## Manual physical session (only once APKs exist)

On a computer with Android SDK `adb`, connect your Galaxy A54 with USB debugging authorized. Extract the GitHub Actions artifact, identify the debug app APK and AndroidTest APK and run:

```bash
MANGAALL_A54_SESSION_ACK=I_ACCEPT_MANGAFIRE_A54_INTERACTIVE_TEST \
MANGAALL_A54_APP_APK=/path/to/app-universal-debug.apk \
MANGAALL_A54_TEST_APK=/path/to/app-debug-androidTest.apk \
bash .github/scripts/run_mangaall_a54_browser_profile.sh
```

The shell script refuses emulator/non-A54/multiple devices, refuses missing opt-in, installs only `app.tsuzuki.dev` and `app.tsuzuki.dev.test` side-by-side, never uninstalls/clears user data, and launches the single interactive instrumented test. On the phone, complete the site's normal challenge and touch **Validar sessão**. The script emits only closed, sanitized per-work verdicts under `.github/results/mangaall-a54-profile/verdict.txt`; raw instrumentation output is immediately deleted. No full signed URLs, cookies, page bodies or private requests are kept.

## Acceptance

- **Pass session mechanism:** both real signed API requests return structurally valid JSON in the same Browser Host profile after the interactive verification. Report `BROWSER_SIGNED_API_ACCEPTED|signedJson=2`.
- **Inconclusive:** user did not complete verification, API did not produce the signed URL, Cloudflare still challenged, or browser returned invalid/blocked content. A CI green / successful APK build does **not** count.
- **Further gates (not tested here):** valid observed MangaFire HID, complete chapter pagination/cursors, canonical collector/SQLDelight, actual Reader pixels and the existing five over-64-KiB signed code10 results. Maintain all three internal sources together and do not turn replay into new live source proof.

Related: [PR #271](https://github.com/jssantogit/tsuzuki/pull/271) remains separate and draft (Node/Chromium CI-only solver; unresolved generic Cloudflare). [Host issue #207](https://github.com/jssantogit/tsuzuki/issues/207).
