# MangaALL — same-profile MangaFire verification on Galaxy A54 (test only)

Status: **physical Galaxy A54 debug-only verification succeeded for 2/2 API checks; not production functionality or 30-title acceptance** (2026-11-22, user-submitted local runner verdict).

## Physical Galaxy A54 verification evidence — 2026-11-22

The operator ran the Termux + localhost wireless ADB instrumentation on a physical Galaxy A54 and provided the following <b>sanitized local verdict</b> (user-reported, not a GitHub Actions live run):

```text
MANGAALL_A54_PROFILE|outcome=BROWSER_SIGNED_API_ACCEPTED|signedJson=2|works=2|providerCandidateWorks=2|canonical=NOT_TESTED|chapters=NOT_TESTED|readerPixels=NOT_TESTED
MANGAALL_A54_PROFILE|phase=STARTED
MANGAALL_A54_PROFILE|phase=ACTIVITY_REQUESTED
MANGAALL_A54_PROFILE|phase=OPERATOR_CONFIRMED
MANGAALL_A54_PROFILE|work=punpun|outcome=SIGNED_API_JSON_VALID|mangafireCandidates=1|browserApi=VALID_JSON
MANGAALL_A54_PROFILE|work=koe|outcome=SIGNED_API_JSON_VALID|mangafireCandidates=1|browserApi=VALID_JSON
```

Interpretation: the original signed P-256 MangaALL code10 lookup surfaced one MangaFire candidate for each of **Oyasumi Punpun** and **Koe no Katachi**, and the same-profile Browser Host returned valid JSON containing an `items` array for both lookup-generated API URLs after interactive on-device verification. This establishes the explicit **A54 debug-only signed-API gate** for **2/2 works**. It does **not** imply that the API behaves the same way in CI, in the existing release-app sandbox, after a fresh session/cold restart, or without human verification. It is not evidence of canonical work binding, chapter-list pagination, page acquisition, or decoded Reader pixels, and does not establish the 30-work tri-source smoke.

[The matching compiled debug/test APK CI #45090863034](https://github.com/jssantogit/tsuzuki/actions/runs/45090863034) completed successfully. GitHub Actions compiled and published APKs; it did **not** run the live A54 session. The session report above was submitted by the operator.

Next technical gates: prove the profile behavior survives reuse/restart as appropriate; exercise canonical work matching and paginated chapter retrieval across all three sources; then expand the same on-device combined-source smoke to the intended 30 works. The >64KiB transport/pagination limitation remains a separate unresolved acceptance dependency.

## What differs from generic WebView

The Host creates the MangaALL WebView profile with SHA-256 of `app.tsuzuki.mangaall` and the name `tsuzuki-provider-<24 hex>`. The generic Tsuzuki WebView does not select that profile. This experiment extracts that unchanged profile derivation into `ProviderBrowserProfiles` and uses it in both the production Host and a **debug-only, non-exported** interactive Activity. Release APKs do not contain the Activity.

The browser is opened at `https://mangafire.to/` and the user may complete normal site verification on-screen. The test does **not** automate CAPTCHAs, spoof the browser, rotate IPs or transfer cookies. After the user presses `Validar sessão`, the Activity closes. The on-device instrumentation then calls the original signed code10 real three-source lookup for **Oyasumi Punpun** and **Koe no Katachi**, obtains the actual MangaFire API URL in **ephemeral memory** from the Host's OkHttp interceptor, and asks the real `AndroidProviderBrowserHostService.readText` to access that same signed endpoint using the same WebView profile.

Only a returned JSON object with a real `items` array and no `error` establishes `SIGNED_API_JSON_VALID` on each work. HTML, an open homepage, cookies, generic Cloudflare challenges, HTTP errors or two-source MangaDex/MangaDot responses cannot pass. The separate metric `mangafireCandidates` reports how many source-HID records the actual signed code10 returned. The test never binds a canonical edition or claims Reader pixels, and does not call it 30-title acceptance.

**Important limitation:** the debug Android package is `app.tsuzuki.dev` and has its *own Android application sandbox*. Although the Host and test UI share an identical profile **within that debug app**, their cookie state cannot be imported into the installed release `app.tsuzuki`. This trial proves or disproves the feasibility of the mechanism, not access persistence in the release build.

## Prepare exact debug artifacts

Run [MangaALL A54 browser-profile APK workflow](../.github/workflows/mangaall-a54-browser-profile-apks.yml) on the reviewed branch. GitHub Actions only compiles. It checks the original P-256 signed sequence36 code10 archive and stages that exact `.tsz` into `androidTest/assets`, not into the application or shipping provider. The artifact contains a universal `app.tsuzuki.dev` APK plus a corresponding test APK, retained for three days. Nothing is deployed to a real phone from CI.

## Sem computador: Termux + Depuração sem fio no próprio A54

Este caminho dispensa PC, root, Shizuku, Gradle e Git no telefone. A interface de verificação continua sendo o APK **debug isolado** e a sessão de navegação com o mesmo perfil Browser Host do debug; **não** é uma alteração da release.

1. Instale o Termux pela distribuição oficial [F-Droid](https://f-droid.org/en/packages/com.termux/) ou [GitHub](https://github.com/termux/termux-app/releases). Abra o Termux e execute `apt update && apt full-upgrade && apt install android-tools unzip coreutils`, depois `termux-setup-storage` e conceda permissão de arquivos. Se o `curl` estiver quebrado por incompatibilidade de bibliotecas (`CANNOT LINK EXECUTABLE`), **não use `pkg` para repará-lo**: o próprio Termux recomenda `apt update && apt full-upgrade`, pois `pkg` depende de `curl`.
2. No navegador do A54, autenticado no GitHub, baixe o artefato **mangaall-a54-debug-profile-test-apks** da [última CI verde deste workflow](https://github.com/jssantogit/tsuzuki/actions/workflows/mangaall-a54-browser-profile-apks.yml). Deixe o **ZIP** em Downloads, sem extrair manualmente. Artefatos expiram em 3 dias; uma nova execução compile-only pode gerá-los novamente.
3. Com o aparelho conectado a Wi-Fi, abra **Configurações → Opções do desenvolvedor → Depuração sem fio**. Se o menu não existir, toque 7 vezes em **Número de compilação** em **Sobre o telefone → Informações do software**. Mantenha Configurações e Termux em tela dividida durante o pareamento.
4. No Termux, baixe o script de execução (texto, branch experimental) e rode:

```bash
curl -fsSL 'https://raw.githubusercontent.com/jssantogit/tsuzuki/probe/mangaall-a54-browser-profile-validation-20261122/.github/scripts/run_mangaall_a54_termux.sh' -o ~/mangaall-a54-termux.sh
bash ~/mangaall-a54-termux.sh
```

Se o `curl` ainda falhar, abra pelo navegador do celular o [arquivo de execução Termux](https://github.com/jssantogit/tsuzuki/blob/probe/mangaall-a54-browser-profile-validation-20261122/.github/scripts/run_mangaall_a54_termux.sh), toque em **Raw / Download raw file** e salve como `run_mangaall_a54_termux.sh` na pasta Downloads. Execute diretamente do Termux com `bash ~/storage/downloads/run_mangaall_a54_termux.sh`. A versão atual do executor **não utiliza `curl` nem `wget` durante o teste**: o runner A54 revisado foi incorporado como texto dentro dele; o único ZIP necessário é o artefato da CI já baixado. Em caso de nome diferente atribuído pelo navegador, use `ls ~/storage/downloads` e passe o nome exato ao `bash`.

O script pergunta se já houve pareamento; quando necessário, informe a **porta de pareamento**, digite o código apenas no prompt local do `adb pair`, depois informe a **porta de conexão**, que é diferente e aparece na tela principal de Depuração sem fio. Usa sempre `127.0.0.1:<porta>`, ADB no próprio aparelho. Se o ZIP tiver sido renomeado pelo navegador, execute `bash ~/mangaall-a54-termux.sh ~/storage/downloads/NOME-REAL.zip`.

O runner de Termux verifica que está em Termux, valida a conexão local `adb -s 127.0.0.1:PORT`, rejeita emulador/outro modelo, extrai temporariamente os dois APKs do ZIP e executa o **mesmo script A54** já documentado. Ao abrir o MangaFire, conclua a verificação normal e toque **Validar sessão**. O resultado fechado é armazenado no diretório privado do Termux: `~/.github/results/mangaall-a54-profile/verdict.txt`.

**Segurança:** não compartilhe código de pareamento, cookies, capturas de challenge ou URLs assinadas. O script não precisa e não recebe essas informações; não desinstala nem limpa o Tsuzuki oficial. A sessão exige interação humana e pode falhar mesmo com o site visualmente aberto — isso é inconclusivo, não é um PASS. Após o teste, você pode desligar Depuração sem fio.

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
