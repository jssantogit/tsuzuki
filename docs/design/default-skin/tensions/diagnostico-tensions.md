# Diagnóstico — Default Skin

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Status: Provisional, aguardando aprovação. Continuação da linguagem clara de Configurações. Não consolida design.md nem altera Notion.

## Contrato funcional consultado

Checkout: `repositório Tsuzuki`.

- `SettingsTsuzukiLogsScreen.kt`: estado da captura, iniciar, parar, limpar e compartilhar.
- `DiagnosticCaptureState.kt`: duração padrão de 15 minutos.
- `CrashLogUtil.kt`: relatório `tsuzuki_logs.txt`, compartilhamento Android e limpeza do histórico estruturado, contexto, relatório em cache e crash persistido. Não limpa o Logcat global.
- `DebugInfoScreen.kt`: versão, build, Installation ID copiável/regenerável, Profile Verifier, WebView, modelo, Android e One UI ou MIUI condicional.
- `WorkerInfoScreen.kt`: Running, Enqueued e Finished, incluindo SUCCEEDED/FAILED/CANCELLED neste último; ID, tags, estado, próxima execução e tentativa nos registros enfileirados. Somente leitura e cópia.
- `BackupSchemaScreen.kt`: `ProtoBufSchemaGenerator.generateSchemaText(Backup.serializer().descriptor)`, somente leitura e cópia.

## Composição

Sete frames: Diagnóstico; Logs parado; Logs ativo; confirmação de limpeza; Informações técnicas; Tarefas em segundo plano; Esquema de backup. O hub tem somente dois destinos. Ferramentas ficam no fim das Informações técnicas. Iniciar e Parar são alternativas conforme o estado. Compartilhar permanece disponível em ambos.

A confirmação de limpeza é a apresentação solicitada nesta rodada; o código legado chama a limpeza diretamente. O aviso de privacidade não promete anonimização completa nem exige consentimento extra.

## Fixtures e limites

- A versão-base 0.20.4 é a do checkout, não evidência de versão instalada. Build, Installation ID, WebView, modelo, Android e status Compilado são exemplos. Nenhuma variante One UI/MIUI aparece na fixture genérica; o componente aceita os valores detectados.
- Workers usam registros sintéticos com formato real e tags de exemplo; listas têm tamanho variável, quebra de linha e crescimento natural. Grupo concluído vazio demonstra o estado vazio.
- Schema exibe uma referência de apresentação derivada dos campos reais de Backup, BackupCategory e BackupSource. Não é uma exportação executada pelo gerador Android nem substitui o schema completo gerado pelo aplicativo. Sua origem e alcance são explícitos no próprio conteúdo técnico.
- Captura, timeout, limpeza e regeneração têm estado local independente por aparelho. Não acessam logs, crash, preferências ou jobs reais. Compartilhar informa a transição Android, sem gerar um relatório falso. Copiar usa clipboard do navegador quando disponível, com alternativa manual em caso de falha.
- A geometria, medidas e microinterações permanecem Provisional. Fonte Segoe UI local e tokens claros continuam os artefatos existentes, sem nova decisão global de tipografia.
- Preservadas referências anteriores no HTML, sem edição de conteúdo. Nenhuma ferramenta de Avançado, telemetria, consentimento P2P, configuração de Providers ou opção de verbose logging foi acrescentada.

## Integração Android futura

Ligar as rows aos serviços existentes, preencher dados reais em tempo de execução, preservar resultados reais do Profile Verifier e alimentar a área de schema com o texto integral do gerador. Nenhuma edição ou controle de execução de Workers. Compilação sem perfil, perfil divergente, pendência, ausência de perfil incorporado, API não suportada, erros e código desconhecido mantêm mapeamento fiel ao resultado.
