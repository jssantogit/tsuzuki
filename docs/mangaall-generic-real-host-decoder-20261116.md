# MangaAll — CI genérica de dados reais até o decoder Kotlin do Host (2026-11-16)

**Estado atual:** Provider source real → Host V1 decoder real; a PR #210 acrescenta consulta **separada** a catálogo independente com Kotlin de produção em emulador Android opt-in. Ainda não há integração das identidades com reconciliação canônica, SQLite, escolha de fonte no Reader ou pixels. [Issue #207](https://github.com/jssantogit/tsuzuki/issues/207) acompanha o objetivo end-to-end.

## Como executar para obras arbitrárias

1. Abra [Actions → MangaAll real-source Host decoder](https://github.com/jssantogit/tsuzuki/actions/workflows/mangaall-real-host-decoder.yml) e selecione **Run workflow** na versão `main`.
2. Informe **1–30 títulos de obras reais**, uma por linha, com prefixo de formato opcional como `manga :: Título`, `manhwa :: Título` ou `web_novel :: Título`. A seleção é entrada do operador em tempo de execução; não há `if (title == ...)`, HIDs pré-escolhidos ou concorrentes fabricados. As três shards repartem a lista automaticamente; shards sem obras passam com resultado vazio explícito.
3. Cada shard reconstrói o pacote publicado MangaAll 0.5.3/code8 e verifica a assinatura do índice root, a sequência 34, o checksum do .tsz e a equivalência exata com o JS de origem assinado. Executa as consultas HTTP públicas reais sob limites de tempo/cursor, sem cookies, proxy, evasão de challenge ou bytes de imagem.
4. O Provider exporta somente os envelopes normalizados (lookup/capítulos/cursors/campos editoriais); o teste JVM importa esses bytes, valida hash de lote/caso e passa as respostas originais pelo `ScriptProviderReadingGateway` real. O relatório `MANGAALL_REAL_HOST_DECODER` é emitido por obra. Artefatos com dados públicos sanitizados expiram após 3 dias.

**Status por obra do decoder:**

- `INCONCLUSIVE_UPSTREAM`: falha retornada/lançada no Provider; um HTTP 403/challenge nesse runner não significa ausência de capítulos no Android.
- `INCOMPLETE_REJECTED_FIELDS` / `INCOMPLETE_BUDGET_EXCEEDED`: envelope não pôde ser preservado com segurança, portanto não foi usado como prova de completude.
- `INCOMPLETE_CAPTURE_CURSOR`: consulta de lookup/capítulos deixou `nextCursor` pendente, cursor inesperado ou interrompeu uma cadeia iniciada. Páginas 200 obtidas não são inventário completo.
- `NO_REPLAYABLE_SOURCE_RESPONSE`: houve somente metadados de páginas Reader, sem resposta efetiva de lookup/capítulos a decodificar.
- `REAL_RESPONSES_DECODED_NOT_CANONICAL`: o contrato Kotlin daquelas respostas recebidas foi aceito. **Não** prova que todas as fontes/candidatos foram explorados ou que uma edição está vinculada corretamente.

A CI pode avaliar trabalhos conhecidos ou inéditos; *nenhuma* alteração em testes ou commits é necessária para acrescentar um título. O corpus antigo de 67 obras continua útil para regressões offline, mas não é pré-requisito da execução genérica.

## Extensão da PR #210 — catálogo independente real no Android (opt-in)

O mesmo workflow agora também inclui **três shards Android paralelas** que consultam a integração de catálogo **MangaUpdates** de produção do Tsuzuki, usando `MangaUpdatesIntegrationProvider` e a implementação HTTP do próprio `TrackerManager`. É uma fonte independente do MangaAll, não uma resposta construída a partir de HIDs. Não reimplementa o algoritmo de identidade no runner: executa `ResolveMangaAllIndependentCatalogIdentity`, um interactor Kotlin do Domain. O mesmo campo `works` (1–30 obras) alimenta fonte e catálogo; `case=user-<hash>` correlaciona ambos sem expor nomes livres nos artefatos.

- `CATALOG_AMBIGUOUS`: duas identidades sem crosswalk verificado, mesmo com título idêntico; nunca selecionar o primeiro.
- `CATALOG_INCOMPLETE`: alguma integração habilitada falhou, a paginação foi interrompida ou o total relatado contradiz os resultados.
- `CATALOG_UNAVAILABLE`: a integração real não está acessível neste runner; não equivale a obra inexistente.
- `CATALOG_UNVERIFIED`: o endpoint de detalhes não confirmou ID/título/crosswalk ou não estava acessível.
- `CATALOG_FORMAT_UNSUPPORTED`: o catálogo MangaUpdates usado neste gate exclui novels da busca; para `web_novel` e `light_novel`, a ausência de resultados não permite declarar `NOT_FOUND`.
- `CATALOG_NOT_FOUND`: só quando as fontes de catálogo consultadas responderam integralmente e nenhum título/alias exato apareceu.
- `RESOLVED`: o catálogo independente confirmou a identidade exata pelo próprio ID no endpoint de detalhes; `creatorsAttested` indica se houve créditos de autor/artista, **não** autoriza automaticamente uma edição MangaAll.

O probe Android atual usa **apenas MangaUpdates**, embora o interactor aceite todas as integrações reais habilitadas pelo `IntegrationRegistry`. Logo, seu status `RESOLVED` é limitado à autoridade independente consultada; não comprova consenso entre catálogos ainda não integrados ao gate. A instrumentação exige **emulador descartável e build `app.tsuzuki.dev`**, não toca na biblioteca física do usuário. O script envia apenas códigos fechados, números, IDs públicos e um hash derivado do título; não preserva stdout bruto com URLs de respostas. A CI de compilação `mangaall-catalog-instrumentation-compile.yml` valida o harness sem chamar rede externa.

**Distinção importante:** este catálogo live é um estágio **paralelo** ao decodificador Host. Ainda não cria título canônico no SQLite descartável nem fornece metadata/autor ao `CollectProviderReadingEvidence`. A captura MangaAll continua `CAPTURED_REAL_PROVIDER_REPLAY` e a resolução de catálogo, quando efetivamente executada na instrumentação, é `DIRECT_LIVE_ANDROID_CATALOG_ONLY`. Não há `DIRECT_LIVE_ANDROID` de leitura nem aceite end-to-end; o próximo passo segue conectar essas duas linhas à reconciliação e à UI.

## O que NÃO pode ser declarado por esta etapa

- O arquivo `.tsz` é realmente assinado; a **disponibilidade** da fonte varia por ambiente. MangaFire pode responder 403/challenge no GitHub runner mesmo funcionando no A54, logo `NO_CHAPTERS` exigiria um inventário de fonte completo e autoridade independente — não só HTTP200 ou `nextCursor=null` isolado.
- Um envelope não contém a identidade canônica/autoria verificada pelo catálogo do Tsuzuki. `CollectProviderReadingEvidence`, `RefreshChapterEvidence`, `ReconcileChapterEvidence` e a consulta SQL de capítulos canônicos **não são executados aqui**. Não transferir autoria do próprio MangaAll para se autocorroborar.
- O Reader recebe apenas a contagem/tipo de páginas com URLs confidenciais/CDN omitidas. `READY` e URLs não são pixels desenhados, nem leitura validada.
- O probe de fonte intencionalmente amostra HIDs/candidatos sob orçamento; um retorno de decoder positivo não é um atestado de *todas* as edições existentes.

## Próxima etapa de engenharia (#207)

**Bootstrap canônico real, independente e resistente à ambiguidade** usando os providers de catálogo de produção do Tsuzuki: título idêntico sem identidade verificável não é autor confiável; quando há concorrentes ainda legítimos, reportar `CATALOG_AMBIGUOUS` e nunca selecionar o primeiro. Se a origem não estiver acessível, `CATALOG_UNAVAILABLE`.

Com identidade confirmada e hashes da captura validados, conectar envelopes de chamadas a um `ProviderReadingGateway` reversível e *acionado* pelo Host; executar `CollectProviderReadingEvidence` real, atualização, `ReconcileChapterEvidence`, persistência descartável e consulta de capítulos exibidos, preservando `PROVIDER_SNAPSHOT_WRITE` vs `DELTA_WRITE` e progresso. Se o Host solicitar uma combinação de título/cursor/HID ausente da captura, reportar `REPLAY_GAP` (incompleto) em vez de fabricar resposta.

Por fim, verificar a escolha do capítulo/fonte no Reader; imagens/pixels exigem teste online Android separado e autorização do CDN. A execução com captura chama-se `CAPTURED_REAL_PROVIDER_REPLAY`, nunca `DIRECT_LIVE_ANDROID`.

Este documento não é aceite físico do Host nem do MangaAll; serve para executar o estágio real que já existe sem confundi-lo com a meta final.
