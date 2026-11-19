# MangaAll — lote sequencial de títulos reais no Android (code8)

## Objetivo

Aceitar de **1 a 30 títulos na ordem informada** e testar cada um, sem
solicitar `provider_work_id` ou `provider_chapter_id` na interface GitHub.
O lote usa o **mesmo** pacote original MangaAll 0.5.3/code8, root assinado
seq34, sem modificação do JavaScript. O APK `app.tsuzuki.dev` é efêmero,
executado em emulador descartável. A issue #207 segue aberta até prova online
e aceitação editorial/física completas.

## Execução pela interface do GitHub

Em **Actions → MangaAll signed code8 — sequential Android live manga batch →
Run workflow**, use a branch com o workflow (após merge, `main`):

- **titles**: `Attack on Titan, Boku no Hero, Dragon Ball`
  (também aceita linhas separadas ou JSON
  `["Attack on Titan","Boku no Hero","Dragon Ball"]`).
  Em títulos contendo vírgulas, use necessariamente JSON.
- **source**: `mangafire`, `mangadex` ou `mangadot`. A escolha da fonte
  é explícita para todos os títulos do lote; nunca se troca de fonte após
  falha nem se inferem edições por popularidade.
- **facet_language**: `en` para MangaFire, que só anuncia inglês;
  nas outras fontes, escolha a faceta anunciada (por exemplo `pt-br`).
- **gate_mode**: `reader` para imagens reais no ReaderActivity, ou
  `transport` para download/decodificação sem pixels de tela.

A seleção automática vale **somente dentro do teste descartável**.
Um título/alias idêntico numa fonte não constitui confirmação canônica
editorial: o sistema não cria biblioteca real nem vínculo permanente.

## Execução por commit, sem botão Run workflow

1. Crie uma branch `mangaall-live/minha-rodada` a partir da `main`
   **que já contenha este workflow**.
2. Edite o arquivo versionado `.github/mangaall-live-batch.json`,
   por exemplo:

   ```json
   {
     "mode": "reader",
     "source": "mangafire",
     "language": "en",
     "titles": ["Attack on Titan", "Boku no Hero", "Dragon Ball"]
   }
   ```

3. Faça commit e push **da mudança nesse arquivo**. O evento `push`
   ativa o workflow apenas em `mangaall-live/**` e somente se o manifesto
   foi modificado; commits normais na `main` ou PRs não rodam LIVE.
   Novos commits na mesma branch substituem a execução anterior se ela
   ainda estiver em andamento. A ordem dos títulos é preservada.

O botão manual usa os campos informados; o gatilho de commit lê o
manifesto. Não se usa a lista salva para substituir silenciosamente um
input manual.

## Como cada caso é processado

Um único runner Android serial executa, para cada título:

1. Limpa a aplicação efêmera, envia o título codificado em Base64 via ADB,
   valida `code8` assinado e invoca o `ScriptProviderReadingGateway.lookup()`
   no Android com o título, idioma e fonte selecionados.
2. Percorre **até seis páginas de lookup**, com cursores exatos e orçamento
   de tempo. Exige **um único** work ID da fonte selecionada cujo título
   ou alias coincida após normalização de caixa, acentos e espaços.
   Qualquer candidato adicional igual/ambíguo, falha HTTP ou cursor
   incompleto resulta em **INCONCLUSIVE**, nunca pick automático.
3. Usa o binding exato retornado para buscar **até doze páginas** de
   `chapters()`, exigindo um único capítulo número 1, sem volume e na
   mesma faceta de idioma. A ausência em inventário parcial é inconclusiva.
4. Passa os IDs somente **internamente** ao runner Android de leitura
   (`transport` ou `reader`), que **revalida** o binding/capítulo no
   Host. A opção do Reader é selecionada pelo card real; não existe
   bypass de 403/challenge, credenciais nem substituição de edição.
5. Registra `PASS`, `INCONCLUSIVE` ou `FAIL` com motivo fechado,
   limpa dados do caso e segue para o título seguinte, mesmo quando
   um dos anteriores ficou inconclusivo.

A condição do Reader ainda exige que o capítulo de teste efêmero
seja compatível com Ch. 1 sem volume. Não há afirmação de que a edição
seja original, nem comprovação do catálogo independente.

## Saída, limites e segurança

O log do workflow mostra somente `case=1/3`, etapa e código de resultado.
O artefato `summary.json` contém **os títulos fornecidos pelo operador**,
ordem e resultados fechados. Não retém IDs técnicos retornados pela fonte,
respostas de lookup, URLs, headers, cookies, imagens nem screenshots; a
instrumentação privada fica na memória do runner e arquivos temporários
são apagados após cada caso. Artefatos expiram em 3 dias.

O job é **verde só se todos os títulos passarem**. Um caso
`INCONCLUSIVE` não é interpretado como `NO_CHAPTERS` ou como PASS
falso; o lote continua e o workflow termina não aceito.

A ordem serial reduz concorrência, mas um lote de 30 obras pode levar
bastante tempo e esbarrar no limite de seis horas do GitHub Actions.
Começar com 3–5 títulos costuma produzir diagnóstico mais claro.
Não há APK A54, publication de provider, mudança de produção nem uso de
dados privados.

**Validação deste incremento:** testes locais de parser Python e compilação
Android via GitHub Actions devem ser confirmados na PR específica. Os
testes offline verdes da PR #215 não demonstram execução real do novo lote.

Referências: [workflows de páginas](https://github.com/jssantogit/tsuzuki/actions/workflows/mangaall-live-page-android.yml),
[issue #207](https://github.com/jssantogit/tsuzuki/issues/207),
[runbook anterior](mangaall-live-android-page-gate-20261118.md).
