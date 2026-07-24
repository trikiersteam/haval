# Haval Dock

Uma **toolbar inferior** (barra de atalhos) para a central Haval/GWM, desenhada como **overlay**
por cima do mediacenter/CarPlay. App Android **standalone** (não depende de outros apps nem de
aprovação de terceiros), no mesmo molde do [haval-radio](https://github.com/leandrosavn/haval-radio).

A barra fica **só na faixa de baixo** da tela — nunca cobre o header, o meio ou as laterais (é onde
o mapa/CarPlay aparece). As configurações abrem **apenas pelo ícone do app** na central.

## Estado

🚧 **Bootstrap** — esqueleto do projeto + pipeline de build/release. A UI foi prototipada
(ver `prototype/`) e a implementação do app (overlay + leitura/escrita das funções do veículo)
está em andamento.

## A barra (10 controles, esquerda → direita)

`Temp. motorista · Ventil. motorista | Veloc. ar-cond. · Recirculador | Modo condução · Modo direção · Regeneração | Temp. passageiro · Ventil. passageiro | Volume rádio`

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

`prototype/index.html` — mockup HTML da barra + tela de configurações (abre direto no navegador).
