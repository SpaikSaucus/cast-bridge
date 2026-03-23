[![en](https://img.shields.io/badge/lang-en-red.svg):black_large_square:](https://github.com/SpaikSaucus/cast-bridge/blob/main/README.md) [![es](https://img.shields.io/badge/lang-es-yellow.svg):ballot_box_with_check:](#)

# CastBridge

App Android para enviar videos de paginas web al Chromecast con conexion estable, usando el mismo protocolo que YouTube.

Los navegadores usan screen mirroring para castear, lo que genera desconexiones y consume bateria. CastBridge en cambio detecta la URL del stream y la envia directamente al Chromecast via Cast SDK.

## Compatibilidad

Funciona con servidores que sirven video sin DRM: HLS (.m3u8), DASH (.mpd), MP4, WebM.

No funciona con contenido protegido con DRM (Netflix, Disney+, Amazon Prime) ni con YouTube (usar su app oficial). Si al reproducir ves un error como "contenido protegido" o "error 232403", ese servidor usa DRM — proba con otro servidor disponible en la pagina.

## Compilar

Requisito: [Docker Desktop](https://www.docker.com/products/docker-desktop/) instalado y corriendo.

```bash
cp .env.example .env        # crear archivo de configuracion
./build-apk.sh              # Linux/macOS
```

En Windows: doble click en `build-apk.bat`.

La APK se genera en `app-output/CastBridge.apk`. La primera compilacion tarda 10-20 minutos (descarga JDK, Android SDK, Gradle). Las siguientes tardan 1-3 minutos.

> El archivo `.env` contiene las passwords para firmar la APK. Podes cambiar los valores por defecto editando el archivo. No se sube al repositorio (esta en `.gitignore`).

## Instalar

**Via USB** (recomendado):
1. Activar Depuracion USB en el celular: Ajustes > Acerca del telefono > tocar 7 veces "Numero de compilacion" > Opciones de desarrollador > Depuracion USB
2. Conectar el celular y ejecutar: `adb install app-output/CastBridge.apk`

**Sin USB**: copiar la APK al celular e instalarla manualmente (requiere habilitar fuentes desconocidas).

## Uso

1. Abrir CastBridge y seleccionar el Chromecast arriba a la derecha.
2. Luego pegar una URL en la barra de direcciones (o compartir desde cualquier navegador).
3. Navegar hasta el video y darle play. Si hay multiples servidores, elegir uno.
4. Cuando aparece **"Video detected!"**, tocar **SEND TO CAST**. Si la pagina tiene multiples streams, la app muestra una lista para elegir — es normal probar mas de uno hasta dar con el que reproduce.
5. Controlar la reproduccion con los botones Play/Pause y Stop.

## Problemas comunes

| Problema | Solucion |
|----------|----------|
| Docker build falla | Verificar que Docker Desktop este corriendo |
| Exit code 137 | Asignar al menos 4 GB de RAM en Docker Desktop > Settings > Resources |
| No aparece el icono de Cast | El celular y el Chromecast deben estar en la misma red Wi-Fi |
| "Video detected!" no aparece | Probar con otro servidor de video en la pagina |
| Error DRM / contenido protegido | Ese servidor usa encriptacion, cambiar a otro servidor |

## Estructura

```
Dockerfile                       # Entorno de build containerizado
build-apk.sh / build-apk.bat    # Scripts de compilacion
app/src/main/java/.../
  MainActivity.java              # UI y navegador WebView
  VideoDetector.java             # Deteccion de video e inyeccion JS en iframes
  CastManager.java               # Sesion Cast y controles de reproduccion
  LocalStreamProxy.java          # Proxy HTTP que reenvia video al Chromecast
  AdBlocker.java                 # Bloqueo de ads y trackers (130+ dominios)
  UrlUtils.java                  # Utilidades de URL
  CastOptionsProvider.java       # Configuracion Cast SDK
  CastDiagnostics.java           # Datos de diagnostico
```
