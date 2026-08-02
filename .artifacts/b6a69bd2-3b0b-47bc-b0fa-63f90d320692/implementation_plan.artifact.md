# Adição do Botão SYNC e Ajuste de Tamanho da Temperatura

Este plano detalha a inclusão do botão de sincronização (SYNC) no controle de temperatura do motorista e a redução do tamanho da fonte da temperatura para melhor equilíbrio visual.

## Mudanças Propostas

### Overlay Service

#### [MODIFY] [OverlayService.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/OverlayService.kt)

**1. Refatoração do `createTempControl`**
- Adicionar um parâmetro opcional `id: String` ou `side: String` para identificar se é o controle do motorista.
- Reduzir o `textSize` do valor da temperatura de **54f** para **43f** (~20% de redução).
- Criar um `FrameLayout` no topo do card para conter tanto o valor da temperatura (centralizado) quanto o novo botão SYNC (alinhado à esquerda).

**2. Implementação do Botão SYNC**
- O botão terá o texto "SYNC", fonte `10f` ou `11f` (conforme padrão de legendas), estilo negrito.
- Estilo "pill" com borda e fundo dinâmico:
    - **Ligado**: Fundo Azul (`SURFACE_SELECTED`), Borda Ciano, Texto Ciano.
    - **Desligado**: Fundo Padrão (`SURFACE_RAISED`), Borda Linha, Texto Mudo.
- Ação: Alternar o valor de `DockKeys.CAR_HVAC_SYNC_ENABLE` entre "1" e "0".

## Plano de Verificação

### Verificação Manual
- Abrir o dashboard.
- Confirmar que o botão SYNC aparece **apenas** no card de temperatura do motorista e está alinhado à esquerda.
- Testar o clique no SYNC e verificar se o estado visual muda e se o comando é enviado ao carro.
- Validar se o texto da temperatura ficou mais proporcional ao card com o novo tamanho reduzido.
