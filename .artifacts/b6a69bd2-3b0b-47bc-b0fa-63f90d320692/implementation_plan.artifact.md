# Plano de Publicação - Ajustes de Configuração e Lançamento v0.2.44

Este plano detalha os ajustes finais na interface de configurações e a preparação para a publicação da versão v0.2.44 no GitHub.

## Alterações Propostas

### Interface de Configurações (`MainActivity.kt`)

#### [MODIFY] [MainActivity.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/MainActivity.kt)
- **Tipo de Visualização**: Remover a opção "Balões" do seletor segmentado.
- **Habilitação Condicional**:
    - Adicionar suporte a `enabled` no componente `Stepper`.
    - Desabilitar os itens "Altura da barra", "Opacidade da barra" e "Moldura nos itens" quando o modo visual não for "Barra".
    - Quando desabilitados, as cores dos textos serão alteradas para `Muted`.
- **Modo Simulação**: Desabilitar o switch e garantir que ele permaneça desligado.

### Armazenamento de Configurações (`SettingsStore.kt`)

#### [MODIFY] [SettingsStore.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/data/SettingsStore.kt)
- Garantir que `simulationEnabled` seja iniciado como `false`.
- Adicionar lógica para garantir que o modo visual seja alterado para "Barra" caso estivesse em "Balões" (prevenção).

### Publicação (`app/build.gradle.kts` e Git)

#### [MODIFY] [build.gradle.kts](file:///Users/rodrigo/StudioProjects/haval/app/build.gradle.kts)
- Incrementar `versionCode` para `37`.
- Incrementar `versionName` para `0.2.44`.

#### Git
- Adicionar todos os arquivos modificados.
- Realizar commit com mensagem: `fix: ajustes de configuração, desativação do modo simulação e lançamento v0.2.44`.
- Criar tag `v0.2.44`.
- Realizar push para o repositório principal e push das tags.

## Plano de Verificação

### Verificação Manual
1. Abrir a tela de configurações.
2. Verificar se a opção "Balões" desapareceu.
3. Mudar o tipo de visualização para "Dashboard" e verificar se "Altura da barra", "Opacidade da barra" e "Moldura nos itens" ficam cinzas e bloqueados.
4. Mudar para "Barra" e verificar se eles voltam a ficar ativos.
5. Verificar se o "Modo Simulação" está desabilitado e desligado.
6. Confirmar se a versão exibida no rodapé é `0.2.44`.
