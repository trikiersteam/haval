# Lançamento v0.2.45: Refinamento de Configurações e Publicação

Ajustamos o comportamento da tela de configurações para ocultar ou desabilitar itens que não se aplicam ao modo visual ou de visibilidade selecionado.

## O que mudou

### 1. Refinamento da Tela de Configurações
- **Tempo do Menu**: O item "Tempo do menu" agora é ocultado automaticamente quando a visibilidade está em "Sempre visível", seguindo o mesmo comportamento do item "Ocultar após".
- **Habilitação por Contexto**:
    - O item "Tempo do menu" agora é desabilitado quando o modo de visualização é "Dashboard", pois os menus de popup são específicos da barra inferior.
    - O feedback visual (texto cinza) foi aplicado a todos os itens dependentes do modo "Barra" quando estão inativos.

### 2. Publicação e Versionamento
- **Versão**: Atualizado para **0.2.45** (Build 38).
- **GitHub**: Realizado o commit das alterações, criação da tag `v0.2.45` e push para o repositório oficial.

## Como Testar

1.  Abra a tela de Configurações.
2.  Mude a "Visibilidade da Barra" para **Sempre visível**:
    - Observe que os itens "Ocultar após" e "Tempo do menu" desaparecerão da lista.
3.  Mude para **Auto-ocultar**:
    - Os itens reaparecerão.
4.  Alterne o "Tipo de Visualização" para **Dashboard**:
    - Verifique se os itens de ajuste da barra (incluindo o Tempo do menu) ficam cinzas e bloqueados.
5.  Confirme no rodapé que a versão exibida é a `0.2.45`.

render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/MainActivity.kt)
