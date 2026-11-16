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

Pipeline de **pistola de aire 10 m** (medición en mm):

1. Localizar el círculo negro (radio = 29,75 mm) en dos pasadas (miniatura → recorte escalado).
2. Agujeros = claros respecto a un fondo local desenfocado (soporta sombras / crema / azul).
3. Cada mancha se reparte en N círculos de radio de balín (2,25 mm) minimizando error; se prefiere un total de 10 impactos.
4. Mordiscos en el borde del negro, rechazo de dedos, y `?` (confianza baja) si el disparo está a &lt; 0,8 mm de una línea.
5. Taps / slider para corregir.

Validado con la sesión del 14-11 (totales conocidos): **5/6 exactos** (la 5ª queda a 1 punto y marcada dudosa).

## Arquitectura

| Pieza | Rol |
|-------|-----|
| `vision/PistolPipeline` | Calibración + umbral local + empaquetado de círculos + scoring pistola |
| `vision/DetectionPipeline` | Orquesta el análisis para la UI |
| `vision/ScoreMapper` | Radios ISSF pistola 10 m |
| UI Compose | Home → revisión con overlay interactivo |

Sin OpenCV ni modelos ML: el APK se mantiene ligero y la instalación es directa.
