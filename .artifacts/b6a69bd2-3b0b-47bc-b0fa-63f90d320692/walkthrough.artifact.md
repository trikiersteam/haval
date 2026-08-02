# Refinamento Estético: Unidades de Temperatura

Ajustamos a exibição da temperatura no dashboard para que o valor numérico mantenha seu destaque visual, enquanto a unidade (°C) assume um tamanho mais discreto e elegante.

## O que mudou

### 1. Destaque Seletivo com Spans
- **Número Grande**: Restauramos o tamanho do valor da temperatura para **54sp**, garantindo excelente legibilidade à distância.
- **Unidade Reduzida**: Utilizamos `SpannableStringBuilder` para aplicar um `RelativeSizeSpan` de **0.7x** especificamente no sufixo "°C". Isso torna a unidade aproximadamente 30% menor que o número, seguindo padrões modernos de design de interfaces automotivas.
- **Hierarquia Visual**: Esta técnica cria uma hierarquia clara, onde o dado principal (a temperatura) é o foco, e a unidade é apenas um complemento informativo.

## Como Testar

1.  Abra o dashboard.
2.  Observe os valores de temperatura nos cartões laterais.
3.  O número (ex: **22.5**) deve aparecer em tamanho grande e negrito, enquanto o **°C** ao lado deve estar visivelmente menor e alinhado ao topo/centro da linha de base do texto.

> [!TIP]
> Esta abordagem resolve o problema de "esmagamento" do layout quando tentávamos reduzir o tamanho de todo o texto, mantendo a área de toque e o alinhamento central perfeitos.

render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/OverlayService.kt)
