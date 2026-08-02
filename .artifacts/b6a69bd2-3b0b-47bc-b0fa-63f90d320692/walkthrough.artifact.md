# Atualização da Escala Cromática da Bateria

Concluímos a troca da escala de cores da bateria para alinhar com o novo padrão visual onde o **Azul (Ciano)** representa o nível máximo de eficiência/carga e o **Verde** representa o nível bom/médio.

## Mudanças Realizadas

### 1. Inversão na Lógica de Cores (`DockControls.kt`)
Atualizamos a classe `Battery` para refletir a nova escala:
- **Excelente (> 75%)**: Alterado de Verde para **Ciano (Azul)**.
- **Bom (35% - 75%)**: Alterado de Ciano para **Verde**.
- **Alerta (16% - 34%)**: Mantido como Âmbar.
- **Crítico (<= 15%)**: Mantido como Vermelho (Outline).

### 2. Sincronização no Dashboard (`OverlayService.kt`)
- Atualizamos os estados iniciais do cartão de bateria no dashboard.
- O ícone e a barra de preenchimento agora iniciam em **Verde** (representando o estado "Bom"), aguardando a atualização em tempo real que aplicará a cor correta baseada na carga.

## Como Testar

1.  **Modo Simulação**:
    - No arquivo `VehicleClient.kt`, altere o valor de bateria simulado.
    - Com **85%**, a barra e o ícone devem ficar **Ciano (Azul)**.
    - Com **50%**, a barra e o ícone devem ficar **Verdes**.
    - Com **20%**, devem ficar **Âmbar**.
    - Com **10%**, devem ficar **Vermelhos**.

> [!NOTE]
> Essa mudança traz consistência com os outros indicadores do dashboard (como o de Economia), onde o Azul agora sinaliza o melhor estado possível do sistema.

render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/data/DockControls.kt)
render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/OverlayService.kt)
