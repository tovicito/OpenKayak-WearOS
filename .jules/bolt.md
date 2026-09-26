# Bolt's Performance Journal

## 2025-05-18 - Avoid Collection & String Formatting Allocations in Sequence Analysis
**Learning:** Performing intermediate `.map { ... }`, `.toSet()`, and string join/split operations inside nested sequence comparison loops creates high GC overhead during route/circuit analysis.
**Action:** Use index-based direct iteration for slice checks (e.g. unique ID counting and rotation comparison) and cache intermediate ID structures directly rather than re-parsing formatted signature strings.
