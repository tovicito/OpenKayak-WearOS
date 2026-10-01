## 2025-05-18 - Avoid redundant StateFlow updates and Double conversions in high-frequency Wear OS sensor callbacks

**Learning:** On Wear OS sensors using `SENSOR_DELAY_GAME` (50–100Hz), executing unconditional `MutableStateFlow.update { copy(...) }` or converting floats to `Double` (e.g. `Math.abs` or `sqrt(double)`) on every tick causes unnecessary garbage collection pressure and main thread micro-stutters.
**Action:** Always check current state before invoking `StateFlow.update` inside high-frequency callbacks (`onSensorChanged`), and use `kotlin.math` Float primitives (`kotlin.math.abs`, `kotlin.math.sqrt(Float)`) directly.
