# OpenKayak · Aplicación Web React 2.2.2

Aplicación completa para piragüismo portada a **React + TypeScript + Tailwind CSS** desde el proyecto original OpenKayak-WearOS (`com.openkayak.app` y `com.openkayak.phone`).

Ofrece una vista dual sincronizada que reúne la pantalla circular de cubierta de piragua (Wear OS) y el Centro de Control del móvil en una sola experiencia interactiva.

## Características principales

### 1. Pantalla de cubierta (Xiaomi Watch 2 Pro / Wear OS)
- **UI oscura de alto contraste** con paleta oficial: Night (`#05090D`), Panel (`#101820`), Mint (`#76F4D0`), Ice (`#9FD9FF`), Quiet (`#8D9DA8`), Orange (`#FFCA7A`), Pink (`#FF8FA3`).
- **Navegación de 7 páginas** con flechas laterales permanentes `‹` y `›`:
  - **Página 0 (En el agua)**: Distancia en km, tiempo transcurrido, velocidad en km/h, pulso en lpm, ritmo por km, estado GPS, puntos de estado y controles INICIAR / PAUSA / FINALIZAR.
  - **Página 1 (Trazado GPS)**: Mapa Leaflet interactivo en vivo con teselas oscuras de OpenStreetMap, posición del kayak en tiempo real, trazado en color personalizable y marcadores de boyas/giros.
  - **Página 2 (Banda BLE)**: Conexión mediante Web Bluetooth API con bandas de pulso BLE estándar (servicio `0x180D`, medición `0x2A37`, soporte de flags 8/16 bits y vigilancia de muestras obsoletas > 12 s). Incluye simulación para entornos sin banda física.
  - **Página 3 (Paladas)**: Algoritmo de detección de picos de aceleración en cubierta (`PaddleDetector`) con periodo refractario (900 ms), cadencia en tiempo real (paladas/min) y soporte de sensores de movimiento `DeviceMotionEvent`.
  - **Página 4 (Boyas y rutas)**: Marcación manual o automática de boyas y giros en U, lista de boyas y opción para deshacer el último punto.
  - **Página 5 (Ajustes)**: Perfil del deportista (peso, edad), sincronización emulada Health Connect, exportación directa de rutas GPX y soporte de paquetes de mapa offline MBTiles.
  - **Página 6 (Historial)**: Entrenamientos guardados con fecha, distancia, duración, paladas, FC media/máx/mín y calorías estimadas.
- **Modo AOD (Always-On Display)**: Negro puro `#000000`, cifras de alto contraste, vista previa circular con viñeta radial y botón «SALIR AOD».
- **Ventana post-entreno (Finish Review Dialog)**: Análisis geométrico de la ruta (`RouteAnalyzer`), detección de giros cerrados, agrupamiento de boyas repetidas, detección de posibles circuitos cerrados y opciones para guardar/borrar entrenamiento y circuito.

### 2. Centro de Control (Companion Móvil)
- **Pestaña Entrenos**: Lista de sesiones con resumen de distancia, tiempo, paladas, FC y acceso a la ventana completa de análisis `WorkoutDetailDialog`:
  - **Pestaña Resumen**: Métricas de esfuerzo, FC y estimación calórica MET según peso corporal.
  - **Pestaña FC**: Gráfico de serie temporal de frecuencia cardíaca.
  - **Pestaña Velocidad**: Gráfico de serie temporal de velocidad en km/h calculada a partir de los puntos GPX.
  - **Pestaña Tabla 10 s**: Muestras temporales agrupadas en intervalos de 10 segundos.
- **Pestaña Mapa y boyas**: Mapa interactivo con selección de coordenadas al hacer clic, creador de boyas (Boya, Giro en U, Punto de ruta), importación/borrado de circuitos GPX y enlace directo para abrir el punto en Google Maps.
- **Pestaña Ajustes**: Configuración de peso, edad, altura, sexo biológico, límite de filtro de precisión GPS (10–65 m), zoom del mapa (14–17) y selector de color del trazado (Naranja `#FFFF8500`, Cian `#FF42D9FF`, Rosa `#FFFF4FA3`).

### 3. Modos de visualización
- **Reloj (Cubierta)**: Chasis circular de reloj deportivo para cubierta de kayak.
- **Centro de Control**: Vista de tableta/móvil con todas las herramientas de análisis.
- **Vista Dual**: Ambos dispositivos en pantalla simultánea con sincronización en tiempo real.
- **Motor GPS dual**: Alterna en un clic entre recorrido de piragüismo simulado (Embalse de Trasona, Asturias) y geolocalización real del dispositivo mediante `navigator.geolocation`.

## Ejecución en desarrollo

```bash
npm run dev
```

La aplicación escucha en el puerto `3000` (`http://0.0.0.0:3000`).
