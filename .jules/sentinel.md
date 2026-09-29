## 2025-05-18 - Generic Error Messaging in Map Tile Downloader
**Vulnerability:** Raw exception messages (`t.message`) were directly appended to user-facing UI state in `MapTileDownloader.kt`, exposing potential system path details or internal error strings.
**Learning:** Exception messages displayed in UI state should be sanitized to generic messages while retaining detailed exception objects in backend/Android logs.
**Prevention:** Avoid concatenating `t.message` or stack trace details into user-facing state objects or strings.
