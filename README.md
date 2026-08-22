# Haval Dash

Projeto de estudo, copia de projeto haval-dash de licença MIT aberta.
Agradecimento especial aos HavaleirosBrasil.

Uma **toolbar inferior** (barra de atalhos) para a central Haval/GWM, desenhada como **overlay**
por cima do mediacenter/CarPlay. App Android **standalone** (não depende de outros apps nem de
aprovação de terceiros).

## Estado

🚧 **Bootstrap** — esqueleto do projeto + pipeline de build/release. A UI foi prototipada
(ver `prototype/`) e a implementação do app (overlay + leitura/escrita das funções do veículo)
está em andamento.


## Stack

- Kotlin + Jetpack Compose + Material 3
- Overlay via `WindowManager` (`TYPE_APPLICATION_OVERLAY`), permissão `SYSTEM_ALERT_WINDOW` via Shizuku
- Dados do veículo pelo `IntelligentVehicleControlService` (chaves HVAC/comfort/drive)
- Updater in-app via releases do GitHub
- minSdk/targetSdk 28 (API do veículo), compileSdk 36

## Build

CI por **tag** (`v*`) no GitHub Actions: compila o APK de release assinado e publica numa Release.
A `versionName` no `app/build.gradle.kts` precisa bater com a tag.

```
git tag v0.1.0 && git push origin v0.1.0
```

## Protótipo
executa diretamente no emulador com várias mock quando flag simulacao está habilitado.

