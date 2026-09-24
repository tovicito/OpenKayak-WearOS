# Palette's Journal

## 2025-05-18 - Wear OS Compose Icon Buttons Accessibility
**Learning:** Icon-only buttons and single-character buttons (like "<", ">", "X") in Wear OS Jetpack Compose lack clear screen reader context for TalkBack users if contentDescription or semantics are not explicitly provided or if raw text is used.
**Action:** Always provide explicit, localized content descriptions / semantics for navigation and action control buttons in Wear OS screens.
