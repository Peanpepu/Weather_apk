# AEMET Tiempo (APK Android)

App Android (Kotlin + Jetpack Compose) para ver el tiempo de una ciudad usando **AEMET OpenData**. Es un puerto fiel de la versión web (`web_version/`) y comparte su misma UX: barra de búsqueda con favoritos, resumen actual, pestañas Temperatura / Precipitaciones / Viento con gráficas con scroll que marcan la hora actual, selector Hoy / Mañana / pasado, y tira de 8 días.

## 1) Requisitos

- **Java 17+** (probado con Java 21)
- **Android Studio** (recomendado) o el **Android SDK** instalado por separado
  - `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`, `build-tools 34.0.0+`
- Una **API key** de AEMET OpenData ([obtenerla aquí](https://opendata.aemet.es/centrodedescargas/altaUsuario))

## 2) Configurar el SDK y la API key

### Ruta del SDK

Crea (si no existe) un fichero `local.properties` en la raíz del proyecto con:

```properties
sdk.dir=/home/<tu_usuario>/Android/Sdk
```

(Sustituye la ruta por la que tengas en tu sistema; en macOS suele ser `/Users/<tu_usuario>/Library/Android/sdk`.)

### API key

La API key se compila dentro del APK como `BuildConfig.AEMET_API_KEY`. Se configura mediante un archivo `.env` en la raíz del proyecto:

- Crea un archivo `.env` en la raíz del proyecto (`APK/.env`) con:
  ```
  AEMET_API_KEY=<tu JWT>
  ```

El archivo `.env` está incluido en `.gitignore` para no exponer la clave en el repositorio. El sistema de build leerá automáticamente este archivo y generará el campo `BuildConfig.AEMET_API_KEY` correspondiente.

## 3) Compilar el APK (Debug)

Desde la raíz del proyecto:

```bash
./gradlew :app:assembleDebug
```

El APK se genera en:

- `app/build/outputs/apk/debug/app-debug.apk`

## 4) Instalar en el móvil

### Opción A — Copiar e instalar manualmente

1. Copia `app-debug.apk` al móvil (USB, Drive, etc.).
2. En el móvil, abre el archivo y permite **"Instalar apps desconocidas"** si te lo pide.

### Opción B — Instalar por ADB

```bash
sudo apt install adb              # solo la primera vez
adb devices                       # confirma que el móvil aparece
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

En el móvil tienes que tener activadas **Opciones de desarrollador → Depuración por USB**.

## 5) Cómo funciona la app

### Pantalla principal

Mismo layout que la versión web:

- **Barra superior**: estrella para marcar la ciudad actual como favorita + nombre · provincia + botón ↻ de refresco.
- **Buscador**: filtra el listado completo de municipios INE; los favoritos aparecen primero.
- **Resumen**: emoji del estado del cielo, temperatura grande, métricas (precipitaciones, humedad, viento) y descripción.
- **Píldoras de día**: Hoy / Mañana / día siguiente.
- **Pestañas**: Temperatura (línea + área), Precipitaciones (barras + mm), Viento (celdas con flecha y dirección).
  - Las gráficas se desplazan horizontalmente y se autocentran en la hora actual.
- **Tira diaria**: previsión de 8 días con mínima/máxima.

### Caché y modo offline

- Cada respuesta AEMET se guarda en una caché en memoria (TTL 30 min).
- Si AEMET no responde, se sirve la última respuesta válida y aparece un aviso amarillo "datos en caché".
- El listado de municipios (≈ 8000 entradas) se cachea en `filesDir/aemet_municipios.json`, así la búsqueda funciona sin red.

## 6) Notas sobre AEMET OpenData

La API funciona en **2 pasos** (implementado en `app/src/main/java/com/example/aemet_tiempo/data/AemetClient.kt`):

1. Llamas al endpoint con la cabecera `api_key`; te devuelve un JSON con un enlace `datos`.
2. Descargas la URL `datos` (sin cabecera) y ahí viene el JSON real, codificado en **ISO-8859-1**.

El servidor AEMET corre detrás de un BIG-IP antiguo que no negocia bien TLS moderno; por eso el cliente OkHttp se configura con TLS 1.2 y HTTP/1.1 explícitamente (ver `MainActivity.kt`).

## 7) Estructura del código

```
app/src/main/java/com/example/aemet_tiempo/
├── MainActivity.kt           — entry point + OkHttp/TLS config + tema
├── data/
│   ├── AemetClient.kt        — cliente HTTP, caché, lógica de 2 pasos
│   ├── AemetModels.kt        — DTOs serializables (tipados como strings)
│   └── MunicipiosRepository.kt — maestro de municipios (red + disco)
└── ui/
    ├── App.kt                — pantalla principal (Compose)
    ├── AppViewModel.kt       — estado + reloj + carga reactiva
    ├── Charts.kt             — gráficas scrollables (línea/barra/viento)
    ├── WeatherDerivation.kt  — todas las derivaciones (series, iconos…)
    ├── Prefs.kt              — DataStore de favoritos + última ciudad
    ├── CityIds.kt            — lista de respaldo de municipios
    ├── Defaults.kt           — DEFAULT_MUNICIPIO_ID
    └── SimpleFactory.kt      — fábrica de ViewModel
```

