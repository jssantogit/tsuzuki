# Issue #154 — Independent Chapter Inventory & Evidence Quality

**Data da investigação:** 24/10/2026

**Estado:** Relatórios A, B e C publicados; decisão arquitetural pendente de aprovação do usuário.

**A recomendação continua sendo a arquitetura A: inventário local de evidências, independente da resolução de leitura.** O Relatório C acrescenta consultas reais: MU e MangaDex justificam adapters de observações de releases; Kitsu exige cautela com registros derivados e Bangumi não enumerou capítulos nos livros amostrados. A transição deve preservar IDs legados e estado do usuário. Isso permanece uma proposta para aprovação.

A validação da Orchestra terminou **PARTIAL**: identidade `1.5.0-rc.1` e integridade estática foram confirmadas, mas os hooks locais não apareceram no `/hooks` observado e nenhum Work Lease foi criado. Isso não bloqueou a investigação.

## Documentos

| Documento | Finalidade | Resultado |
|---|---|---|
| [Relatório A — Investigação arquitetural](architecture-research.md) | Auditoria do fluxo, integrações, qualidade das evidências, exatamente duas arquiteturas, comparação e plano incremental | Arquitetura A recomendada; nenhuma implementação autorizada |
| [Relatório B — Orchestra 1.5.0 Field Validation](orchestra-field-validation.md) | Instalação, identidade, Doctor, descoberta nativa de hooks, Work Lease e preservação do ambiente | PARTIAL; ativação nativa não comprovada |
| [Relatório C — Validação empírica e viabilidade arquitetural](empirical-validation.md) | Corpus, consultas reais, semântica de inventários, IDs legados, experimento SQLite, comparação atualizada e transição | A mantida com condições; nenhuma implementação |
| [Evidências e reprodução do C](evidence-c/README.md) | 149 recibos HTTP, 48 primeiras páginas, 14 followups, corpus e scripts descartáveis | Sem páginas de leitura ou dados de usuário; limites de validação explícitos |

## Escopo e referências

- [Issue #154](https://github.com/jssantogit/tsuzuki/issues/154): especificação principal e contrato de aceitação.
- Tsuzuki investigado: [b0417cdf232e220fe8f7f89f3e156d2d26765883](https://github.com/jssantogit/tsuzuki/commit/b0417cdf232e220fe8f7f89f3e156d2d26765883), confirmado como main no início e no encerramento da pesquisa.
- Tree investigada: a032cbef0149cf62f340063da845ad75d6fc27f3.
- Orchestra: [PR #78](https://github.com/jssantogit/Orchestra/pull/78), candidata fixada em [3666478f7495abce547013507a3366c1fa003212](https://github.com/jssantogit/Orchestra/commit/3666478f7495abce547013507a3366c1fa003212), versão 1.5.0-rc.1.
- As evidências da Orchestra são independentes da recomendação arquitetural. HEALTHY estático não prova hooks ativos.

## Como interpretar a publicação

Os Relatórios A e B preservam integralmente as duas seções originais entregues na conversa, acrescidas apenas de uma nota temporal e navegação. O Relatório C é uma segunda etapa, com novos experimentos explicitamente identificados. As conclusões são propostas para decisão, não um ADR aprovado. A e B não foram reescritos com os resultados do C.

Os blocos finais que registram COMMITS: NONE, PRS: NONE e ausência de alterações descrevem a etapa original de pesquisa. O pedido posterior do usuário autoriza esta publicação documental; não autoriza implementação, migração, ativação de progresso/backup, aprovação da Orchestra ou encerramento da issue.

A validação documental do A não representa execução dos serviços. O C distingue respostas HTTP reais, experimentos SQL reduzidos e análise estática. Nenhum deles certifica cobertura editorial universal, uptime ou comportamento Android. Testes de produto revisados não foram executados. Consulte as limitações e questões em aberto antes de implementar.
