# Lançamento v0.2.49: Dashboard Premium e Feedback Instantâneo

Esta versão traz uma evolução significativa no design e na performance da interface do Dashboard, tornando-a mais integrada, informativa e responsiva.

## O que mudou

### 1. Novo Design de Dashboard (Master Card)
- **Fundo Unificado**: Adicionamos um fundo escuro semi-transparente (92% de opacidade) com cantos arredondados (**40dp**) que engloba todo o painel, melhorando drasticamente o contraste sobre qualquer aplicativo de fundo.
- **Posicionamento Ergonômico**: O Dashboard agora é uma "ilha flutuante" centralizada e posicionada na parte inferior da tela, facilitando o alcance dos comandos.

### 2. Controles de Temperatura "Pill Slider"
- **Unificação**: O valor da temperatura agora é exibido **dentro** da barra de controle, que foi encorpada para **44dp**.
- **Refinamento**: O número mantém o destaque, enquanto o símbolo **°C** é renderizado 20% menor, proporcionando um visual sofisticado.

### 3. Feedback Otimista (Resposta Instantânea)
- **Performance**: Todos os botões (POWER, A/C, AUTO, SYNC, Modos de Condução e Fluxo de Ar) agora mudam de cor no exato momento do toque, eliminando qualquer sensação de atraso da rede do carro.

### 4. Otimização do Cartão de Bateria e Economia
- **Layout Inteligente**: O percentual de carga (**SOC**) foi movido para o topo ao lado do título (`BATERIA 75%`).
- **Eficiência**: Os dados de Autonomia e Economia (com Min/Max) agora compartilham a mesma linha, economizando espaço vertical.
- **Destaque**: O valor numérico da autonomia agora é exibido em **Branco**, facilitando a conferência rápida.

### 5. Outros Refinamentos
- **Header**: Simplificado para exibir apenas a data completa, removendo a hora redundante.
- **SYNC**: Botão de sincronização movido para o grupo de controles rápidos no topo das colunas.
- **Mock Data**: Atualizado com valores de economia e autonomia para testes em ambiente de desenvolvimento.

## Como Testar

1.  Abra o Dashboard e sinta a fluidez ao clicar em qualquer botão.
2.  Confira o novo visual "Pill" da temperatura e o alinhamento centralizado do painel.
3.  Observe o cartão de bateria com o SOC em destaque e a autonomia em branco.
4.  Verifique no rodapé que a versão exibida é a `0.2.49`.

render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br.com.redesurftank.havaldash/OverlayService.kt)
render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br.com.redesurftank.havaldash/data/VehicleClient.kt)
