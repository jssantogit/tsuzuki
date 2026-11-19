# MangaAll — primeiro gate Android online de páginas reais (code8)

**Escopo:** dois gates de execução **manual** para o pacote publicado
`app.tsuzuki.mangaall` 0.5.3/code8, em `app.tsuzuki.dev` descartável:
`transport` (decodificação de até duas imagens da CDN pelo Host) e `reader`
(a mesma CDN real no `ReaderActivity` de produção e comprovação visual
conservadora). O modo `reader` é **transporte/UI em título de teste**:
não atesta identidade editorial do catálogo independente nem aceita obra
real na biblioteca. Não instala APK no Galaxy A54.

## Por que é separado

O workflow genérico `mangaall-real-host-decoder.yml` executa lote de 1–30
obras, coleta dados HTTP reais e resolve identidade, capítulos SQL e opções
do Reader, mas intencionalmente omite URLs e bytes de imagens do replay.
Este gate não remove essa proteção nem transporta URLs de CDN em artefatos:
o Android consulta o provider **diretamente** na execução e não exporta
nenhuma URL, header, imagem ou log bruto.

## Gate visual offline independente: code8 original no ReaderActivity

O workflow `mangaall-host-journey-android.yml` foi estendido para verificar
o **pacote original distribuído 0.5.3/code8**, índice assinado seq34 e SHA,
sem substituir seu JavaScript. Em um emulador descartável, o teste
`mangaAllPublishedCode8TszSourceTap503RetryRendersCdnPixels` utiliza os
componentes reais `ReaderActivity`, `ReaderViewModel`, runtime SCRIPT,
seleção de fonte e pipeline de renderização, simulando HTTP 503 e retry
contra respostas controladas. O teste exige regiões com pixels verdes da
imagem PNG de fixture na tela e preservação da sessão anterior.

Esse gate é **offline**, não prova disponibilidade de MangaFire/CDN
externa nem que imagens remotas apareçam na tela. Na PR, roda
apenas essa regressão; o workflow histórico completo permanece disponível
manualmente. Não há mudança de DI de produção, confiança assinada,
instalação física nem estado de biblioteca do usuário.

## Execução manual (quando o workflow estiver na main)

1. Abra o workflow `MangaAll signed code8 — Android live pages and Reader pixels`
   via **Run workflow**. Não há execução LIVE automática, por PR ou por schedule.
2. Escolha `gate_mode=transport` para download/decodificação sem UI, ou
   `gate_mode=reader` para imagem realmente exibida no Reader. Informe o
   `provider_work_id`, `provider_chapter_id` e `facet_language`
   **observados** no MangaAll real. Não invente IDs nem use cookies,
   credenciais, URLs ou headers privados como argumentos.
3. O runner valida o pacote original oficial e o índice assinado seq34,
   SHA-256 e versionCode8 antes de incorporar o `.tsz` ao AndroidTest.
   A aplicação e os dados são descartáveis; nada é publicado ou inscrito
   num repositório/raiz de confiança do usuário.
4. Os dois modos chamam `chapters()` com o mesmo binding de fonte e faceta,
   exigindo o ID exato na primeira página de inventário. A ausência na amostra
   é inconclusiva; não se declara `NO_CHAPTERS`. O modo `transport`
   prossegue para `pages()` e até duas imagens pelo loader real.
5. O modo `reader` somente aceita um capítulo fonte explicitamente observado
   como **capítulo 1 sem volume**, compatível com o capítulo de teste efêmero.
   Concilia apenas essa evidência sem afirmar completude nem identidade
   editorial independente, abre a mesma `ReaderActivity`, toca a linha
   de fonte Compose e recebe a imagem pelo loader HTTP de produção. Somente
   no test harness se limita o loader a duas páginas; não muda a fábrica
   de páginas nem a UI de produção.
6. A prova visual exige estado `Page.Ready` com stream, chave de fonte correta,
   `SubsamplingScaleImageView` anexada, pronta e visível, janela focada e
   pelo menos duas classes tonais comuns entre a imagem decodificada e a
   captura **em memória** da região visual. Uma página uniforme ou ambígua
   não recebe PASS. O processo nunca grava as imagens, screenshots nem
   URLs da CDN em arquivos ou logs.
7. A CI retém somente `verdict.txt` com código fechado por três dias;
   `app.tsuzuki.dev` é limpa no final. Erros e desafios HTTP não autorizam
   rebind de edição, bypass de proteção de sites ou inferência editorial.

## Critério de aceite e estado

- `transport PASS|pagesDecoded=1/2|readerActivityPixels=NOT_TESTED`:
  bits reais da CDN carregados e decodificados pelo Host Android; não é
  prova de imagem visível no Reader.
- `reader PASS|pagesDecoded=1|readerActivityPixels=REAL_CDN_VISIBLE`:
  requisição de páginas do pacote original code8, clique de fonte e imagem
  real decodificada com evidência de pixels na janela do Reader descartável.
  **Não** prova a identidade editorial da obra, instalação de assinatura
  no A54 nem permanência de progresso em dados físicos.
- `INCONCLUSIVE`: fonte/CDN bloqueada, página fora da primeira janela
  de capítulos, volume/número incompatível com o fixture, conflito, CDN
  indisponível ou imagem sem amostra visual suficiente. Não equivale a
  `NO_CHAPTERS`, nem transforma uma edição em outra.
- `FAIL`: quebra de instrumento/contrato, parâmetro inválido, assinatura
  incorreta ou exceção inesperada. Inconclusivo não recebe gate verde.

**Validação implementada:** [CI compile #44506722152](https://github.com/jssantogit/tsuzuki/actions/runs/44506722152)
**SUCCESS** e [CI Android offline #44506722008](https://github.com/jssantogit/tsuzuki/actions/runs/44506722008)
**SUCCESS**, com `CODE8_PUBLISHED_TSZ_CONTINUOUS_READER_TAP_503_RETRY_PIXELS=PASS`.
Esses dois resultados não executaram `workflow_dispatch`; nenhum
byte remoto de CDN ou screenshot real pode ser declarado testado.

**Pendente na #207:** execução manual LIVE quando o workflow estiver na
`main`, prova real da rede externa no emulador e ligação editorial canônica
validada contra catálogo independente/SQL, seguida de smoke físico A54.
Não há merge, publicação de provider nem instalação física nesta PR.

Referências: [issue #207](https://github.com/jssantogit/tsuzuki/issues/207),
[workflow canônico](https://github.com/jssantogit/tsuzuki/actions/workflows/mangaall-real-host-decoder.yml),
[gate offline original code7](https://github.com/jssantogit/tsuzuki/blob/main/docs/mangaall-host-journey-emulator-20261112.md).
