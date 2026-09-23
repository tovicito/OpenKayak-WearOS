## 2025-05-18 - Wear OS Compose Accessibility Semantics
**Learning:** In Wear OS Compose apps with compact single-character or icon buttons ('X', '<', '>'), TalkBack screen readers announce raw characters or static icon descriptions unless explicit content descriptions or semantics are attached.
**Action:** Always provide localized, dynamic `contentDescription` on Compose `Icon`s and `Modifier.semantics { contentDescription = ... }` on symbol buttons for workout controls and navigation.
