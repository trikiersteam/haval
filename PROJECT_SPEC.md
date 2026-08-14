# HavalDock - Project Specifications for AI

Este arquivo contém as especificações técnicas e de design do projeto **HavalDock** para orientar IAs no desenvolvimento e manutenção do código.

## 🚗 Visão Geral do Projeto
O **HavalDock** é uma barra de ferramentas (dock) e painel de instrumentos (dashboard) customizado para centrais multimídia de veículos (especificamente da linha Haval). O app roda como um serviço de overlay (`OverlayService`) para fornecer acesso rápido a controles do veículo sem sair de outras aplicações (como Waze ou Spotify).

## 🛠️ Stack Tecnológica
- **Linguagem:** Kotlin.
- **Plataforma:** Android 9.0 (API 28).
  - *Nota:* O app é distribuído via sideload, por isso mantém o `targetSdkVersion` em 28 para compatibilidade total com o sistema do veículo.
- **Interface (UI):** 
  - **Jetpack Compose + Material 3:** Usado para telas de configuração e novos componentes.
  - **Views Tradicionais (XML/Programático):** O `OverlayService` utiliza `WindowManager` com Views tradicionais para garantir performance e controle preciso da sobreposição em tempo real.
- **Integrações:**
  - **Shizuku:** Utilizado para acessar APIs ocultas do sistema Android e controlar funções do veículo.
  - **CAN Bus / Vehicle SDK:** Integração com o barramento do carro para leitura/escrita de dados de Climatização (HVAC), Bateria (EV) e Modos de Condução.

## 📺 Especificações de Tela e Display
- **Orientação:** Paisagem (Landscape).
- **Resolução Nativa da Central:** **1920 x 720** pixels (Ultra-wide).
- **Densidade:** ~321 dpi.
- **Dimensões dos Componentes:**
  - **Dashboard:** O painel principal é renderizado em uma área de **1770 x 720** pixels, geralmente alinhado à direita (`Gravity.END`).
  - **Toolbar (Barra Inferior):** Ocupa toda a largura da tela (`MATCH_PARENT`) quando ativa, com altura variável definida via `SettingsStore` (geralmente entre 60 e 80dp).
  - **Handle (Mini Pill):** Quando minimizado, reduz-se a uma área de **100dp x 22dp** centralizada na parte inferior.

## 🎮 Funcionalidades Principais
1. **Climatização (HVAC):** Controle de temperatura (Dual Zone), velocidade do ventilador, direção do ar (Airflow), AC, Auto e recirculação.
2. **Energia e Bateria:** Monitoramento de nível de bateria (SOC), voltagem, consumo instantâneo e gráfico de regeneração de energia.
3. **Modos de Condução:** Seleção rápida entre HEV, EV, Save SOC e estratégias de energia.
4. **Lançador de Projeção:** Atalhos inteligentes para Android Auto e Apple CarPlay, com detecção de foco e retorno rápido.
5. **Dashboards:** Painéis visuais (Normal e Light) que consolidam todas as informações do veículo em uma única tela.

## 🏗️ Estrutura de Pastas
- `:app`: Módulo principal contendo a lógica de UI e serviços.
- `br.com.redesurftank.havaldock.data`: Camada de dados e clientes de comunicação com o veículo (`VehicleClient`).
- `br.com.redesurftank.havaldock.OverlayService`: O coração do aplicativo que gerencia toda a interface sobreposta.
