# Wave 5 — Canonical Details: relatório de implementação

## 1. Branch, HEAD e commits

Branch: `design/default-skin-consolidation`. Baseline aceito e preservado:
`799984804d8db70598e84cfd1dc90a52f6129069`.
HEAD de implementação verificado pelo CI:
`f5d2b1200167b67fa44d794edd041e7089eb9acc`.
O commit documental que inclui este relatório vem depois desse HEAD e não altera código.

Commits da wave, em ordem:

| Commit | Resultado |
| --- | --- |
| `2f6ec3a14` | RED das regressões de refresh e caracterização do resolver existente |
| `8b8f22bf3` | Contratos RED de leitura, capítulos e Providers |
| `8c27536de` | Integração mínima do resolver, preservação de conteúdo e funções derivadas |
| `a502a2799` | RED adicional de contexto inicial e checkpoints durante degradação |
| `54bcaf243` | Correções de contexto/progresso e GREEN comportamental |
| `abfc809ed` | Promoção visual de Hero, editorial e capítulos |
| `f5d2b1200` | Correções de API, fidelidade visual e acessibilidade; CI final verde |

## 2. Arquivos e subsistemas

Details mantém `CanonicalTitleScreen.kt` e `CanonicalTitleScreenModel.kt` como adapters
e owner. A apresentação foi decomposta em `CanonicalTitleDetailScreen.kt`,
`CanonicalDetailsHero.kt`, `CanonicalDetailsChapterComponents.kt`,
`CanonicalDetailsChapterPresentation.kt` e `CanonicalDetailsProviderLabels.kt`.

As duas sheets existentes, `ContentOptionSelectorSheet.kt` e
`ContentBindingLinkSheet.kt`, recebem `ContentProviderLabels.kt` opcional para a copy
pública de Details. Chamadores existentes do Reader preservam os defaults e contratos.
Foram adicionados 68 recursos em cada bundle base/pt-rBR, cinco arquivos de testes App
foram adicionados/estendidos, e um teste do resolver Domain foi estendido. O plano e
este relatório registram a verificação. Nenhum arquivo de implementação Reader,
Provider Platform, P2P, matching ou dos demais roots foi modificado.

## 3. Runtime reutilizado

Identidades continuam canônicas. Foram reutilizados os repositories de capítulos e
progresso, resolução de artwork, metadata/integration e ratings, mutations de Library,
`CanonicalReadingStartResolver`, `GetCanonicalReadingStart`, `OpenCanonicalChapter`
e `ReaderActivity.newCanonicalIntent`. Não há um segundo Details runtime, resolver
de leitura, materializer ou modelo canônico.

Library, Search, Home e Collections continuam desembocando em `CanonicalTitleScreen`.

## 4. Artwork e title-logo

Hero usa um único artwork decorativo real, com a cadeia de fallback já existente,
crop atmosférico e fade longo até a mesma cor do background. Ausência/falha final da
imagem mantém fundo limpo, título acessível, metadata, ratings e ações.

A investigação de app/domain/data não encontrou owner semântico de title-logo.
O fallback é título textual. Nenhum logo, artwork ou fixture do board foi copiado,
sintetizado ou buscado em integração nova.

## 5. CTA Start/Continue

“Continuar lendo” depende de progresso válido associado a capítulos reais:
`read`, `lastPageRead > 0` ou `lastVariantId` real. Timestamp, status READING e
presença de capítulos não indicam resume. Sem progresso, “Começar a ler”.

O CTA delega ao resolver existente. `Ready` é validado pela identidade canônica e
passa pelo gateway existente antes do Reader. `Unavailable`, identidade estrangeira
ou falha deixam Details utilizável e não abrem Reader. O target não é escolhido na UI.
Sem capítulos observados, a leitura fica indisponível, preservando o resto da obra.

## 6. Contextual chapter rail

Uma função pura seleciona até cinco capítulos observados na ordem real. Nunca
iniciado usa a janela inicial; progresso ativo usa identidade canônica e contexto
ao redor; caught-up usa a janela final. Coleções pequenas permanecem completas.
Identidade ativa desconhecida não produz capítulos. Slots inferidos de contagem não
viram disponibilidade de leitura na apresentação. Decimais, fractions, specials,
gaps e IDs reais são preservados; nenhuma sequência 1…N é gerada.

## 7. Full list, jump, sort e scrubber

“Ver todos” muda somente a região inferior; Hero permanece. Back restaura o modo
compacto antes de sair da obra. A lista é virtualizada e keyed por ID canônico.
Capítulos lidos recuam visualmente; progresso parcial real recebe uma linha fina.
Como o progress model não fornece total de páginas, nenhum percentual é inventado.

Sort local mantém a ordem original ou a reversão da coleção carregada, sem alterar
persistência/domínio. Jump resolve somente ID, display number ou título real exato;
frações/specials são alcançáveis e ausência não fabrica nem abre capítulo.
O scrubber tem threshold local/testável de 80 itens e converte posição em índice e
objeto real. Bubble apresenta o label real; release reposiciona a lista e oculta a
bubble. Não há interpolação de números de capítulos.

## 8. Recommendations

O visual aprovado prevê essa capacidade, mas a investigação não encontrou owner
real de Recommendations/related works no runtime atual. A seção permanece omitida.
Nenhuma lista, API, fixture ou integração foi criada para preencher o layout.

## 9. Add-ons → Providers

A superfície pública de Details e suas sheets usa Providers. A ação abre
`SettingsTsuzukiProvidersScreen`, o mesmo destino público estabelecido por Search.
Não houve simples troca de texto mantendo uma rota Add-ons. Compatibilidade interna
e copy default de outros chamadores permanecem; Provider Platform não foi alterado.

## 10. Testes e TDD

Foram adicionados 34 testes App e uma caracterização Domain, preservando os testes
existentes. Os contratos cobrem Start/Continue e resolver Ready/Unavailable, identidade
canônica, artwork/chapters ausentes, todos os ratings, erros iniciais recuperáveis,
refresh degradado, contexto de capítulos, progresso, modos/Back, sort/jump/scrubber,
Providers routing/copy e compatibilidade dos defaults do Reader.

Arquivos: `CanonicalTitleScreenModelTest`, `CanonicalTitleRefreshPreservationTest`,
`CanonicalDetailsChapterPresentationTest`, `CanonicalTitleProvidersNavigationTest`,
`CanonicalDetailsProviderCopyTest` e `GetCanonicalReadingStartTest`.

RED foi comprovado antes de produção: dois defeitos reais de refresh no baseline;
APIs comportamentais ausentes; depois três regressões adicionais de contexto e
checkpoint. Uma fixture de artwork foi corrigida para retornar null explicitamente,
sem enfraquecer assertions. GREEN comportamental precedeu a promoção visual.

## 11. Formatter, Gradle e CI

Formatter ktlint adotado pelo repositório e `git diff --check` passaram localmente.
A tentativa Gradle focada parou em `SDK location not found`, antes de compilation
Android/testes. ANDROID_HOME/ANDROID_SDK_ROOT estavam ausentes; não há compile local
verde alegado.

[CI final 38140023931](https://github.com/jssantogit/tsuzuki/actions/runs/38140023931)
passou no HEAD de implementação: 580 testes App Tsuzuki PASSED, incluindo contratos
existentes da área e navegação dos roots. `:app:compileDebugKotlin`,
`:app:compileDebugUnitTestKotlin` e `:app:testDebugUnitTest` executaram; BUILD SUCCESSFUL.
Format e CI Gate passaram.

[CI comportamental 38138486075](https://github.com/jssantogit/tsuzuki/actions/runs/38138486075)
já havia passado antes da UI. [CI inicial 38136815213](https://github.com/jssantogit/tsuzuki/actions/runs/38136815213)
executou Domain Tsuzuki com sucesso, incluindo a nova caracterização de IDs irregulares.
Não houve mudança de produção Domain posterior.

A primeira compilação visual falhou por três usos de API/namespace Compose; foram
corrigidos no batch final. O histórico RED/falhas está no plano, sem tratar falha ou
planner-skipped como evidência verde.

## 12. Executados versus skipped

| CI final | Resultado |
| --- | --- |
| Change Planner | Executado, sucesso |
| Format | Executado, sucesso |
| Tests — App — Tsuzuki | Executado, sucesso; 580 testes e compilação Android dentro do job |
| CI Gate | Executado, sucesso |
| Compile standalone, Release Compile | Planner-skipped |
| Native Package, SQLDelight Migrations, Supabase Backend | Planner-skipped |
| Domain Tsuzuki | Executado no CI inicial; não reexecutado pelo diff final |
| Instrumented/emulator/Android físico | Não executado |

## 13. Impeccable gate

Gate concluído por comparação de código com design.md, index.html e os assets
canonical-details.js/css, após GREEN comportamental. Estados revisados: progresso,
nunca iniciado, muitos ratings, editorial, full list, scrubber, sem artwork,
sem capítulos e refresh degradado. Recommendations foi avaliado como capacidade
omitida por falta de owner, sem fixtures.

Correções do batch: tema real do app e fade exato; escalas reais e overflow cue dos
ratings; formato Webtoon; bubble seguindo posição e separada do track; capítulos
lidos sem marker de capítulo ativo; acessibilidade de specials/download pendente;
safe areas horizontais e foco/IME do jump. Fonte visual single-artwork, metadata
colapsável, touch targets, wrapping, insets, sheets e hierarquia foram confirmados
por código. Gate não reinterpretou domínio, sourcing ou execução.

## 14. Aceitação pendente no Android real

Ainda precisam de aceitação física: render de artwork, font scaling e títulos longos,
TalkBack/foco, dark theme, notch/system chrome/insets, teclado/sheets, gesto do
scrubber e recenter em obras grandes, scroll e experiência do gateway de leitura.
Testes unitários/compilação não são execução de comportamento Android. Nenhum
telefone pessoal, emulator ou probe de Provider real foi automatizado nesta wave.

## 15. Limites confirmados

- Nenhum APK gerado.
- Nenhum merge em `main`.
- Nenhuma mudança de implementação Provider Platform/P2P/torrent/Debrid/matching.
- Reader Default Skin não iniciado; Reader UI, controls e runtime permanecem fora da wave.
- Nenhuma fixture do mockup em produção, novo canonical model ou API de preenchimento.
- Wave 5 encerra aqui; nenhuma Wave 6 foi iniciada.
