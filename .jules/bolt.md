## 2025-05-20 - Avoid Unconditional StateFlow Updates in High-Frequency Sensor Callbacks
**Learning:** In Wear OS sensor-driven apps (`SensorManager.SENSOR_DELAY_GAME` at 50-100Hz), calling `MutableStateFlow.update` unconditionally on every sensor event causes atomic CAS operations, object allocations (`copy()`), and downstream flow dispatches to Compose UI on every tick even when the property value hasn't changed.
**Action:** Always check `_state.value.property != newValue` before calling `_state.update { ... }` inside high-frequency (>=50Hz) sensor callbacks.
