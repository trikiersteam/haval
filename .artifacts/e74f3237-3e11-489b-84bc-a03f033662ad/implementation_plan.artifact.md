# Refatoração do Componente "MODO DE CONDUÇÃO"

Refatorar o componente de seleção de modo de condução para unificá-lo entre o Dual Dashboard e o Light Dashboard, remover a estratégia de atraso de 2000ms (flicker protection) e ajustar o comportamento do modo SAVE.

## Propostas de Mudanças

### `OverlayService.kt`

#### [MODIFY] [OverlayService.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldash/OverlayService.kt)

- **Remover Variáveis de Controle de Atraso**: Remover `lastManualSocTime`, `lastManualSoc`, `lastManualMode` e `lastManualStrategy` (ou deixar de usá-las no contexto do modo de condução).
- **Refatorar `changeDriveMode`**:
    - Remover a atualização de `lastManualSocTime` e `lastManualMode`.
    - Ajustar a lógica de `targetSoc`: Se `soc` for nulo, não deve forçar `c.curHevSocInt()`. O comando para o veículo só deve ser enviado se o `soc` for explicitamente passado.
- **Refatorar `createDriveModeSelectionLight`**:
    - Renomear para algo mais genérico (ex: `createDriveModeComponent`) se for o caso, ou apenas atualizar sua lógica.
    - Remover lógica de `isRecent`.
    - Atualizar `updateUI` para refletir o estado real do veículo via `refreshAll`.
- **Refatorar `createHevSubCardLight`**:
    - Remover lógica de `isRecent`.
    - No `track.setOnTouchListener`:
        - `ACTION_MOVE`: Apenas chama `updateSliderUI` para atualizar o preenchimento da barra e o texto, sem chamar `changeDriveMode`.
        - `ACTION_UP` / `ACTION_CANCEL`: Chama `changeDriveMode` com o `soc` final calculado.
    - No `layout.setOnClickListener`:
        - Chama `changeDriveMode` passando `soc = null`. Isso garantirá que a estratégia mude para SAVE sem sobrescrever o percentual configurado anteriormente no veículo, a menos que o usuário use o slider.
- **Atualizar `openMode`**:
    - Remover a lógica de `isRecent` para manter a consistência com os dashboards.

## Plano de Verificação

### Verificação Manual
1. **Teste de Navegação**: Abrir o Dual Dash e o Light Dash e verificar se o componente de modo de condução está visível e funcional.
2. **Troca de Modos**:
    - Selecionar EV ou EV Priority e verificar a resposta imediata na UI.
    - Selecionar HEV e verificar a exibição das sub-opções.
3. **Comportamento do SAVE**:
    - Clicar no card SAVE: Verificar se a estratégia muda para SAVE no veículo (pode ser verificado via log ou comportamento esperado), mas sem resetar o percentual.
    - Deslizar a barra de SAVE: Verificar se o valor na tela muda enquanto desliza, mas o comando para o carro só é enviado ao soltar (ACTION_UP).
4. **Fim do Atraso**: Verificar que não há mais o travamento de 2 segundos na interface após uma alteração manual.
