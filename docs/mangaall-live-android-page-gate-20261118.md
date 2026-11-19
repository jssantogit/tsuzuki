# MangaAll — primeiro gate Android online de páginas reais (code8)

**Escopo:** gate `DIRECT_LIVE_ANDROID` manual, em emulador descartável,
para `ProviderReadingGateway.pages` do pacote publicado
`app.tsuzuki.mangaall` 0.5.3/code8 e o carregador `ProviderHttpChapterLoader`.
Não envolve `ReaderActivity`, pixels de tela, seleção Compose nem o Galaxy A54.

## Por que é separado

O workflow genérico `mangaall-real-host-decoder.yml` executa lote de 1–30
obras, coleta dados HTTP reais e resolve identidade, capítulos SQL e opções
do Reader, mas intencionalmente omite URLs e bytes de imagens do replay.
Este gate não remove essa proteção nem transporta URLs de CDN em artefatos:
o Android consulta o provider **diretamente** na execução e não exporta
nenhuma URL, header, imagem ou log bruto.

## Execução manual (depois que o workflow estiver na main)

1. Execute o workflow `MangaAll signed code8 — Android live page decode`
   via **Run workflow**. Não há `schedule` nem execução online por PR.
2. Informe `provider_work_id`, `provider_chapter_id` e `facet_language`
   **observados** numa execução de fonte real/Host. Não inventar IDs, não
   selecionar automaticamente uma edição apenas pelo título e não usar
   credenciais, cookies ou headers privados como entradas.
3. O workflow verifica o índice assinado oficial (seq. 34) e o hash do
   archive code8 no commit fixo do repositório Provider. O arquivo `.tsz`
   é incorporado somente como asset de `AndroidTest`, sem publicação.
4. O teste consulta primeiro `chapters()` no mesmo binding/faceta em uma página real de inventário, exigindo que o ID escolhido apareça exatamente uma vez. Um capítulo encontrado somente em outro cursor é **inconclusivo** no escopo desta prova limitada, não ausente. Depois chama `pages()` no gateway SCRIPT/QuickJS e usa a política
   de origens e o carregador HTTP do próprio Host para **até duas páginas**.
   Verifica se o download completou e o Android conseguiu decodificar
   bitmaps reais. Janela máxima individual: chamadas de rede 25s;
   timeout instrumentado de 180s. O teste não contabiliza páginas de
   um inventário canônico nem salva progresso.
5. Somente `verdict.txt` com status fechado é retido por três dias.
   Logs HTTP e URLs são descartados após o emulador. O teste usa
   `app.tsuzuki.dev` e verifica a propriedade de emulador descartável.

## Critério de aceite

- `PASS|provenance=DIRECT_LIVE_ANDROID|pagesDecoded=1/2`:
  imagem(ns) original(is) do upstream carregada(s) e decodificada(s)
  pelo Host Android. **Não prova pixels visíveis no Reader**.
- `INCONCLUSIVE`: origem bloqueada, capítulo não atestado na primeira página, API indisponível, páginas vazias,
  CDN inacessível ou dados que não foram decodificados. Não transforma
  403/challenge ou timeout em `NO_CHAPTERS` ou evidência editorial.
- `FAIL`: instrumentação, contrato de entrada, política de ambiente,
  pacote assinado ou teste quebrado. Um resultado inconclusivo impede
  o gate online de ficar verde, mas preserva a razão fechada.

**Próximo incremento, ainda NÃO implementado neste gate:** combinar o
provider original code8, associação canônica validada, seleção real do
Reader e imagens de rede numa mesma `ReaderActivity`, com prova de
holder anexado e pixels efetivamente renderizados (sem comparar cores
sintéticas numa imagem real). Reutilizar os testes de screenshot/pager
do `CanonicalReaderSourceSwitchInstrumentedTest` mantendo autorização
de fonte, isolamento e sem bloquear toda PR em rede externa.

Referências: [issue #207](https://github.com/jssantogit/tsuzuki/issues/207),
[workflow canônico](https://github.com/jssantogit/tsuzuki/actions/workflows/mangaall-real-host-decoder.yml),
[gate offline original code7](https://github.com/jssantogit/tsuzuki/blob/main/docs/mangaall-host-journey-emulator-20261112.md).
