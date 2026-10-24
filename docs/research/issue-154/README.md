# Issue #154 — Independent Chapter Inventory & Evidence Quality

**Data da investigação:** 24/10/2026

**Estado:** pesquisa concluída; decisão arquitetural pendente de aprovação do usuário.

**Recomendo a arquitetura A: inventário local de evidências, independente da resolução de leitura, evoluindo as fronteiras que o Tsuzuki já possui.** A investigação confirmou oportunidades concretas de reaproveitamento; não é necessário reconstruir o Chapter Engine.

A validação da Orchestra terminou **PARTIAL**: identidade `1.5.0-rc.1` e integridade estática foram confirmadas, mas os hooks locais não apareceram no `/hooks` observado e nenhum Work Lease foi criado. Isso não bloqueou a investigação.

## Documentos

| Documento | Finalidade | Resultado |
|---|---|---|
| [Relatório A — Investigação arquitetural](architecture-research.md) | Auditoria do fluxo, integrações, qualidade das evidências, exatamente duas arquiteturas, comparação e plano incremental | Arquitetura A recomendada; nenhuma implementação autorizada |
| [Relatório B — Orchestra 1.5.0 Field Validation](orchestra-field-validation.md) | Instalação, identidade, Doctor, descoberta nativa de hooks, Work Lease e preservação do ambiente | PARTIAL; ativação nativa não comprovada |

## Escopo e referências

- [Issue #154](https://github.com/jssantogit/tsuzuki/issues/154): especificação principal e contrato de aceitação.
- Tsuzuki investigado: [b0417cdf232e220fe8f7f89f3e156d2d26765883](https://github.com/jssantogit/tsuzuki/commit/b0417cdf232e220fe8f7f89f3e156d2d26765883), confirmado como main no início e no encerramento da pesquisa.
- Tree investigada: a032cbef0149cf62f340063da845ad75d6fc27f3.
- Orchestra: [PR #78](https://github.com/jssantogit/Orchestra/pull/78), candidata fixada em [3666478f7495abce547013507a3366c1fa003212](https://github.com/jssantogit/Orchestra/commit/3666478f7495abce547013507a3366c1fa003212), versão 1.5.0-rc.1.
- As evidências da Orchestra são independentes da recomendação arquitetural. HEALTHY estático não prova hooks ativos.

## Como interpretar a publicação

Os relatórios preservam integralmente as duas seções entregues na conversa, acrescidas apenas de uma nota temporal e navegação. As conclusões são uma proposta para decisão, não um ADR aprovado. Não houve nova execução dos experimentos durante a publicação.

Os blocos finais que registram COMMITS: NONE, PRS: NONE e ausência de alterações descrevem a etapa original de pesquisa. O pedido posterior do usuário autoriza esta publicação documental; não autoriza implementação, migração, ativação de progresso/backup, aprovação da Orchestra ou encerramento da issue.

A validação das integrações registra capacidades e limites documentados. Não representa certificação de cobertura, uptime, todos os termos de uso ou execução dos serviços. Testes revisados não foram executados durante a pesquisa. Consulte as limitações e questões em aberto antes de implementar.
