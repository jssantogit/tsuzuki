# Evidências do Relatório C

Consultas anônimas de metadados em 24/10/2026; nenhuma página de leitura, imagem,
torrent ou conta de usuário foi acessada. Este diretório é documentação de pesquisa,
não um adapter, teste de CI ou parte do produto.

| Arquivo | Conteúdo |
|---|---|
| `corpus.json` | Escopos e IDs selecionados manualmente; associação investigativa, não crosswalk editorial certificado |
| `inventory-results.json` | Primeira página por escopo/fonte, com projeção factual e recibo HTTP |
| `followups.json` | Paginação, caudas Kitsu, detalhes e relações Bangumi |
| `requests.jsonl` | Todas as tentativas HTTP do experimento: inclui falhas e consultas excluídas |
| `excluded-sample.json` | Projeção da consulta com ID MU transcrito incorretamente; excluída do corpus válido |
| `selected-metadata.json` | Projeção dos candidatos selecionados encontrados nos caches de busca/detalhes |
| `summary.json` | Estatísticas calculadas por `analyze.py` |
| `legacy-sql-results.json` | Experimento SQLite em memória com DDL real e pais mínimos |
| `probe.py` | Harness descartável original, com cache, allowlist, orçamento e pausa entre consultas |
| `replay.py` | Reexecuta somente recibos de inventário e followups válidos; rede exige opt-in |
| `analyze.py` | Verificação offline de relações, amostras, erros de identidade e estatísticas |
| `legacy_sql_probe.py` | Reproduz o experimento em `:memory:`, sem abrir banco do produto |

Verificações offline, a partir da raiz do repositório:

```powershell
python -X utf8 -B docs/research/issue-154/evidence-c/analyze.py
python -X utf8 -B docs/research/issue-154/evidence-c/legacy_sql_probe.py --repo .
```

Reexecução externa opcional, deliberadamente **não executada na publicação**:

```powershell
python -X utf8 -B docs/research/issue-154/evidence-c/replay.py --live --cache-dir "$env:TEMP/tsuzuki-154-replay"
```

O harness limita tentativas a 160 no total/45 por fonte, espera pelo menos 1,1s por
host, usa timeout de 20s e para a fonte após 403/429. Só permite POST em endpoints
de busca. Não possui credenciais nem caminhos para conteúdo de leitura. O cache
externo pode conter respostas completas; não publique esse cache sem nova revisão.
O `probe.py` isolado usa o próprio diretório como cache; use `replay.py` para manter
os arquivos operacionais fora do repositório.

Os hashes SHA-256 identificam os bytes originais locais; **não** permitem recuperar
ou verificar integralmente os corpos brutos a partir destas projeções. Corpos brutos
foram preservados apenas no laboratório temporário local. Os recibos/projeções são
evidência auditável da execução, não uma assinatura independente do serviço.
Mudanças de dados, hora, ordenação e paginação podem mudar os hashes numa reexecução.

Correções do experimento:

- As dez primeiras consultas Kitsu receberam 406 por `Accept: application/json`.
  A troca por `application/vnd.api+json` corrigiu a negociação. O cache passou a
  incorporar `Accept`, causando repetição acidental das outras 30 buscas; os
  recibos incluem esse custo. Não foi um ensaio planejado de repetibilidade.
- O ID MU de Re:Zero mangá arco 1 foi transcrito incorretamente como `11057030324`.
  A API devolveu 200 com 10.000 resultados e séries alheias. A consulta foi excluída
  das amostras válidas e substituída por `11106634916`, conversão base36 do link
  publicado `53olqck`. O recibo errado permanece como registro da correção.
  `analyze.py` exige igualdade de `seriesId` em todos os releases MU válidos.
- Busca MD inicial safe/suggestive não localizou Berserk; a consulta que incluiu
  erotica resolveu o caso. Não houve acesso a imagens/conteúdo. Busca textual
  não é prova de equivalência de edições, adaptação ou idioma.

Nenhum resultado desta pasta comprova execução Android, migração Kotlin,
comportamento do Reader, sync remoto, estabilidade longitudinal ou completude
editorial. Consulte os limites e decisões pendentes no Relatório C.
