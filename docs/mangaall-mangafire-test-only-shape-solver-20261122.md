# MangaALL — Test-only MangaFire shape CAPTCHA experiment (2026-11-22)

**State: experimental.** This is neither a production app feature nor proof that MangaFire works on the GitHub runner.

The only modified code is in the Host CI harness. The signed MangaALL Provider 0.5.5/code10, P-256 index sequence 36, its JS modules, Android Browser Host/Binder and the application remain unchanged.

## How it works

- The existing Node HTTP bridge sends signed-code10 upstream GET requests. Only a **MangaFire GET /api/** response with HTTP 403 and JSON `{"error":"captcha_required"}` may invoke a disposable Playwright Chromium session.
- The browser loads a **pinned Apache-2.0 Keiyoushi MangaFire solver.html** at the original origin, and uses OpenCV.js to attempt the shape-selection CAPTCHA. It is adapted in memory from the upstream 10 rounds x 10 concurrent tries to **2 rounds x 2 tries** (maximum four), once per shard.
- A server-issued `waf_pass` cookie is kept inside the same ephemeral browser context. The originally signed API URL must then return **HTTP 200 JSON**. A solver success signal, a clearance cookie alone, a browser page load or a response from another source is *not* proof.
- Subsequent MangaFire API GETs reuse the same disposable browser context. Cookies, tokens, HTML challenges, images, and browser state are never exported into CI artifacts.
- **`cf-mitigated: challenge` is classified separately**. It may initiate one bounded, passive Chromium visit to the site's normal home page, letting standard browser-side Cloudflare JavaScript run; no visual CAPTCHA solver, challenge clicking, fingerprint spoofing or proxy changes are attempted. Only a `cf_clearance` cookie in that disposable context followed by signed-API JSON 200 can validate the session. An unresolved challenge remains inconclusive. Other 403s, invalid responses, timeouts and unsupported origins/methods remain blocked. No IP rotation, stealth or publication.
- This is only evidence of source HTTP availability. It does not grant canonical work identity, editorial edition, complete chapters, SQLite acceptance or Reader CDN pixels.

## Execution

- Offline contract: `node .github/scripts/test_mangaall_shape_test_core.mjs`.
- One-shot live probe: `.github/workflows/mangaall-mangafire-shape-experiment.yml`, with **Oyasumi Punpun** and **Koe no Katachi** in one real, three-source signed-code10 probe. A manual dispatch must include the exact acknowledgement `I_ACCEPT_TEST_ONLY_AUTO_SHAPE`, or an explicitly scoped commit request on `probe/mangaall-mangafire-shape-*`. Browser state is discarded at job completion.
- The 30-work smoke retains its default no-solver mode; an experimental solver/browser-session option, if enabled, must be explicitly operator selected and must not falsely promote 2/3 sources to tri-source PASS.

## Attribution

Keiyoushi `extensions-source`, Apache License 2.0: <https://github.com/keiyoushi/extensions-source/blob/5d54f0d02e2a7dbdda08e823de444f7527cc883c/LICENSE>. Pinned solver <https://github.com/keiyoushi/extensions-source/blob/5d54f0d02e2a7dbdda08e823de444f7527cc883c/src/all/mangafire/assets/solver.html> at commit `5d54f0d02e2a7dbdda08e823de444f7527cc883c` and blob SHA `f975cc6b3951f1793e932a11f0422f2573ef3f26`. Downloaded and Git-hash verified at test runtime, not bundled with the app.

## Acceptance criteria

1. Offline tests pass with exact Cloudflare-vs-JSON distinction, bounded solve attempts, opt-in and no session leakage.
2. The two-work experiment yields real sanitized source evidence and explicitly reports the shape attempt outcome.
3. Only an observed successful session should justify enabling the full 30-work experiment. An unresolved Cloudflare challenge, the independent >64 KiB Host result limit and real Reader pixels remain separate blockers.


## CI: correção de transporte nativo do navegador após o PASS físico A54 (2026-11-22)

O teste físico A54 de [PR #272](https://github.com/jssantogit/tsuzuki/pull/272) alcançou **2/2 respostas JSON válidas** da API assinada MangaFire depois de uma verificação interativa no WebView do próprio Browser Host. Essa é prova **limitada ao ambiente debug do A54**, não um passe da CI.

Auditoria da [PR #271](https://github.com/jssantogit/tsuzuki/pull/271) identificou uma diferença de implementação ainda não testada: o Chromium era usado para adquirir a sessão, mas a consulta assinada era enviada através de `BrowserContext.request.get`, que [compartilha o cookie jar mas é a API HTTP do Playwright](https://playwright.dev/docs/api/class-apirequestcontext), não uma solicitação feita dentro da página. Cloudflare pode diferenciar essas solicitações. Essa **hipótese técnica não é uma causa confirmada** dos 403 anteriores.

Mudança experimental, ainda estritamente test-only:

- Manter a sessão no mesmo Chromium e fazer o GET assinado na origem por `page.evaluate(() => fetch(...))`, com `credentials: same-origin`, redirecionamento bloqueado, timeout de 12s e limite de 192 KiB.
- Não copiar cookies, User-Agent, tokens, URLs assinadas nem HTML de desafios para logs/artefatos. Não alterar programa, plugin/provider assinado, release ou permissões.
- Após uma sessão verificada no Chromium, restringir a rota a GET `https://mangafire.to/api/*`. Em browser inicial, permitir somente o JS oficial do challenge. O solver de figuras permanece isolado e só é acionado se o site retornar `captcha_required` como JSON — **não** quando Cloudflare retornar `cf-mitigated: challenge`.
- A aceitação exige HTTP 200, JSON objeto com campo `items` array e sem `error`, para o **mesmo URL produzido pelo code10 original**. Cookie ou tela carregada sozinho não vale.
- Novo workflow `mangaall-mangafire-browser-transport-contract.yml` executa contrato offline por push, sem tráfego externo, e cobre 403 challenge, controle de URL/método, resposta grande e JSON 200 sem `items`. A execução live segue explicitamente opt-in e limitada a duas obras pela configuração original.

**Sem promessas de superação automática de desafio interativo:** se o runner hospedado pelo GitHub permanecer bloqueado pela infraestrutura anti-bot, o resultado correto continua sendo `INCONCLUSIVE`. O PASS Android não pode ser transplantado via cookies entre ambientes.

## Live observations (2026-11-22)

- [Run #45085281359](https://github.com/jssantogit/tsuzuki/actions/runs/45085281359): CI SUCCESS, offline shape suite SUCCESS; 2 inconclusive source cases; 6 MangaDex/5 MangaDot HTTP200, MangaFire 1 HTTP403 `cf-mitigated: challenge`. Shape solver correctly not invoked (0 shape attempts). Browser challenge not tried.
- [Run #45085541343](https://github.com/jssantogit/tsuzuki/actions/runs/45085541343): CI SUCCESS, 2 inconclusive cases; one native headless Chromium JavaScript challenge probe, no `cf_clearance` (`CLOUDFLARE_BROWSER_UNRESOLVED`). This result does not show that the shape CAPTCHA solver was exercised.
- Follow-up compares headed Chromium on Xvfb, permits origin-scoped Cloudflare challenge POST, and requires parsed non-error JSON from the original signed API. If unresolved, stop CI retries and validate access on physical Android. Neither attempt validates Reader pixels or Host's 64-KiB bound.

- [Run #45085762359](https://github.com/jssantogit/tsuzuki/actions/runs/45085762359): CI SUCCESS and offline contract PASS. Headed Chromium/Xvfb attempted one native browser verification. Same 12 observed original upstream GETs: MangaDex 6×200, MangaDot 5×200, MangaFire 1×403. The browser session reached a clearance-cookie candidate but its retried signed MangaFire API request was still challenged (`CLOUDFLARE_CHALLENGE`); no authenticated API JSON200 recovered; both works `INCONCLUSIVE`. **Do not extend CI retries or treat this as CAPTCHA/tri-source PASS.** Follow-up needs an ordinary physical Android browser/WebView with a legitimate interactive user verification and same-profile provider session proof, separately from CI and Host 64-KiB limit.
