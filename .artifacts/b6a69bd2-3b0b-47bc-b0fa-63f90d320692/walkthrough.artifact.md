# Lançamento v0.2.46: Melhoria na Organização das Configurações

Refinamos a tela de configurações para ocultar grupos de opções que não são aplicáveis ao modo de visualização selecionado, mantendo a interface limpa e intuitiva.

## O que mudou

### 1. Visibilidade Condicional do Posicionamento
- **Filtro Inteligente**: O grupo "Posicionamento das Seções" agora é ocultado automaticamente quando o "Tipo de Visualização" é **Dashboard**.
- **Contexto**: Como o posicionamento manual é uma funcionalidade exclusiva da **Barra Inferior**, removê-lo quando o Dashboard está ativo evita confusão e economiza espaço na tela de configurações.

### 2. Publicação e Versionamento
- **Versão**: Atualizado para **0.2.46** (Build 39).
- **GitHub**: Realizado o commit das alterações, criação da tag `v0.2.46` e push para o repositório oficial.

## Como Testar

1.  Abra a tela de Configurações.
2.  Com o "Tipo de Visualização" em **Barra**:
    - Verifique se o grupo "Posicionamento das Seções" está visível no final da lista.
3.  Mude o "Tipo de Visualização" para **Dashboard**:
    - Observe que o grupo "Posicionamento das Seções" desaparece instantaneamente.
4.  Confirme no rodapé que a versão exibida é a `0.2.46`.

> [!TIP]
> Essa mudança torna a configuração do app muito mais focada: você só vê o que realmente pode ajustar para o modo que escolheu usar.

render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/MainActivity.kt)
