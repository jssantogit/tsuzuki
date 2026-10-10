# Entry Flow / primeiro acessó - tensoes

## Decisoes consolidadas

- Proposal 1 logo-centric: lockup oficial, mensagem curta, Criar conta / Entrar / Continuar como visitante.
- Welcome somente na primeira instalação; visitante e caminho válido. Nao reapresentar a entrada em cada abertura nem depois de sair da conta.
- Welcome sem chrome; formularios com retorno, sem bottom navigation. Mesma família visual de Conta.
- Cadastro com email e senha; recuperação somente com email. Requisitos adicionais não são inventados pelo mockup.
- Navegação conectada e estados locais demonstrativos; nenhum servico real acionado.

## Tensoes reais para implementação posterior

1. **Persistencia de primeiro acesso:** escolher o mecanismo de conclusão e reconciliar restauração/reinstalação com a inicialização real. Nao vincular a conclusão exclusivamente a uma sessão autenticada.
2. **Confirmação e recuperação:** integrar respostas reais, links de email e retorno ao app sem prometer envio ou autenticação antes da resposta do servico. Preservar mensagens de erro acessíveis e recuperaveis; politica de senha segue o backend.

## Estado de revisão e evidencia

Entry Flow pendente de revisão visual humana. Cinco frames em index.html e assets/entry-flow.js/css. Marca oficial local; nenhuma marca sintetizada. Login, cadastro e envio de recuperação são simulados. Nenhum Android, runtime de auth ou Settings alterado. Os boards anteriores permanecem preservados.

## Redefinição de senha

- Frame dedicado após abertura do link de recuperação: Nova senha, Confirmar nova senha, Salvar nova senha e confirmação com retorno a Entrar.
- Divergência entre os campos aparece junto à confirmação; o formulário preserva os valores para correção.
- Tensão de integração já existente: validar o link/token e tratar expiração ou uso prévio sem prometer redefinição antes da resposta real do serviço. Política de senha segue o backend.
- Salvamento e sucesso são simulações locais. Sem persistência de senha ou implementação Android. Revisão visual permanece pendente.