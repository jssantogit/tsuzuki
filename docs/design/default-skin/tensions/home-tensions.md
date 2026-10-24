# Home: tensões de design

## Contrato definido nesta rodada

Proposal 5, transição profunda. Hero aberto e opcional, artwork desde o topo, conteúdo na zona de fade, sem poster em foreground. Sem título Início ou busca no topo. Navegação Início / Busca / Biblioteca / Perfil. Continue lendo opcional e único, com cards horizontais próprios de progresso e retomada direta. Collections dinâmicas, seção por Collection e navegação de pastas 16:9 sem capas ou contagens. Módulos ausentes recolhem todo seu espaço.

O refinamento visual é reapresentado em onze frames, sem implementação Android. Medidas locais e fixture data não são valores obrigatórios de runtime. Não fechar visualmente antes de revisão humana.

## Tensões verdadeiras

- A política final de sourcing e seleção dos até quatro destaques de Hero depende do produto/runtime. O board não cria descoberta obrigatória nem recomendações artificiais para suprir ausência.
- Exposição direta de Lists e a ordenação destas com seções pertencentes ao usuário permanecem trabalho futuro. A invariant visual já está definida: progresso, catálogo e navegação de pastas são famílias distinguíveis.
- Artwork e title-logo confiáveis precisam de política de aquisição/disponibilidade de runtime. O board usa assets oficiais localizados e fallback textual; não fabrica logo nem define Provider de artwork.

## Fixtures e entrega

One Piece, Fullmetal Alchemist, Witch Hat Atelier e The 100 Girlfriends Who Really, Really, Really, Really, Really Love You são fixtures reais de obras. Progresso e Collections são demonstrações locais. Cards de retomada não expõem Provider, rating ou status editorial. A navegação de destino é local, preservando o contrato canônico conceitual, e não prova execução de Reader real.

Entradas: index.html no projeto OpenDesign e docs/design/default-skin/mockups/index.html no repositório. Módulos isolados assets/home-main.js e assets/home-main.css. Boards anteriores conservados. Sem alterações Android, runtime, Providers ou Settings.

## Polish final — decisões consolidadas

- Continue lendo mostra capítulo, “pág. atual / total” quando conhecida e indicador proporcional, com geometria comum entre os cards. Não é uma rail de catálogo.
- Título longo tem escala responsiva e teto visual de três linhas; o nome completo continua acessível em Detalhes.
- Folder usa representação editorial abstrata, sem ícone de file manager, sem artwork de obra e sem mudar navegação.
- Fade profundo usa perfis de artwork e uma zona textual protegida, chegando à cor exata da Home. A escolha do perfil e da posição focal no runtime permanece decisão de implementação; o board não demonstra análise automática da imagem.
- Os onze frames e estados esparsos permanecem, sem conteúdo de preenchimento. O refinamento não fecha aprovação visual por conta própria.

As tensões de sourcing do Hero, artwork oficial e exposição futura de Lists acima continuam válidas. Nenhuma delas autoriza implementar Android nesta rodada.


### Continue lendo — correção do componente horizontal

A apresentação anterior com mini posters verticais está superseded. Continue lendo usa uma rail horizontal de cards horizontais: capa pequena inteira à esquerda; título, capítulo atual, página atual / total quando conhecido e barra de progresso à direita. As barras compartilham geometria e marcador proporcional à posição de leitura. O card inteiro retoma diretamente a leitura, sem botões internos nem passagem por Details. Não inclui ratings, gêneros, status editorial ou Provider. Esta família de retomada permanece distinta do catálogo e das pastas 16:9. O componente compartilhado atualiza todos os frames em que aparece, sem alterar Hero, Collections, navegação ou estados vazios. Progresso do mockup é demonstrativo; Android permanece fora do escopo.
