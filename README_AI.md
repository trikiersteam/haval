# 🧠 Contexto para IA (README_AI)

Este arquivo contém diretrizes essenciais para assistentes de IA (Gemini/Gomini) trabalharem no projeto **HavalDock**.

## 📱 Especificações do Hardware (Carro)
- **Resolução da Tela:** 1920x720 pixels (Ultra-wide landscape).
- **Densidade:** 321 dpi.
- **Ambiente:** Central Multimídia GWM (Haval H6/Jolion).
- **Performance (CRÍTICO):** A central tem recursos limitados de CPU e RAM. 
    - **Prioridade:** Baixo consumo de recursos.
    - Evitar recomposições excessivas (se usar Compose) ou atualizações de UI desnecessárias.
    - Manter o `OverlayService` o mais leve possível, pois ele roda permanentemente em background.

## 🛠️ Stack Técnica & Arquitetura
- **Linguagem:** Kotlin (API 28 fixa).
- **Core:** `OverlayService.kt` gerencia a interface via `WindowManager` (`TYPE_APPLICATION_OVERLAY`).
- **Comunicação Veicular:** SDK Beantechs via `VehicleClient.kt`. Toda escrita no barramento deve ser assíncrona.
- **Permissões:** Utiliza Shizuku para comandos que exigem privilégios de sistema (ADB).

## 🎯 Regras de Design e UI
- **Estilo:** HMI Clima v2 (Inspirado no visual original da GWM, mas aprimorado).
- **Fonte:** `Chakra Petch` (Customizada no projeto).
- **Interação:** Suporte a Swipes (Up/Down) para ocultar/exibir a barra.
- **Segurança:** Trava de volume e limites de temperatura para evitar comandos acidentais durante a condução.

## 💡 Diretrizes para Sugestões de Código
1. **Thread Management:** Nunca bloquear a Main Thread. Usar `Executors` ou `Coroutines` para IPC com o carro.
2. **Contexto de Overlay:** Lembrar que `WindowManager` não possui o lifecycle de uma `Activity`.
3. **Compatibilidade:** Não sugerir APIs introduzidas após o Android 9 (Pie/API 28).
4. **Layout:** Sempre considerar o aspecto 1920x720 ao sugerir novos elementos de interface.
