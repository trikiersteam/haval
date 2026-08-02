# Monitoramento de Economia e Consumo (Versão Simplificada)

Este plano descreve a implementação simplificada dos indicadores de eficiência e consumo da viagem no Dashboard, utilizando componentes nativos (Views) para garantir máxima estabilidade e baixo consumo de recursos.

## Proposed Changes

### [UI Components]

#### [MODIFY] [OverlayService.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/OverlayService.kt)

- **Variáveis de Estado**:
    - Adicionar `private var maxEconomicLevel = 0f` para rastrear o maior valor desde o início do serviço.

- **Componente de Nível de Economia**:
    - Adicionar um novo card dentro da seção de Bateria para exibir o `Economic Level`.
    - Formato: `Valor Atual (Max: Maior Valor)`.
    - Cores dinâmicas no valor: Verde (>= 70), Amarelo (40-69), Vermelho (< 40).

- **Componente de Consumo da Viagem**:
    - Adicionar um card logo abaixo do nível de economia com dois campos:
        - **Energia**: Valor de `CAR_EV_INFO_CYCLE_ENERGY_CONSUME_INFO` + " KW".
        - **Combustível**: Valor de `CAR_EV_INFO_CYCLE_FUEL_CONSUME_INFO` + " Litros".

- **Integração e Atualização**:
    - Inserir estes componentes no final do método `createBatteryCard`.
    - Atualizar os valores em tempo real através do `updaters`.

## Verification Plan

### Manual Verification
- **Economic Level**: Verificar se o "Max" atualiza corretamente conforme valores maiores são recebidos.
- **Consumo**: Validar a exibição correta dos valores de KW e Litros.
- **Estabilidade**: Confirmar que a navegação e interação no Dashboard permanecem fluidas.
