# Plano de Implementação: Desativar Assistente de Voz Nativo

Este plano descreve a implementação de uma nova funcionalidade no Haval Dash para permitir que o usuário desative ou ative o assistente de voz nativo do carro através de um botão de alternância (toggle) nas configurações.

## User Review Required

> [!IMPORTANT]
> A funcionalidade depende do **Shizuku** estar configurado e autorizado no dispositivo. Sem o Shizuku, os comandos `pm` falharão silenciosamente ou registrarão um erro no Logcat.

> [!WARNING]
> A desativação é feita via `pm uninstall --user 0`, o que é seguro e reversível, mas remove o app apenas para o usuário atual. O APK original permanece no sistema.

## Proposta de Mudanças

### Data & Logic

#### [MODIFY] [SettingsStore.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldash/data/SettingsStore.kt)
- Adicionar a chave `KEY_NATIVE_VOICE_DISABLED`.
- Adicionar o estado observável `nativeVoiceDisabled`.
- Implementar a lógica de ativação/desativação usando `ShizukuShell` no método `setNativeVoiceDisabled`.
- Os pacotes afetados serão:
    - `com.iflytek.cutefly.speechclient.hmi`
    - `com.beantechs.voiceclient`

### UI (Settings Screen)

#### [MODIFY] [MainActivity.kt](file:///Users/rodrigo/StudioProjects/haval/app/src/main/java/br/com/redesurftank/havaldash/MainActivity.kt)
- Adicionar uma nova seção no `SettingsScreen` chamada "Sistema".
- Incluir um `RowSwitch` para "Desativar Voz Nativa".
- Vincular o switch ao estado em `SettingsStore`.

## Plano de Verificação

### Testes Manuais
1. **Verificação de Persistência**: Alterar o status do botão, fechar o app e reabrir para garantir que o estado foi salvo.
2. **Execução de Comandos**: Monitorar o Logcat para verificar se os comandos `pm uninstall` e `pm install-existing` estão sendo disparados corretamente via Shizuku.
3. **Reversibilidade**: Confirmar que, ao ligar novamente o assistente no app, o pacote volta a ficar disponível no sistema.

### Verificação de Segurança
- O padrão será "Desativado = false" (Assistente Ligado), conforme solicitado.
- A ação só ocorrerá mediante interação explícita do usuário.
