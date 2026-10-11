# OpenKayak-WearOS · reconstrucción 2.0

Aplicación independiente para piragüismo en español, orientada a Wear OS 5 y a un Xiaomi Watch 2 Pro montado en la cubierta de la piragua. Esta rama reinicia los archivos de aplicación y conserva el identificador `com.openkayak.app`.

## Funciones de esta preview (compilación CI y pruebas unitarias correctas)

- UI oscura de alto contraste basada en Wear Compose Material 3 Expressive, seis páginas y flechas laterales permanentes.
- Dashboard de distancia, tiempo, velocidad y ritmo por kilómetro.
- Servicio GPS en primer plano, filtro de saltos físicamente imposibles y trazado GPX local.
- Exploración, selección y reconexión progresiva de bandas BLE que anuncien el servicio estándar de frecuencia cardíaca; descriptor CCCD confirmado, parser de pulso de 8/16 bits y vigilancia de muestras obsoletas.
- Modo AOD de negro puro, refresco reducido, métricas clave y botón visible «SALIR AOD».
- Estimación experimental de paladas mediante acelerómetro, ajustada para llevar el reloj montado en la cubierta.
- Guardado, visualización y borrado manual de boyas de giro (rosa) y puntos de ruta (verde).
- Sincronización en segundo plano de sesión de ejercicio, distancia y muestras de pulso con Health Connect, con notificación de fallo y acciones Reintentar/Abortar.
- Exportación de la ruta en GPX y pruebas unitarias iniciales del parser BLE y del detector de paladas.

## Límites conocidos de la versión

La pantalla de ruta dibuja el trazado y los puntos guardados sobre una cuadrícula vectorial, no sobre un mapa raster. No incluye descarga de mapas offline en esta preview. Tampoco aprende aún automáticamente circuitos repetidos. Las paladas son una estimación a partir de vibraciones/movimiento de la embarcación: oleaje y golpes pueden generar falsos positivos, así que hace falta calibración en tu piragua. No se ha validado todavía en agua real ni con tu banda BLE concreta.

El botón de salida de AOD intenta reactivar el reloj, pero Wear OS puede requerir que el primer toque despierte la pantalla antes de aceptar el toque del botón. Las frecuencias de refresco en AOD dependen del sistema. La app solicita permisos de Health Connect solo para los tipos de datos que escribe esta versión, no para categorías de salud que no utiliza.

## Compilación local

Requiere JDK 17 y Android SDK Platform 36 (AGP 8.9.1 / Gradle 8.11.1):

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug
```

GitHub Actions ejecuta pruebas unitarias y genera un APK debug. En compilaciones push correctas de esta rama, el flujo adjunta el APK a una GitHub pre-release de prueba. No es una compilación certificada libre de errores ni una release firmada para Google Play.
