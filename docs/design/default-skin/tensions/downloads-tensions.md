# Downloads — tensões de design

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Escopo

Proposta Provisional: Downloads root, Downloads automáticos, Limpeza automática e sheet Categorias. Default Skin clara; referências anteriores preservadas. design.md e Notion não são alterados.

## Evidência funcional

- Fonte: SettingsDownloadScreen.kt e DownloadPreferences.kt no checkout Tsuzuki. Recursos pt-rBR confirmam a descrição de divisão de imagens: “Melhora o desempenho do leitor”.
- Wi-Fi, CBZ e divisão são preferências independentes. Paralelismo por fonte: 1–10; páginas por fonte: 1–15. Microcorreção aprovada: Fontes simultâneas substitui o antigo label Downloads simultâneos, mantendo valor 5, range 1–10 e slider.
- Baixar enquanto lê aceita desativado e próximos 2, 3, 5 ou 10 capítulos não lidos.
- Downloads automáticos possui parent, somente não lidos e categorias incluídas/excluídas. Dependentes ficam indisponíveis com parent desligado, sem perda dos valores.
- Categorias excluídas prevalecem sobre incluídas no runtime. UI tri-state impede que a mesma categoria esteja nos dois conjuntos.
- Limpeza possui remoção ao marcar como lido, remoção após leitura com seis valores existentes, proteção de marcados e categorias excluídas. Microcorreção aprovada: o summary usa exatamente o label da opção selecionada, incluindo Último capítulo lido. Os demais labels conservam penúltimo/terceiro/quarto/quinto anteriores.

## Fixtures

As quatro frames têm estados locais independentes para validar a gramática: a raiz começa com automações desativadas; a subpágina automática começa ligada. A sheet demonstra Lendo e Favoritos incluídos, Arquivados excluído e Planejados neutro. O resumo de sua frame de fundo corresponde à seleção aplicada nela. As categorias não são dados reais do usuário.

## Design system tensions

| Regra atual | Problema | Solução proposta | Alcance | Status |
|---|---|---|---|---|
| Hub híbrido aprovado | Downloads reúne preferências imediatas e regras especializadas | Rede/arquivos e sliders na raiz; automação e limpeza em drill-down | Downloads | Provisional |
| Sliders com anatomia provisória | Dois limites diferentes precisam permanecer claros | Valor destacado, escala real, label persistente e controle por teclado | Performance Settings | Provisional |
| Dependências visíveis aprovadas | Automação desligada não pode apagar a seleção | Controles disabled com valores visíveis e indicação da dependência | Downloads automáticos | Provisional para styling |
| Include/exclude aprovado para lookup | Categorias são locais, sem necessidade de busca remota | Mesma row tri-state e footer Limpar/Aplicar; edição separada do estado aplicado | Categorias locais | Provisional |
| Multi-select comum aprovado | Exceções de limpeza não possuem inclusão/exclusão | Lista de escolhas com Cancelar/Confirmar | Limpeza | Provisional para anatomia |

## TBD e limites

- Persistência Android, execução e fila real não são demonstradas. Nenhuma operação de download ou remoção acontece.
- Medidas/insets, sliders, contraste exato de disabled, foco e sheets permanecem Provisional.
- Categorias vazias e textos finais em listas extensas necessitam validação futura.
- Armazenamento, cache, backups, arquivos/atividade/consentimento P2P e permissões ficam fora desta família.
- Retorno a Configurações nesta prancha anuncia o destino existente; não compõe uma quinta frame.


## Microcorreções finais autorizadas

A supporting copy de Remover capítulos marcados já acompanha o switch e foi preservada: desligado, Capítulos marcados permanecem protegidos.; ligado, Capítulos marcados também podem ser removidos. Essas correções não redesenham Downloads nem promovem outras medidas a Approved.
