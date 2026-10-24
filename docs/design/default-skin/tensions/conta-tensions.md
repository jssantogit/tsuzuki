# Conta — design system tensions

> Registro de elaboração preservado. Os estados Provisional/TBD abaixo descrevem a rodada original. O fechamento visual de Settings e as decisões que a substituem estão na [especificação vigente](../design.md). Medidas Android, execução real e limites expressamente pendentes continuam sem certificação. Aquisição global e Folder Catalog não são referências aprovadas.


Esta validação propõe a superfície **Configurações → Conta** em dois estados da mesma tela. `design.md` e as demais referências aprovadas permanecem como fonte visual.

## Approved

- **Uma família, quatro estados relacionados:** Entrar, Criar conta, Redefinir senha e Conta conectada compartilham a superfície Conta. Não são quatro arquiteturas independentes.
- **Formulário relacional:** labels persistentes para E-mail e Senha nos estados pertinentes. Entrar oferece Entrar, Criar conta e Redefinir senha; cadastro oferece Criar conta e voltar para Entrar; redefinição contém somente E-mail, Enviar instruções e Voltar ao login, sem Senha.
- **Overview conectado:** exibe e-mail como identificador, sem avatar ou username, status operacional breve e última sincronização quando disponível. E-mail pode quebrar integralmente. Sincronizar agora é ação manual com feedback acessível e transitório.
- **Ação destrutiva:** Sair fica separada e exige confirmação; fechar/cancelar retorna o foco à ação Sair e confirmar retorna ao cabeçalho.
- **Destino operacional:** a linha de status representa navegação futura para **Conta → Sincronização**. Nesta rodada não há subpágina nem painel de detalhes operacionais.
- Estes pontos aprovam relações e composição demonstradas, não implementação real nem promoção de medidas e styling a regras globais.

## Provisional

| Decisão provisória | Alcance |
|---|---|
| Medidas observadas dos campos, alvos, espaçamentos, foco visível, mensagens de erro junto ao campo e feedback de formulário. | Anatomia local da composição; escala CSS não define dp/sp nem comportamento Android. |
| Styling exato das ações primária e textuais, confirmação de saída, cores, raios, tipografia e quebras. | Conta; não generalizar como tokens ou componentes globais. |
| Forma, texto e duração exatos do feedback transitório de sincronização e da mensagem de status. | Protótipo local; ajustar com produto e acessibilidade. |
## TBD

- Semântica e estados funcionais de sync (TBD): habilitação, mutações pendentes, conflitos, última sincronização bem-sucedida, diagnóstico e resolução. A composição não define as relações entre eles.
- Disponibilidade, duração e resultado de login, criação de conta, recuperação de senha e sincronização real.
- Formatação de data/hora para a última sincronização e conteúdo dos detalhes de diagnóstico.
- Comportamentos reais de autenticação, criação de conta, recuperação e sincronização; o protótipo demonstra somente transições locais. Valores iniciais do overview (“usuario@example.com” e “Hoje, 09:41”) são amostras explicitamente documentadas aqui, fora da UI; após sair, o formulário corrigido continua disponível.
- Comportamento Android com teclado nativo, autofill e escalonamento do sistema precisa de validação no produto.

## Inventário preservado para sync

Sync habilitado; mutações pendentes; conflitos não resolvidos; última sincronização bem-sucedida; diagnóstico; resolução de conflitos. Esta rodada mostra apenas um resumo no overview e não implementa diagnóstico ou resolução.

## Limites do protótipo

Entrar, criar conta, redefinir senha, sincronizar e sair são transições locais para demonstrar os dois estados. Não há autenticação, envio de e-mail nem sincronização real. Senha fica somente no campo durante a interação; não é persistida nem registrada em console. O e-mail conectado é o digitado no formulário demonstrativo.
