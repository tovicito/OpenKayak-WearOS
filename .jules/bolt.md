## 2025-05-20 - High-Frequency Sensor Math Precision
**Learning:** In Wear OS high-frequency sensor callbacks (e.g. `SENSOR_DELAY_GAME` at 20-50Hz), converting floats to doubles for math operations like `sqrt` causes unnecessary double precision conversions on ARM micro-architectures.
**Action:** Use Kotlin's `kotlin.math.sqrt(Float)` directly to keep calculations strictly in single-precision floating-point arithmetic.
