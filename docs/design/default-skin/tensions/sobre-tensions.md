# Sobre — fechamento de Configurações

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Provisional

Sobre usa a Default Skin clara e a assinatura horizontal oficial de `R.drawable.ic_tsuzuki_lockup`, utilizada por `LogoHeader`. O SVG local é uma transposição dos paths e transformações do VectorDrawable, com tint de texto equivalente ao `onSurface`; não é um redesenho. A proporção original é 294:64. Marca fora de card, versão discreta e somente Projeto / Código aberto.

Medidas de marca, respiros e anatomia do shell de Licenças permanecem Provisional. Não há consolidação em `design.md` ou Notion nesta rodada.

## Fontes

Checkout `repositório Tsuzuki`: `AboutScreen.kt`, `OpenSourceLicensesScreen.kt`, `LogoHeader.kt`, `ic_tsuzuki_lockup.xml`, `app/build.gradle.kts` e `gradle/libs.versions.toml`.

O AboutScreen fornece os destinos GitHub de Tsuzuki e Mihon e o formato de versão Stable / FOSS / Nightly / Debug. A apresentação recebe um registro de build; não fixa um canal ou número como regra do produto. Stable v0.20.4 é apenas fixture baseada no checkout. Data/commit são exibidos somente quando fornecidos; não foram inventados.

Licenças mantém o contrato `produceLibraries(R.raw.aboutlibraries)` e detalhes em sheet. O catálogo gerado não está disponível no checkout acessível. O HTML usa uma amostra documental de dependências reais (AboutLibraries, OkHttp e Kotlin Coroutines) com versões do version catalog. Essa amostra não é o catálogo completo nem prova os textos legais do build. Nomes não são contrato fixo; o componente recebe uma lista variável. Nenhuma licença foi atribuída por suposição.

## Correções delimitadas

Diagnóstico já tinha o hub sem label redundante e Installation ID com cópia e regeneração separadas; os componentes são reaproveitados sem redesenho. Avançado remove somente os chevrons de cookies e WebView. A fonte ilustrativa Hikka é substituída por MangaBall; contagens são fixtures. O frame inferior usa rolagem da mesma tela. A confirmação adicional começa com proteção desativada para demonstrar o aviso de risco; o fluxo habitual continua iniciando com proteção ativada.

## Limites / TBD

Operações Android, dados instalados e catálogo legal integral dependem do app. O protótipo não executa jobs nem limpeza de dados reais. Versão não copia diagnóstico e não possui gestos ocultos. Onboarding legado e funções adicionais de Sobre ficam intencionalmente ausentes.

O wrapper de leitura de `od-next-media-inputs` retornou sem conteúdo nesta execução; não houve guidance adicional carregada. A marca local foi transposta por ferramentas nativas, preservando a fonte oficial.
