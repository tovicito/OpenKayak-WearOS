# Palette's Journal - Critical UX & Accessibility Learnings

## 2025-05-18 - [Wear OS Icon & Symbol Buttons Content Descriptions]
**Learning:** In Wear OS Jetpack Compose interfaces, compact navigation and action buttons (such as `<`, `>`, `X`) are often rendered using raw text inside `Button` elements. Without explicit accessibility semantics (`Modifier.semantics { contentDescription = "..." }`), TalkBack screen readers announce only literal characters (e.g., "X" or "less than"), creating an opaque experience for visually impaired users on small watch screens.
**Action:** Always attach `semantics { contentDescription = "..." }` to any text-symbol or icon-based action buttons across all Jetpack Compose screens.
