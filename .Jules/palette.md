## 2025-05-10 - Wear OS Icon and Symbol Button Accessibility
**Learning:** Icon-only and symbol-text buttons on Wear OS (e.g. `<`, `>`, `X`, or action icons) lack accessible names for TalkBack / screen readers when relying on raw text or ambiguous descriptions.
**Action:** Always provide explicit `contentDescription` on Compose `Icon`s or add `.semantics { contentDescription = "..." }` modifiers on symbol-based `Button` components.
