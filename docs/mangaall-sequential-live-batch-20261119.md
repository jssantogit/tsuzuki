# MangaAll — gate Android unificado (3 fontes, code8 assinado)

## Escopo corrigido

**MangaAll é um único Provider (app.tsuzuki.mangaall)**. O router original assinado consulta MangaDex, MangaDot e MangaFire juntos, devolvendo identidades distintas por fonte. O desenho antigo de escolher uma fonte por workflow não representava o comportamento real e foi substituído.

O novo lote recebe **uma lista ordenada de 1–30 títulos** e um modo reader/transport. Para cada título chama o Host MangaAll uma vez por página de cursor, inspecionando a união das três fontes. O teste mantém a faceta inglesa (en), a única anunciada pelos três adaptadores code8; outro idioma não pode ser apresentado como auditoria de três fontes.

## Entrada

No GitHub Actions, abrir o workflow MangaAll signed code8 — unified three-source Android live manga batch, selecionar Run workflow e informar os títulos, por exemplo:

~~~json
["Death Note", "Naruto", "Bleach"]
~~~

Escolher reader para testar pixels reais no ReaderActivity, ou transport para download/decodificação Android, sem evidência visual. **Não existe seletor de fonte.**

Para iniciar pelo commit, criar branch mangaall-live/minha-rodada a partir da main já atualizada e modificar o manifesto .github/mangaall-live-batch.json:

~~~json
{
  "mode": "reader",
  "language": "en",
  "titles": ["Death Note", "Naruto", "Bleach"]
}
~~~

Um push que modifica especificamente esse manifesto numa branch mangaall-live/** aciona o workflow. Commits normais na main e PRs não geram tráfego live.

## Inspeção conjunta, não picks arbitrários

1. Uma chamada ao gateway original do MangaAll faz fan-out às três fontes; consumimos até oito páginas de lookup com cursor e exigimos conclusão real. Um resultado incompleto não vira ausência de obra.
2. Os candidatos são separados por prefixo de identidade. Cada fonte registra MISSING, AMBIGUOUS (dois ou mais IDs distintos), ou um candidato único. Se a mesma identidade mudar título entre páginas, ou a faceta entrar em conflito, o auditor encerra como inconclusivo.
3. Candidatos únicos são investigados com chapters() da sua fonte e seu binding exato, até doze páginas. Só um capítulo 1 único, sem volume, habilita a prova de páginas desse binding.
4. O auditor registra consistência/variação de títulos principais e autoria disponíveis, sem divulgar nomes retornados ou IDs da fonte. VARIANCE é sinal de análise editorial pendente; UNVERIFIED significa metadado insuficiente.
5. **Uma fonte ambígua não bloqueia as outras duas.** Cada binding READY é testado no Reader/transport, em ordem, enquanto fontes ambíguas permanecem inconclusivas. Não há autoescolha de edition, ranking, HID nem capítulo estrangeiro.
6. O relatório JSON final registra três linhas por título (fonte, contagem de candidatos, estágio de descoberta, resultado do Reader e motivo), além da comparação de metadados. Um título só passa o gate técnico completo se as três fontes passarem sem variação de metadados identificada. Caso contrário fica INCONCLUSIVE ou FAIL, mas os outros títulos continuam.

## Limites e segurança

- O lookup, inventário de candidatos e diagnóstico de conflitos são **integrados em um Provider MangaAll**. A prova de pixels, por enquanto, roda sequencialmente em fixtures Reader descartáveis para cada binding elegível; isso ainda NÃO comprova três opções coexistindo numa mesma sessão visual da biblioteca real. Um teste específico de paridade no mesmo Reader fica pendente.
- A correspondência textual de títulos/aliases e três imagens carregadas não atestam que são a mesma edição editorial. O arquivo de resultado registra editorialIdentityVerified=false.
- MangaDex permanece development-only no pacote code8, sujeito aos seus gates próprios de atribuição, remoção e imagens; este teste não equivale à sua publicação.
- Não exportamos URLs, headers, cookies, IDs observados, respostas remotas, screenshots nem bytes de mangá aos artefatos CI. Somente títulos declarados pelo operador e códigos de diagnóstico fechados.
- Ausência de título/capítulo só é afirmada se inventário correspondente foi concluído; timeouts, challenges e cursores limitados são inconclusivos.
- Nenhuma alteração do provider publicado, app de produção ou instalação no Galaxy A54.

O run antigo #44521594314 mostrou AMBIGUOUS_SOURCE_MATCH para os três títulos **dentro de MangaFire somente** e não deve ser usado para concluir nada sobre conflitos entre as fontes juntas. Link: https://github.com/jssantogit/tsuzuki/actions/runs/44521594314

Issue de acompanhamento: https://github.com/jssantogit/tsuzuki/issues/207
