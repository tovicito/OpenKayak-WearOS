## 2025-05-15 - Symbol and Icon Content Descriptions in Wear OS Compose
**Learning:** Icon-only and symbol-text buttons (`<`, `>`, `X`) in OpenKayak Wear OS lack native screen reader labels and default to reading literal characters or hardcoded developer IDs like "PauseResume".
**Action:** Always attach explicit `semantics { contentDescription = "..." }` or dynamic `contentDescription` on Compose `Button` and `Icon` components for screen reader accessibility.
