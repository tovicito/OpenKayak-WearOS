# Bolt Journal

## 2025-05-18 - Avoid allocations and double conversions in Android High-Frequency Sensor Callbacks
**Learning:** In Wear OS / Android sensor event listeners registered with `SENSOR_DELAY_GAME` (~50-60 Hz), micro-optimizations like keeping calculations strictly single-precision (`Float`), using Kotlin's inline `abs`, and checking current flow state before triggering `MutableStateFlow.update` prevent excessive GC pressure and main/default thread overhead on low-power Wear OS hardware.
**Action:** Always audit `onSensorChanged` callbacks for hidden `Double` conversions, standard library allocations, and redundant state updates.
