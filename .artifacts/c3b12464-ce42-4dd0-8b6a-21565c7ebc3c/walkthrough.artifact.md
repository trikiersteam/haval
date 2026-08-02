# Dashboard Remodelado — Estilo HMI Clima

O Dashboard foi completamente remodelado para seguir o design extraído do Lovable, adotando uma estética automotiva moderna e profissional.

## Mudanças Principais

### 🎨 Nova Identidade Visual
- **Paleta de Cores**: Adotamos os tokens exatos do Handoff:
    - Fundo: `#091017`
    - Superfície: `#171F28`
    - Cyan: `#36CAF1` (Ativo/Frio)
    - Laranja: `#FE8F5B` (Quente/Interna)
    - Verde: `#52E492` (Bateria/HEV)
- **Raios de Borda**: Cartões agora possuem cantos mais arredondados (28dp) e bordas sutis (`OUTLINE`).

### 📐 Grade de 12 Colunas
- O layout foi reestruturado em uma grade de 3 grandes seções (Motorista, Veículo, Passageiro), simulando o comportamento de colunas do design original.
- Mantivemos o recuo de **1 polegada (160px)** à esquerda para acesso rápido ao sistema original.

### 🌡️ Controle de Temperatura Aprimorado
- **Botões +/-**: Adicionados botões circulares para ajuste rápido.
- **Barra de Gradiente**: O slider agora exibe um gradiente de Ciano para Laranja, representando a faixa térmica de forma visual.

### 🔋 Nova Coluna Central (Veículo)
- **Bateria**: Novo card com barra de progresso horizontal verde e percentual dinâmico.
- **Modos de Condução**: Seleção por tiles (`HEV`, `Prioridade EV`, `EV`) com feedback visual de seleção (bordas verdes).
- **Recirculação**: Card com ícone destacado e texto de status.
- **Clima Ambiente**: Exibição compacta de temperatura interna e externa com ícones coloridos.

### 🕒 Header com Informações do Sistema
- Adicionado um cabeçalho superior contendo a marca "CLIMA", a autonomia atual do veículo e o relógio do sistema sincronizado.

## Verificação
As mudanças foram validadas para a resolução de 1792x720px, garantindo que os novos componentes escalem corretamente sem sobreposição.
