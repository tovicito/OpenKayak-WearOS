## 2025-05-18 - High-Frequency Sensor Callbacks and StateFlow Updates
**Learning:** On Wear OS devices running sensor listeners at `SENSOR_DELAY_GAME` (50-200 Hz), updating `StateFlow` state inside `onSensorChanged` on every single callback triggers constant flow emissions and allocation overhead even when state values are unchanged.
**Action:** Guard state updates in high-frequency sensor handlers with equality checks (`if (_strokeState.value.field != newValue)`) to eliminate redundant StateFlow emissions and thread context switches.
