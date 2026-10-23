# OpenKayak 2.7 · Wear OS + Android

OpenKayak es una aplicación de piragüismo con una app nativa para el reloj Wear OS y un centro de control Android. Esta rama conserva también la aplicación web React/TypeScript. Los paquetes Android se generan desde los módulos nativos de Gradle, nunca convirtiendo HTML en APK.

## Paquetes de la versión 2.7

- **AAB Wear OS**: paquete Android App Bundle de la app del reloj, variante release.
- **AAB Android**: paquete Android App Bundle de la app móvil, variante release.
- **APK debug Wear OS y Android**: paquetes instalables para pruebas directas en dispositivos.
- **SHA256SUMS.txt**: hashes SHA-256 de los cuatro paquetes para comprobar su integridad.

Los AAB son el formato recomendado para distribuir aplicaciones mediante Google Play. En esta versión, los AAB se compilan en variante release pero **no están firmados con una clave de subida de Google Play**. Antes de subirlos a Play, hay que configurar la firma de publicación y guardar la clave/contraseñas como secretos de GitHub Actions. No subas un keystore ni contraseñas al repositorio. Los APK debug son para pruebas y no para publicar en Google Play.

## Cambios funcionales incluidos en esta rama

- Ajuste optativo «Hola reloj» con reconocimiento local compatible, solo mientras OpenKayak está en primer plano y la pantalla está interactiva.
- Detector de paladas experimental con validación temporal.
- Coach IA en Android conectado a Gemini, con clave API almacenada cifrada mediante Android Keystore.
- Trazado GPS, historial, importación/exportación GPX, mapa, circuitos/boyas, BLE de frecuencia cardíaca, sincronización Wear OS Data Layer y escritura en Health Connect desde el reloj cuando se conceden permisos.

## Límites importantes

«Hola reloj» no es una palabra de activación de bajo consumo a nivel de hardware. Se pausa al entrar en AOD o al salir de la app y necesita micrófono y reconocimiento local compatible. El Coach IA necesita Internet; la pregunta y el resumen de entrenamientos enviado como contexto se procesan a través de Google. La detección de paladas es experimental, y la calidad de las métricas debe comprobarse en el dispositivo real.

## Compilar los paquetes nativos

Requiere JDK 17 y Android SDK Platform 36.

```bash
./gradlew --no-daemon clean testDebugUnitTest :app:bundleRelease :phone:bundleRelease :app:assembleDebug :phone:assembleDebug
```

Salidas de Gradle:

- Wear OS AAB: `app/build/outputs/bundle/release/app-release.aab`
- Android AAB: `phone/build/outputs/bundle/release/phone-release.aab`
- Wear OS APK debug: `app/build/outputs/apk/debug/app-debug.apk`
- Android APK debug: `phone/build/outputs/apk/debug/phone-debug.apk`

## Compilar la aplicación web

Requiere Node.js 22 o compatible con las dependencias del proyecto.

```bash
npm ci
npm run build
npm run dev
```

## Descargas

Los paquetes se publican en [Releases de GitHub](https://github.com/tovicito/OpenKayak-WearOS/releases). La release v2.7 solo debe considerarse lista cuando GitHub Actions termine correctamente y muestre los dos AAB, los dos APK debug y el archivo de hashes.
