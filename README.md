# OpenKayak-WearOS · reconstrucción 2.0

Aplicación independiente para piragüismo en español, orientada a Wear OS 5 y a un Xiaomi Watch 2 Pro montado en la cubierta de la piragua. Esta rama reinicia los archivos de aplicación y conserva el identificador `com.openkayak.app`.

## Funciones de esta preview (compilación CI y pruebas unitarias correctas)

- UI oscura de alto contraste basada en Wear Compose Material 3 Expressive, siete páginas y flechas laterales permanentes, con panel AOD de circuito circular.
- Dashboard de distancia, tiempo, velocidad y ritmo por kilómetro.
- Servicio GPS en primer plano con reintentos si el proveedor se activa tarde, última posición provisional, filtro de saltos y trazado GPX con los puntos aceptados.
- Exploración, selección y reconexión progresiva de bandas BLE que anuncien el servicio estándar de frecuencia cardíaca; descriptor CCCD confirmado, parser de pulso de 8/16 bits y vigilancia de muestras obsoletas.
- Modo AOD de negro puro, refresco reducido, métricas clave y botón visible «SALIR AOD».
- Detector conservador de paladas con bloqueo por velocidad/precisión GPS y patrón de picos rítmicos. Sigue necesitando validación en la cubierta.
- Modelo local softmax de tres clases entrenado con trazas GPS sintéticas, memoria persistente de los eventos detectados y análisis del GPX para agrupar posibles boyas repetidas.
- Ventana post-entreno con mapa online, FC media/máxima/mínima, kcal estimadas según peso configurable, paladas, giros y opciones para guardar/borrar sesión y circuito. Health Connect se encola tras guardar el entrenamiento.
- Exportación de la ruta en GPX y pruebas unitarias iniciales del parser BLE y del detector de paladas.

## Límites conocidos de la versión

La pantalla de ruta carga teselas de OpenStreetMap por Internet y dibuja el recorrido encima. No incluye descarga de mapas offline en esta preview. Tampoco aprende aún automáticamente circuitos repetidos. La detección de paladas es experimental; oleaje y golpes rítmicos aún pueden crear falsos positivos. Hay que validarla en tu piragua. No se ha validado todavía en agua real ni con tu banda BLE concreta.

El botón de salida de AOD intenta reactivar el reloj, pero Wear OS puede requerir que el primer toque despierte la pantalla antes de aceptar el toque del botón. Las frecuencias de refresco en AOD dependen del sistema. Health Connect solicita los permisos de ejercicio, distancia y frecuencia cardíaca que usa esta versión. Si no está disponible en el reloj, Ajustes muestra el motivo.

## Compilación local

Requiere JDK 17 y Android SDK Platform 36 (AGP 8.9.1 / Gradle 8.11.1):

```bash
./gradlew --no-daemon clean testDebugUnitTest assembleDebug
```

GitHub Actions ejecuta pruebas unitarias y genera un APK debug. En compilaciones push correctas de esta rama, el flujo adjunta el APK a una GitHub pre-release de prueba. No es una compilación certificada libre de errores ni una release firmada para Google Play. El clasificador se entrena con ejemplos sintéticos y no reconoce visualmente boyas físicas.
