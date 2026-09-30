## 2025-05-18 - Wear OS Navigation Button Accessibility
**Learning:** In Wear OS Jetpack Compose interfaces, compact overlay navigation buttons (such as "<" and ">" arrow buttons) lack descriptive text by default, leaving TalkBack screen readers unable to convey their navigation function to visually impaired users.
**Action:** Always attach explicit `semantics { contentDescription = "..." }` or `Modifier.semantics` to icon-only and arrow navigation controls in Compose components.
