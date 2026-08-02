# Proteção Automática do Modo Simulação (Ambiente de Dev)

O objetivo é automatizar completamente o "Modo Simulação" baseado na presença de um arquivo marcador local. Se o marcador existir (na sua máquina), o modo liga sozinho. Se não existir (versão final/carro), ele desliga sozinho. O usuário não poderá alterar isso manualmente pela interface.

## Estratégia Técnica

1.  **Marcador Local**: Arquivo `dev_marker` na raiz do projeto (ignorado pelo Git).
2.  **Injeção no Build**: O Gradle verificará o arquivo e injetará a variável `BuildConfig.DEV_ENVIRONMENT`.
3.  **Controle Centralizado**: O `SettingsStore` usará essa variável para forçar o estado da simulação.
4.  **UI Bloqueada**: O switch na `MainActivity` servirá apenas como um indicador visual (ligado ou desligado), mas ficará sempre desabilitado para clique.

## Mudanças Propostas

### 1. Infraestrutura e Git

#### [MODIFY] [.gitignore](file:///Users/rodrigo/StudioProjects/haval/.gitignore)
- Adicionar `dev_marker` para garantir que ele nunca suba para o repositório.

#### [NEW] `dev_marker`
- Criar este arquivo vazio na raiz para ativar o modo na sua máquina agora.

#### [MODIFY] [build.gradle.kts](file:///Users/rodrigo/StudioProjects/haval/app/build.gradle.kts)
- Adicionar a verificação: `val isDev = file("../dev_marker").exists()`.
- Definir `buildConfigField("boolean", "DEV_ENVIRONMENT", isDev.toString())`.

### 2. Lógica e UI

#### [MODIFY] [SettingsStore.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/data/SettingsStore.kt)
- No `init`, definir `simulationEnabled.value = BuildConfig.DEV_ENVIRONMENT`.
- O método `setSimulationEnabled` será mantido mas não terá efeito prático pois a UI estará travada.

#### [MODIFY] [MainActivity.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldock/MainActivity.kt)
- O switch de "Modo Simulação" terá `enabled = false`.
- A descrição será atualizada para: `"Ativado automaticamente em ambiente de desenvolvimento."`

## Plano de Verificação

### Verificação em Ambiente Dev (Sua máquina)
1. Rodar o build.
2. O switch de Simulação na tela de configurações deve aparecer **Ligado** (Azul/Verde) mas **Bloqueado** (Cinza/Desabilitado).

### Verificação de Segurança (Simulada)
1. Apagar o arquivo `dev_marker`.
2. Rodar o build.
3. O switch deve aparecer **Desligado** e **Bloqueado**.

> [!IMPORTANT]
> Após essa mudança, qualquer build gerado via CI (GitHub Actions) ou em outras máquinas sem o marcador será automaticamente uma versão de "Produção" (Simulação OFF).
