# Remodelagem do Dashboard — Design Lovable (Clima)

Este plano descreve a atualização completa do visual do Dashboard para seguir as especificações de design extraídas do protótipo Lovable, mantendo a compatibilidade com o Android 9 da central multimídia (1792x720px).

## User Review Required

> [!IMPORTANT]
> A nova tipografia sugerida (**Chakra Petch**) não está presente no projeto. Usaremos **Roboto Condensed** e **Roboto Mono** como alternativas nativas de alta legibilidade, a menos que você forneça os arquivos `.ttf`.

> [!NOTE]
> O layout passará de uma estrutura de colunas simples para um sistema de grade mais fiel ao "Handoff", com proporções e raios de borda específicos.

## Proposed Changes

### [Data & Styling]

#### [MODIFY] [DockControls.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/data/DockControls.kt)
- Atualizar `DockColors` com os novos tokens: `SURFACE` (#171F28), `SURFACE_RAISED` (#242F3B), `OUTLINE` (#414F5D), `CYAN` (#36CAF1), etc.

### [UI Components]

#### [MODIFY] [OverlayService.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/OverlayService.kt)
- **Grid de 12 Colunas**: Implementar a distribuição de espaço usando `layout_weight` para simular a grade de 12 colunas (4 para motorista, 4 para centro, 4 para passageiro).
- **Novos Cartões (Outer Panels)**:
    - Atualizar `createDashboardCard` para usar `radiusCard` (28dp) e a cor `clima_surface`.
    - Implementar bordas (`stroke`) nos cards ativos.
- **Componentes Internos**:
    - **Temperatura**: Usar o gradiente `CYAN → WHITE → ORANGE` na barra de progresso.
    - **Ventilação/Bancos**: Usar os novos raios de controle e cores de "track" inativa.
    - **Coluna Central**: Criar métodos específicos para os novos cards compactos:
        - `createBatteryCard()`: Barra de progresso horizontal verde.
        - `createDriveModeSelection()`: Três tiles (`HEV`, `Prioridade EV`, `EV`) com bordas de seleção.
        - `createRecirculationCard()`: Estilo pílula com ícone destacado.
        - `createAmbientTempCard()`: Exibição dupla (Interna/Externa) com ícones coloridos.

## Verification Plan

### Automated Tests
- Verificar se as constantes de cores e dimensões estão sendo aplicadas corretamente via logs.

### Manual Verification
- Validar o alinhamento na tela de 1792x720px.
- Confirmar se o recuo de 1 polegada (160px) à esquerda permanece funcional.
- Testar a legibilidade dos novos valores de temperatura (mais largos e destacados).
