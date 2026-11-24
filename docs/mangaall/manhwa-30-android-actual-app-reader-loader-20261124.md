# MangaALL — carregador REAL do Reader Android, 30 manhwas congelados

**24 nov 2026 · [CI #45373652573](https://github.com/jssantogit/tsuzuki/actions/runs/45373652573) · SUCESSO no build e nos 3 shards de emulador · PR #275 Draft/unsigned.**

## Evidência na camada real do app

- Coorte exata [MANHWA_30_FIXED_20261123](../../.github/mangaall-dual-source-30-cohort-lock.json), hash SHA256 `19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1`, verificado pelo workflow e por cada teste dentro do Android. **Nenhuma das 30 obras foi substituída**.
- O `MangaAllFrozen30Code11AndroidLiveInstrumentedTest` executa `reading.lookup` em fontes reais MangaDex/MangaDot, e percorre `reading.chapters` até cursor terminal para comprovar um capítulo 1 em um HID específico de fonte. Não mistura HIDs rivais.
- `reading.pages` entrega metadados HTTPS não vazios para **esse mesmo HID e capítulo 1**.
- **Diferentemente da [CI #45371037327](https://github.com/jssantogit/tsuzuki/actions/runs/45371037327), que decodificou imagens via HTTPS nativo genérico**, este teste usa o `eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpPageLoader` **do próprio aplicativo**.
- A prova cria `PreparedHttpPage` com os cabeçalhos fornecidos por `reading.pages` e a permissão de rede do manifest **experimental unsigned**; o loader usa seu OkHttp, valida `ProviderNetworkPolicy`, não segue redirecionamentos, limita o corpo a até 16MiB e grava em cache privado temporário. A instrumentação exige `Page.State.Ready` e `ReaderPage.stream` legível, decodifica o bitmap da página e lê um pixel, sem reatribuir HID, capítulo ou obra. `recycle()` exclui cache ao final.
- Os metadados temporários e o conteúdo das imagens **não são publicados**. Artefatos apresentam somente vereditos sanitizados.

| Job da CI | Cobertura | Veredito |
|---|---|---|
| `build-once-unsigned` | Par comum de APKs | **SUCCESS** |
| `frozen-manhwa-app-reader-loader (0)` | Obras 1–10 | **SUCCESS** |
| `frozen-manhwa-app-reader-loader (1)` | Obras 11–20 | **SUCCESS** |
| `frozen-manhwa-app-reader-loader (2)` | Obras 21–30 | **SUCCESS** |

O primeiro ensaio [#45373255835](https://github.com/jssantogit/tsuzuki/actions/runs/45373255835) falhou somente no Spotless por ordem de imports e quebra de linha. Corrigido antes da prova funcional, sem mudanças no corpus ou enfraquecimento do gate.

## Limites de escopo, sem promover a produção

- Este **não é um teste de UI** do `ReaderActivity`, `ReaderViewModel` ou do viewer que efetivamente desenha pixels na tela e avança entre páginas/capítulos. O estado Ready pertence ao `ProviderHttpPageLoader`, e a imagem comprovada é a **primeira página do capítulo 1** do HID selecionado.
- **Não prova identidade editorial canônica**, autor, equivalência de edição de provedor, resolução de homônimos, vinculação/seleção de entidades no catálogo e SQLite, persistência/retomada após reinício ou capítulos posteriores. Todos esses gates continuam abertos.
- O manifesto code11 é experimental, **não assinado**, com snapshot nativo de capítulos, consultas específicas para manhwas e duas fontes. Não prova que o Host signed code10 suporta respostas MangaDot acima do teto legado de 192KiB, nem que o MangaFire está disponível. O agregado mais abrangente de fonte permanece RED/NEEDS_CORRECTION.
- Não há merge, assinatura, publicação ou liberação da coorte. **A lista congelada de 30 obras não deve ser trocada antes de validação integral.**

## Próximo gate

Conectar este transporte real ao **caminho canônico + persistência SQLite + UI/viewer**, com detecção explícita das edições rivais por fonte e provas de navegação e renderização reais. Não confundir um test de carregador com a jornada completa.
