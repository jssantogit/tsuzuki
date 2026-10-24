# Dados e armazenamento — tensões de design

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


## Escopo e autoridade

As composições de hub Dados e armazenamento, Uso do armazenamento, Backup e restauração, Frequência e Exportar biblioteca são preservadas como referências aprovadas nesta conversa. Esta continuação apresenta cinco frames: Local de armazenamento, Criar backup, Restaurar backup normal, Restaurar backup com componentes ausentes e Exportar biblioteca com CTA corrigido. Novas relações visuais permanecem Provisional. design.md e Notion não são alterados; as referências anteriores continuam acessíveis por retorno e navegação.

## Fontes funcionais

- SettingsDataScreen.kt, no checkout Tsuzuki: OpenDocumentTree para pasta, StorageInfo, limpeza de cache, autoClearChapterCache, criação/restauração de backup, frequências 0/6/12/24/48/168 horas e exportação CSV com Título/Autor/Artista.
- BackupPreferences.kt: preferências de backup existentes. CreateBackupScreen.kt/BackupOptions.kt e RestoreBackupScreen.kt/RestoreOptions.kt confirmam os fluxos e o inventário descritos abaixo; migração desse conteúdo a Providers continua TBD. Não há promessa de cobertura da Provider Platform.
- Exportação: desligar Título desmarca Autor/Artista e desabilita essas opções, conforme o código atual. O protótipo não inventa preservação dessas duas escolhas quando Título é desligado.
- Arquivos temporários de aquisição: localização visual autorizada nesta rodada, responsabilidade do Host. Não pertence a Nyaa ou a configuração global de rotas.

## Fixtures

Pasta do Tsuzuki com caminho Armazenamento interno › Tsuzuki; 74 GB disponíveis de 128 GB; 54 GB usados calculados pela diferença; cache 84 MB; frequência 24 horas e último backup há 2 horas. Valores demonstrativos, sem dados reais do dispositivo. Cache automático começa ligado. Título, Autor e Artista começam selecionados. Backup da biblioteca é um rótulo visual de arquivo, não um arquivo existente ou nome gerado pelo backend. MangaFire e Kitsu são exemplos de componentes ausentes, não diagnóstico de instalação real.

## Contrato funcional confirmado de backup

- BIBLIOTECA na criação: Mangás, Capítulos, Tracking, Histórico, Categorias e Dados de leitura fora da biblioteca.
- CONFIGURAÇÕES: Configurações do app, Repositórios de extensões, Configurações de fontes e Configurações privadas. Os labels herdados foram solicitados expressamente nesta rodada para preservar o inventário legado; não viram uma taxonomia nova da plataforma de Providers.
- Capítulos, Tracking, Histórico e leitura fora da biblioteca dependem de Mangás. Configurações privadas depende de Configurações do app OU Configurações de fontes. Desligar um parent não apaga escolhas dos dependentes, conforme os setters atuais.
- canCreate exige pelo menos Mangás, Categorias, Configurações do app, Repositórios de extensões ou Configurações de fontes. Somente escolhas subordinadas não habilitam a criação.
- CreateDocument application/* recebe BackupCreator.getFilename(); então BackupCreateJob inicia. Não há campo de nome ou destino próprio na referência.
- Restauração escolhe arquivo primeiro e valida por BackupFileValidator. RestoreOptions possui Biblioteca, Categorias, Configurações do app, Repositórios de extensões e Configurações de fontes, sem dependências entre elas. canRestore exige pelo menos uma selecionada e validação permissiva.
- MissingRestoreComponents mantém canRestore=true; InvalidRestore mantém canRestore=false. O aviso inline não substitui a tela inteira nem oferece mesclar/substituir.
- Defaults da fixture seguem BackupOptions: opções ligadas, Configurações privadas desligada; RestoreOptions começa com cinco opções ligadas.
- Aviso MIUI incompatível permanece documentado, sem frame adicional nesta rodada.

## Reparo de layout e estados

O container od-screen impunha height:100% sobre um aplicativo posicionado entre top/bottom safe insets. A altura ultrapassava o espaço útil e cortava o CTA. A regra local ds-app passa a height:auto; top/bottom determinam a altura, e o grid reserva header, scroll e footer. Exportar CSV, Criar backup e Restaurar backup usam a mesma região inferior. Insets finais Android continuam Provisional.

Local de armazenamento suporta path ausente com Nenhuma pasta selecionada e Escolher pasta. A ação usa o picker nativo do navegador quando disponível; em Android, o contrato real é OpenDocumentTree, grants de leitura/escrita e persistência do URI. Não há gerenciamento próprio de volumes ou movimentação de arquivos.

As seleções funcionam localmente. Arquivos escolhidos pelo browser não são declarados válidos: sem BackupFileValidator disponível, a UI bloqueia restauração e informa que o arquivo não pôde ser validado neste ambiente. Frames normal/ausentes são fixtures previamente definidas. Criar, restaurar e exportar anunciam a transição para operações reais sem gerar arquivos ou fingir conclusão.

## Design system tensions

| Regra existente | Problema | Proposta | Status |
|---|---|---|---|
| Hub híbrido de Settings | Armazenamento, backup e exportação têm densidades diferentes | Hub com três grupos e subpáginas próprias | Provisional |
| Grouped surfaces | Uso do dispositivo precisa de leitura rápida | Barra simples proporcional, local e disponibilidade, sem dashboard | Provisional |
| Ações operacionais discretas | Limpar não é uma preferência | Ação textual separada de switches | Provisional |
| Dependências visíveis | Autor/Artista dependem de Título | Controles indisponíveis quando Título é desligado | Provisional para visual |
| CTA reservado | Exportação possui ação dominante | Footer próprio fora da rolagem | Provisional |

## TBD e limites

- A correspondência de repositórios de extensões, configurações de fontes e configurações privadas com a Provider Platform ainda exige migração; a composição preserva somente o contrato legado atual.
- Nome final de arquivo CSV será definido na implementação Tsuzuki; não reproduzir mihon_library.csv.
- Operações Android de backup, restauração, exportação e limpeza são destinos demonstrados, sem executar arquivos reais. Pasta usa seletor nativo do navegador quando disponível; nunca há picker desenhado pelo Tsuzuki.
- Confirmação operacional de limpar temporários e possível encerramento de transferências continua futura.
- Consentimento P2P continua fora desta família, a definir em Privacidade e segurança; atividade P2P continua fora desta rodada. Não há seleção de rotas.
- Medidas, insets, foco, confirmação operacional e múltiplos volumes Android permanecem Provisional/TBD.
- Nenhum help link Mihon é reproduzido. Não há fila, logs, Provider settings ou controles de Downloads nesta família.

## Microcorreções da rodada Privacidade e segurança

- Tracking permanece terminologia pública, inclusive na criação de backup.
- Removida a frase redundante sobre o seletor do sistema em Local de armazenamento. Alterar local mantém o destino nativo, sem picker próprio.
- Componentes ausentes usa duas listas independentes, fontes e serviços de Tracking. Grupos vazios são omitidos; cada nome é escapado e pode quebrar linha. Listas e aviso crescem em fluxo normal, sem altura fixa ou limite de dois itens. MangaFire e Kitsu continuam fixtures.
- Consentimento P2P tem destino visual definido nesta rodada: Privacidade e segurança → Privacidade de rede → P2P direto. A indicação anterior de localização pendente é histórica; atividade P2P continua fora desta família.
- As composições restantes de Dados e armazenamento foram preservadas. Medidas e integração Android continuam com seus limites anteriores.
