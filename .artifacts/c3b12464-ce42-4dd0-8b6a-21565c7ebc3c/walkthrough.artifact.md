# Refinamento do Card de Clima Ambiente

Realizamos um ajuste fino no Dashboard para tornar a interação com a recirculação do ar mais intuitiva e o visual mais limpo.

## Mudanças Realizadas

### 🔄 Card como Botão Único
- Transformamos o card de **Clima Ambiente** (que exibe as temperaturas interna e externa) em um único botão clicável.
- Ao clicar em qualquer área do card, a função de **recirculação do ar** é alternada.
- Isso elimina a necessidade de um botão pequeno e específico, aproveitando melhor a área de toque na central multimídia.

### 🎨 Limpeza Visual
- Removemos a marcação retangular (stroke/borda) que existia ao redor do ícone de recirculação.
- O ícone agora fica posicionado livremente no centro do card, mantendo o alinhamento com as temperaturas laterais.
- O ícone continua mudando de cor e forma (Ciano/Aberto/Fechado) para indicar o estado atual.

## Como Testar
1. Abra o Dashboard.
2. Toque em qualquer lugar dentro do card que mostra as temperaturas interna e externa.
3. Observe que o ícone de recirculação no centro alterna o estado e a variável é enviada ao veículo.
