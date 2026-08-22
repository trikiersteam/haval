# Refatoração do Cartão de Bateria e Economia

O objetivo é otimizar o espaço no cartão de bateria, movendo as informações de Economia para a mesma linha da Autonomia e dando maior destaque visual ao percentual de carga (SOC).

## Mudanças Propostas

### Overlay Service

#### [MODIFY] [OverlayService.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br.com.redesurftank.havaldash/OverlayService.kt)

**1. Reestruturação da Linha Superior (`topRow`)**
- Manter o ícone da bateria.
- Manter o texto "BATERIA" (fixo, alinhado à esquerda).
- Criar um novo container horizontal (`infoContainer`) com `weight = 1f`.
- Dentro deste container, colocar:
    - `autonomiaTv`: Texto da autonomia (ex: "450 KM").
    - `ecoTv`: O texto de Economia (Min/Max), movido da linha inferior.
- Estes textos terão um tamanho menor (ex: `10sp`) para caberem lado a lado.

**2. Destaque do SOC**
- Aumentar o `textSize` do `valueTxt` (percentual de bateria) de **14sp** para **22sp** (seguindo o padrão de outros valores importantes no Dashboard).
- Garantir que ele tenha um padding à esquerda para não "colar" nas outras informações.

**3. Remoção de Linha Redundante**
- Eliminar o `ecoRow` original, ganhando espaço vertical no cartão.

## Plano de Verificação

### Verificação Manual
- Abrir o dashboard e confirmar que a linha superior agora contém: `[Ícone] BATERIA [Autonomia | Economia] [SOC%]`.
- Verificar se o percentual da bateria está visivelmente maior e mais fácil de ler.
- Validar se o texto de Economia (Min/Max) cabe corretamente ao lado da Autonomia.
- Confirmar que as cores dinâmicas continuam sendo aplicadas corretamente em todos os elementos.
