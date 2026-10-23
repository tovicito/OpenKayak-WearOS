# OpenKayak 2.6 · Wear OS + Android

OpenKayak es una aplicación de piragüismo con una app nativa para el reloj Wear OS y un centro de control Android. Esta rama conserva además la aplicación web React/TypeScript de Google AI Studio, pero los APK descargables se compilan desde los módulos Android de Gradle. No se generan APK simulados a partir de HTML.

## Cambios de esta integración

- La pantalla de Ajustes del reloj permite activar «Hola reloj». Cuando está habilitado y hay reconocimiento local español disponible, el reloj escucha la palabra clave y puede responder a consultas de distancia, velocidad, tiempo y comandos compatibles.
- El detector de paladas del reloj ya no aplica un periodo refractario adicional; aún valida la consistencia temporal de los picos para reducir recuentos erráticos. Es experimental y debe comprobarse en el kayak real.
- El móvil incluye una pestaña «Coach IA» conectada a Gemini y un campo para introducir la clave API.
- La clave de Gemini se cifra con AES-GCM usando Android Keystore y queda almacenada solo en el móvil. No se envía al reloj ni se incluye en el repositorio o en la URL. Para generar respuestas, el móvil transmite la pregunta y un resumen de los últimos entrenamientos a la API de Google; se necesita conexión a Internet y una clave válida.
- Se conserva la app nativa de reloj/móvil con mapa, trazado GPS, historial, importación y exportación GPX, circuitos/boyas, BLE de frecuencia cardíaca, sincronización Wear OS Data Layer y escritura en Health Connect desde el reloj cuando el usuario otorga permisos.

## Límites importantes

«Hola reloj» es una escucha continua **optativa**, no un servicio de palabra de activación de bajo consumo a nivel de hardware. Solo funciona con OpenKayak en primer plano, pantalla interactiva y un motor de reconocimiento local compatible; se pausa al entrar en AOD o al salir de la app. Si el reloj no ofrece reconocimiento local español, usa el botón de voz bajo demanda o instala/activa un proveedor de voz local compatible. El permiso de micrófono es necesario.

La clave de Gemini permanece cifrada en este móvil, pero el texto que se envía al coach y las estadísticas del entrenamiento incluidas en el contexto se procesan a través del servicio de Google. No introduzcas una clave si no confías en ese flujo. El coach es una función online y puede equivocarse; contrasta las métricas con el historial real.

La detección de paladas es experimental. La calidad de distancia/velocidad depende del GPS; la frecuencia cardíaca depende de que haya muestras válidas de la banda. Las calorías son estimaciones. Comprueba GPS, sensores, sincronización, permisos y consumo de batería durante pruebas reales antes de depender de las métricas. Los APK de CI son builds debug para pruebas, no paquetes firmados para Google Play.

## Compilar las apps nativas

Requiere JDK 17 y Android SDK Platform 36.

```bash
./gradlew --no-daemon clean testDebugUnitTest :app:assembleDebug :phone:assembleDebug
```

Los APK se generan en:

- Wear OS: `app/build/outputs/apk/debug/app-debug.apk`
- Android móvil: `phone/build/outputs/apk/debug/phone-debug.apk`

## Compilar y ejecutar la aplicación web

Requiere Node.js 22 o compatible con las dependencias del proyecto.

```bash
npm ci
npm run build
npm run dev
```

La versión web usa capacidades del navegador (por ejemplo, sensores, Bluetooth o reconocimiento de voz) y no sustituye a las apps Android nativas. No ejecutes scripts que etiqueten HTML como `.apk`: los APK deben salir de Gradle.

## Descargas

Los APK de la integración se publican en la sección [Releases de GitHub](https://github.com/tovicito/OpenKayak-WearOS/releases). La release v2.6 solo debe considerarse disponible después de que el workflow haya terminado con éxito y los dos archivos APK estén adjuntos.
