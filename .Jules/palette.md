## 2025-05-18 - Accessibility Content Descriptions in Wear OS Compose UI
**Learning:** Icon-only buttons and single-character buttons (like `<`, `>`, `X`) in Wear OS Jetpack Compose apps are inaccessible to TalkBack screen readers unless meaningful `contentDescription` string resources or labels are assigned.
**Action:** Always provide explicit, localized, action-oriented `contentDescription` text for interactive icon/text-symbol buttons instead of raw string placeholders or null descriptions.
