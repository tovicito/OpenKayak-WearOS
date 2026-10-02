# Palette's Journal

## 2025-05-18 - Compose Wear OS Accessibility Semantics for Icon and Text-Symbol Buttons
**Learning:** Icon-only buttons and single-character symbol buttons (like `<`, `>`, `X`) in Jetpack Compose for Wear OS lack clear accessibility context for screen readers like TalkBack unless explicit content descriptions or `semantics { contentDescription = ... }` are provided.
**Action:** Always provide descriptive `contentDescription` parameters on `Icon` or apply `Modifier.semantics { contentDescription = ... }` on text-symbol button containers.
