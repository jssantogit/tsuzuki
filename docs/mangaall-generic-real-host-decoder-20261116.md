# MangaAll — CI genérica de dados reais até o decoder Kotlin do Host (2026-11-16)

**Estado desta etapa:** Provider source real → Host V1 decoder real, mas **ainda não** catálogo independente, reconciliação canônica, SQLite, reader source selection ou pixels. [Issue #207](https://github.com/jssantogit/tsuzuki/issues/207) acompanha o objetivo end-to-end.

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
