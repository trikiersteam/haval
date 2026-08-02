# Refatoração do Layout dos Modos de Condução

O objetivo é ajustar o layout dos botões de seleção de modo (HEV, Prioridade EV e EV) para uma disposição horizontal (ícone seguido de texto) e reduzir a altura total do componente para torná-lo mais compacto no dashboard.

## Mudanças Propostas

### Overlay Service

#### [MODIFY] [OverlayService.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/OverlayService.kt)

- **`createModeTile`**:
    - Alterar `orientation` de `VERTICAL` para `HORIZONTAL`.
    - Reduzir o tamanho do ícone de `dp(32)` para `dp(24)`.
    - Remover o padding superior do texto e adicionar um padding à esquerda (`dp(8)`) para afastá-lo do ícone.
- **`createDriveModeSelection`**:
    - Reduzir a altura dos tiles no `modeRow` de `dp(110)` para `dp(50)`.
    - Ajustar as margens entre os botões para `dp(4)` para otimizar o espaço.

## Plano de Verificação

### Verificação Manual
- Abrir o dashboard e confirmar que os botões de modo agora são horizontais e mais baixos.
- Verificar se o ícone e o texto estão devidamente alinhados no centro vertical do botão.
- Confirmar que a borda de seleção continua envolvendo o botão corretamente com a cor do modo ativo.
