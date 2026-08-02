# Lançamento v0.2.44: Ajustes de Configuração e Estabilização

Preparamos o app para publicação oficial, focando na estabilização dos modos visuais e na desativação de ferramentas de teste para o usuário final.

## O que mudou

### 1. Refinamento da Interface de Configurações
- **Modos Visuais**: Removemos a opção "Balões", mantendo o foco em "Barra" e "Dashboard".
- **Habilitação Inteligente**: Os controles de "Altura da barra", "Opacidade da barra" e "Moldura nos itens" agora são desabilitados automaticamente quando o Dashboard está selecionado, pois essas configurações afetam exclusivamente a barra inferior.
- **Feedback Visual**: Itens desabilitados agora aparecem em tom cinza (Muted), indicando claramente que não podem ser alterados no modo atual.

### 2. Estabilização e Produção
- **Modo Simulação**: Desativamos permanentemente o switch de "Modo Simulação" e garantimos que ele inicie desligado, evitando que o usuário final entre acidentalmente em modo de teste.
- **Migração Automática**: Se um usuário estivesse usando o modo "Balões", o app o migrará automaticamente para o modo "Barra" ao iniciar.

### 3. Publicação e Versionamento
- **Versão**: Atualizado para **0.2.44** (Build 37).
- **GitHub**: Realizado o commit das alterações, criação da tag `v0.2.44` e push para o repositório oficial.

## Como Testar

1.  Abra a tela de Configurações.
2.  Observe que a opção "Balões" não existe mais no seletor de "Tipo de Visualização".
3.  Alterne para "Dashboard": as opções de ajuste da barra devem ficar cinzas e bloqueadas.
4.  Alterne para "Barra": as opções devem voltar a ficar ativas (cor branca).
5.  Confirme que o "Modo Simulação" está cinza e desligado.
6.  Verifique no rodapé que a versão exibida é a `0.2.44`.

> [!IMPORTANT]
> Estas mudanças garantem que o usuário tenha uma experiência sem erros de configuração e que o app se comporte de forma previsível em ambiente real.

render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/MainActivity.kt)
render_diffs(file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/data/SettingsStore.kt)
