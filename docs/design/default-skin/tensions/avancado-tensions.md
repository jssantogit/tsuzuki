# Avançado — Provisional

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Continuação da Default Skin clara. Esta rodada não consolida `design.md` e não atualiza Notion.

## Contrato funcional

Fontes: checkout local Tsuzuki, `SettingsAdvancedScreen.kt`, `ClearDatabaseScreen.kt` e `NetworkPreferences.kt`. A apresentação preserva os intents Android para notificações e otimização de bateria, o link dontkillmyapp.com, os provedores DNS existentes, validação de header HTTP para User Agent, invalidação do cache de downloads, atualização de capas e redefinição dos flags individuais do leitor.

A limpeza usa grupos com entradas fora da biblioteca, seleção múltipla, selecionar tudo e inverter seleção. A confirmação começa com “Manter obras com capítulos lidos” ativado. Desativar essa proteção apresenta o aviso de perda de capítulos lidos e progresso. Não equivale a apagar o banco inteiro.

## Limites da demonstração

Cinco frames compartilham componentes e têm estados locais independentes. Contagens, nomes e escolhas são fixtures de apresentação, não dados do dispositivo. Não executam intents, jobs, limpeza de cookies, WebView ou banco Android. A confirmação altera somente os dados demonstrativos; a proteção mantém as entradas demonstrativas com leitura.

DNS e User Agent exibem feedback de reinício. A validação local do User Agent espelha os caracteres aceitos pelo header HTTP usado no código: tabulação ou ASCII imprimível, sem quebras de linha. Não adiciona presets ou DNS personalizado.

## Exclusões intencionais

Informações técnicas e Logs permanecem em Diagnóstico. Consentimento P2P e telemetria permanecem em Privacidade e segurança. Providers, Downloads, backup e armazenamento mantêm suas próprias áreas. Installer/trust de Extensions, antiga preferência de títulos correspondentes à Source e onboarding não foram portados. Não há renomeação automática de Source para Provider.

## Microcorreções de Diagnóstico

O hub perde somente o label redundante DIAGNÓSTICO. Installation ID mantém toque para copiar e ação separada de regenerar, ganhando um pequeno ícone de cópia junto ao valor.

Novos tratamentos de Avançado continuam Provisional até aprovação. Referências anteriores permanecem no HTML.
