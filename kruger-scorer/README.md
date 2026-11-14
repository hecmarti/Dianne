# Dianne

App Android para puntuar dianas **Krüger** (airgun / ISSF training) a partir de una foto.

## Qué hace

1. Haces una foto (o eliges una de la galería) de la diana.
2. La app calibra el negro central, detecta los huecos de perdigón y asigna la puntuación de anillo (criterio ISSF: borde del impacto más cercano al centro).
3. Cada impacto se dibuja con un **círculo de color único por puntuación** y su número.
4. Puedes **tocar un círculo** para incluirlo o excluirlo del total.
5. El **umbral de detección** se ajusta con un slider (impactos dudosos o unidos).

## Descarga e instalación (APK)

APK debug listo para instalar:

- [`dist/dianne-debug.apk`](dist/dianne-debug.apk)

En el teléfono:

1. Transfiere el APK (USB, Drive, etc.).
2. Permite instalar apps de orígenes desconocidos para ese gestor de archivos.
3. Abre el APK e instala.
4. Al abrir Dianne, concede permiso de cámara si quieres fotografiar.

Requisitos: Android 8.0+ (API 26).

## Uso rápido

- **Hacer foto** / **Elegir de galería**: analiza tu diana.
- **Probar con diana de ejemplo**: carga una Krüger con impactos simulados.
- Ajusta el umbral si faltan o sobran detecciones.
- Toca círculos para corregir el total.

Consejos de foto: diana bien iluminada, lo más frontal posible, que se vea entero el círculo negro.

## Compilar

```bash
cd kruger-scorer
# local.properties → sdk.dir=/ruta/al/Android/Sdk
./gradlew assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```


## Reconocimiento (fotos reales)

Pipeline por **marco canónico + aplanado de iluminación** (mismas dianas Krüger):

1. Calibrar el círculo negro (varios umbrales, radio acotado) y warp a un marco canónico.
2. Aplanar sombras dentro del negro (`gray / blur`) y combinar residual + chroma (cian/azul del fondo).
3. Cierre morfológico para completar círculos rotos; DT + semillas para separar clusters; validación por cobertura.
4. Puntuar en el marco canónico (ISSF 10 m aire) y proyectar al overlay.

Afinado con un lote de fotos de móvil (≈±1 impacto en train, holdout OK). El umbral y los taps manuales siguen siendo la red de seguridad.

## Arquitectura

| Pieza | Rol |
|-------|-----|
| `vision/TargetCalibrator` | Localiza el círculo negro (anillo 7) |
| `vision/TemplatePipeline` | Warp canónico + diferencia vs plantilla + cierre/split |
| `vision/DetectionPipeline` | Orquesta el análisis para la UI |
| UI Compose | Home → revisión con overlay interactivo |

Sin OpenCV ni modelos ML: el APK se mantiene ligero y la instalación es directa.
