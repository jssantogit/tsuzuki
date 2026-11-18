# MangaAll — CI genérica real do catálogo até capítulos SQL e opções de leitura Reader

**Após [PR #212](https://github.com/jssantogit/tsuzuki/pull/212) mesclada (40b2791b) e [#213](https://github.com/jssantogit/tsuzuki/pull/213) em validação:** capturas HTTP reais do MangaAll code8 assinado + atestação real de catálogo independente → `CollectProviderReadingEvidence` → `RefreshChapterEvidence` → `ReconcileChapterEvidence` → SQLDelight real em memória → `ResolveProviderChapterReading.options()` e `auditNoMatch()` sobre uma **amostra declarada** dos capítulos persistidos. Isso verifica opções de fonte/identidade, **não** entrega de páginas, escolha efetiva do usuário, pixels do Reader nem montagem visual Compose. [Issue #207](https://github.com/jssantogit/tsuzuki/issues/207) permanece aberta.

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

**Distinção importante:** o catálogo live opera em job Android separado; a PR #211 agrega atestação por obra ao artefato, transferindo identidade/autor comprovados para `CollectProviderReadingEvidence` numa execução JVM com o `ScriptProviderReadingGateway` de produção. O Host cria apenas um título efêmero de teste, **não grava estado canônico em SQLite** nem declara edição/Reader prontos. A captura MangaAll continua `CAPTURED_REAL_PROVIDER_REPLAY`; a consulta de catálogo continua `DIRECT_LIVE_ANDROID_CATALOG_ONLY`. Não há `DIRECT_LIVE_ANDROID` de leitura nem aceite end-to-end.

## Extensão da PR #211 — catálogo real + coletor canônico (sem SQLite)

Na execução manual opt-in, depois de concluídas as 3 shards de consultas HTTP reais do MangaAll e as 3 shards Android do catálogo independente, o job `canonical-collector-real-replay` aguarda os dois artefatos por shard. A instrumentação exporta uma atestação restrita com `caseId=user-<SHA>`, identidade, autoria, créditos e IDs externos retornados do próprio `MangaUpdatesIntegrationProvider` (não do MangaAll). O script `validate_mangaall_catalog_attestation.py` rejeita identidade sem comprovação, campos extras, autoria contraditória, títulos sem hash correspondente e lotes inválidos; um shard sem obras precisa relatar explicitamente `selected=0`. Não são persistidos logs HTTP brutos.

O novo `MangaAllCanonicalCapturedReplay` valida o .tsz assinado e os checksums originais, une casos pelo ID calculado da entrada real e passa uma fita de envelopes ao `ScriptProviderReadingGateway` de produção. Executa `CollectProviderReadingEvidence` Kotlin real com metadata autoral de procedência independente, em repositórios efêmeros de teste. O teste **não injeta capítulos ou HIDs artificiais**. Se o catálogo não resolver a obra, não escolhe um homônimo; se uma obra da fonte não tiver resposta de catálogo, registra `CATALOG_CAPTURE_MISSING`. Se o Host pedir outro título, cursor ou binding, registra `REPLAY_GAP`.

Os relatórios `MANGAALL_CANONICAL_COLLECTOR` distinguem `COLLECT_EXECUTED_NOT_RECONCILED`, `COLLECT_PARTIAL`, `REPLAY_GAP`, `CATALOG_CAPTURE_MISSING` e demais estados inconclusivos. Contagens de observações são do coletor, **nunca** capítulos canônicos persistidos. O próximo estágio da #207 é executar `RefreshChapterEvidence` e `ReconcileChapterEvidence` com repositórios SQLDelight de banco descartável, verificar os capítulos realmente consultáveis na UI e separar pixels de imagem para Android autorizado.

## Extensão da PR #212 — refresh e reconciliação REAL em SQLite descartável

Um quarto estágio por shard reutiliza os **mesmos envelopes HTTP reais e catalogação independente** das etapas anteriores. O teste `MangaAllRealSqliteRefreshTest` roda `RefreshChapterEvidence.execute(forceRefresh=true)` com o coletor real do Domain, um `ScriptProviderReadingGateway` de produção e o tape estrito de chamadas capturadas. Não há capítulos, candidatos ou autores sintéticos no modo opt-in LIVE. `REPLAY_GAP`, `CATALOG_CAPTURE_MISSING` e `INCONCLUSIVE_UPSTREAM` permanecem explícitos.

Cada obra elegível recebe um banco `JdbcSqliteDriver.IN_MEMORY` isolado, com o esquema `Database.Schema` e as implementações **concretas** `CanonicalTitleRepositoryImpl`, `CanonicalMetadataSnapshotRepositoryImpl`, `ProviderReadingBindingRepositoryImpl`, `CanonicalChapterRepositoryImpl` e `ChapterEvidenceRepositoryImpl`. O título e identidade externa vêm da verificação real do catálogo; metadados/autores são gravados com sua proveniência. A chamada do coletor é observada por um proxy de teste transparente, que apenas encaminha ao método real e registra o resultado **uma única vez**; nunca fornece resposta mockada de capítulos. Os provedores editoriais não relacionados não são simulados: este é o caminho provider-only do refresh.

`ReconcileChapterEvidence` efetua os writes reais. O teste relê IDs de capítulo e a query do repositório usada pelos consumidores da UI, conta evidências totais/mapeadas e capítulos confirmados, provisórios e conflitantes. Uma verificação fail-closed exige que cada observação de capítulo aceita pelo coletor esteja persistida, **mesmo se o parser não autorizar mapeamento canônico**. O relatório `MANGAALL_REAL_SQLITE_REFRESH` não marca como capítulo ausente qualquer prova parcial, erro de rede ou lacuna de captura.

Testes offline **contratuais** (não anunciados como consultas live) cobrem: snapshot completo, página parcial aditiva, snapshot vazio completo e isolamento de suporte entre duas fontes distintas. A CI normal executa esses testes e a compilação Android. Só `workflow_dispatch` com 1–30 títulos aciona HTTP real e produz o relatório SQL por obra. Nenhuma imagem ou payload CDN é reproduzida.

## Extensão #213 — Reader source/chapter gate sobre SQLDelight real (sem CDN)

O Host agora persiste também a associação por HID/faceta de fonte (`ProviderWorkSourceAssociationRepositoryImpl`) e checkpoints provisórios de paginação (`ProviderProvisionalCursorRepositoryImpl`). Isso é indispensável: **evidência de capítulo persistida não basta** para que o Reader aceite um HID; o `ResolveProviderChapterReading` exige fonte elegível, autoria corroborada ou edição confirmada, ausência de desqualificação editorial, capítulo mapeado e identidade/idioma consistentes. Nenhuma associação é criada por inferência nem promovida por título.

Depois do refresh, `MangaAllReaderSqliteProbe` consulta uma amostra determinística de **até 12 capítulos SQL por obra**, incluindo início/fim e casos de numeração especial quando disponíveis, através do resolvedor Reader Kotlin de produção. O relatório inclui `readerSampled`, `readerUnique`, `readerAmbiguous`, `readerMissing`, `readerNoSourceReasons` com **códigos fechados**, além do número de associações persistidas. Se não há opções, `auditNoMatch` atribui uma razão em vez de informar simplesmente `NO_CHAPTERS`. Se houver múltiplas opções sem escolha explícita, não é feita escolha automática. Uma amostra não prova todos os capítulos e nunca é descrita como tal.

Testes **contratuais offline**, separados do fluxo LIVE, exercitam a relação entre SQLDelight e `ResolveProviderChapterReading`, `SelectMangaAllSource`, `ResolveMangaAllSelectedChapter`: um capítulo mapeado com fonte corroborada é elegível; uma preferência explícita de teste pode selecionar o capítulo exato; um HID sem associação, autoria conflitante, idioma incompatível ou capítulo ausente nunca se torna legível. Isso **simula uma escolha explícita somente no teste**, não instala ou altera preferências do usuário nem escolhe a edição automaticamente na execução real.

A fita assinada existente remove propositalmente URLs, headers, dados CDN e corpo de páginas: **nunca executar ou declarar `delivery()`/`preparedContent()` real a partir desses metadados**. Marcar `pages=METADATA_ONLY_NOT_REPLAYABLE`, `uiPixels=NOT_EXECUTED_HOST`. O último estágio da #207 exige uma execução Android online separada/autorizada para páginas e renderização, ou um transportador seguro novo capaz de capturar respostas sem expor credenciais.

## O que NÃO pode ser declarado por esta etapa

- O arquivo `.tsz` é realmente assinado; a **disponibilidade** da fonte varia por ambiente. MangaFire pode responder 403/challenge no GitHub runner mesmo funcionando no A54, logo `NO_CHAPTERS` exigiria um inventário de fonte completo e autoridade independente — não só HTTP200 ou `nextCursor=null` isolado.
- A etapa #213 executa **resolução de opções de capítulo/fonte no Reader**, mas sobre uma amostra declarada de capítulos persistidos em banco descartável. Não executa páginas/CDN, preferência explícita real nem UI Compose. Não transportar autoria do próprio MangaAll para autocorroborar.
- O Reader recebe apenas a contagem/tipo de páginas com URLs confidenciais/CDN omitidas. `READY` e URLs não são pixels desenhados, nem leitura validada.
- O probe de fonte intencionalmente amostra HIDs/candidatos sob orçamento; um retorno de decoder positivo não é um atestado de *todas* as edições existentes.

## Próxima etapa de engenharia (#207)

Depois da validação da #213, a #207 ainda precisa de um gate Android com dados reais para abertura do capítulo no Reader, chamada `ProviderReadingGateway.pages`/pré-carregamento, proteção de autoridade de CDN e prova de páginas efetivamente exibidas. Não simular metadados de página como pixels. Verificar também a lista Compose e o estado de progresso real num banco isolado. O replay com captura chama-se `CAPTURED_REAL_PROVIDER_REPLAY`, não `DIRECT_LIVE_ANDROID`.

Um `REPLAY_GAP` de título/cursor/HID, um upstream 403 ou uma resolução de fonte sem opções ainda exigem diagnóstico; nenhum dos três comprova ausência de capítulos.

Este documento não é aceite físico do Host nem do MangaAll; serve para executar o estágio real que já existe sem confundi-lo com a meta final.
