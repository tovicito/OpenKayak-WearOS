# OpenKayak-WearOS 2.5

Aplicación gratuita para piragüismo con app Wear OS y app Android complementaria.

## Novedades 2.5
- Asistente de voz activado manualmente desde el reloj: distancia, velocidad, tiempo, marcar vuelta y solicitud de finalización con confirmación.
- Reconocimiento en español con preferencia por el motor local cuando el reloj lo admite; no mantiene el micrófono abierto continuamente.
- Selector de unidades de velocidad en el reloj: km/h, mph y m/s.
- El asistente consulta el estado real de TrackStore y no modifica el servicio GPS mientras escucha.

## Novedades 2.3
- El reloj transmite entrenamientos, estadísticas de FC, muestras de FC disponibles y la ruta GPX por Wear OS Data Layer.
- El móvil abre un resumen ampliado en una ventana separada dentro de la app: duración, distancia, velocidad media, kcal estimadas, paladas, FC media/máxima/mínima y mapa de ruta completa.
- Gráficas de FC y velocidad con muestras cada 5 segundos y tabla temporal con FC, velocidad y coordenadas GPS.
- Borrado de entrenamientos desde el móvil con confirmación y solicitud de borrado en el reloj cuando esté conectado.
- Perfil de peso, edad y altura sincronizable entre móvil y reloj.
- Mantiene boyas, circuitos, ajustes de mapa, GPS, BLE HR, AOD y Health Connect.

## Precisión y límites
Las gráficas usan los datos realmente recibidos. La FC se registra aproximadamente cada 5 segundos; si faltan muestras, se muestran como ausentes. La velocidad se estima a partir de marcas temporales y coordenadas GPX y depende de la calidad del GPS. Las calorías son una estimación basada en peso y duración, no una medición. La altura se guarda en el perfil pero no hace que las calorías sean exactas. El detector de paladas es experimental.

Los mapas consultan teselas online y mantienen una caché limitada. Los paquetes MBTiles deben proceder de un proveedor que permita uso offline; no se precargan áreas masivamente desde tile.openstreetmap.org.

## Compilar
Requiere JDK 17 y Android SDK Platform 36.

```bash
./gradlew --no-daemon clean testDebugUnitTest :app:assembleDebug :phone:assembleDebug
```

GitHub Actions compila ambos APK debug y publica una pre-release al terminar correctamente. No son APKs firmados para Google Play.
