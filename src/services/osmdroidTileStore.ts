/**
 * Osmdroid-style tile caching engine for Wear OS local storage.
 * Ports the caching behavior of OnlineRouteMap.kt and OfflineMapStore.kt:
 * - Caches every online tile viewed into persistent storage ('osmdroid-wearos-tiles').
 * - Serves cached tiles when offline or revisited.
 * - Tracks tile count and storage status matching Wear OS storage limits.
 */

const CACHE_NAME = 'osmdroid-wearos-tiles';
const MAX_TILES_LIMIT = 512;

export class OsmdroidTileStore {
  private static cachedCount = 0;
  private static listeners: ((count: number) => void)[] = [];

  public static async init(): Promise<number> {
    if (typeof window === 'undefined' || !('caches' in window)) return 0;
    try {
      const cache = await caches.open(CACHE_NAME);
      const keys = await cache.keys();
      this.cachedCount = keys.length;
      this.notifyListeners();
      return this.cachedCount;
    } catch (_) {
      return 0;
    }
  }

  public static getCachedCount(): number {
    return this.cachedCount;
  }

  public static subscribe(listener: (count: number) => void): () => void {
    this.listeners.push(listener);
    listener(this.cachedCount);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  private static notifyListeners(): void {
    this.listeners.forEach((l) => l(this.cachedCount));
  }

  /**
   * Fetches and caches an osmdroid tile URL into persistent Wear OS storage
   */
  public static async fetchAndCacheTile(tileUrl: string): Promise<string> {
    if (typeof window === 'undefined' || !('caches' in window)) return tileUrl;

    try {
      const cache = await caches.open(CACHE_NAME);
      const matched = await cache.match(tileUrl);
      if (matched) {
        const blob = await matched.blob();
        return URL.createObjectURL(blob);
      }

      // Fetch from network and store in osmdroid wearOS cache
      const response = await fetch(tileUrl, { mode: 'cors' });
      if (response.ok) {
        // Enforce LRU limit if exceeds Wear OS tile cache limit
        const keys = await cache.keys();
        if (keys.length >= MAX_TILES_LIMIT) {
          await cache.delete(keys[0]);
        }

        await cache.put(tileUrl, response.clone());
        this.cachedCount = (await cache.keys()).length;
        this.notifyListeners();

        const blob = await response.blob();
        return URL.createObjectURL(blob);
      }
    } catch (_) {
      // If offline, check if tile was previously cached
      try {
        const cache = await caches.open(CACHE_NAME);
        const fallback = await cache.match(tileUrl);
        if (fallback) {
          const blob = await fallback.blob();
          return URL.createObjectURL(blob);
        }
      } catch (_) {}
    }

    return tileUrl;
  }

  public static async clearCache(): Promise<void> {
    if (typeof window === 'undefined' || !('caches' in window)) return;
    try {
      await caches.delete(CACHE_NAME);
      this.cachedCount = 0;
      this.notifyListeners();
    } catch (_) {}
  }
}

// Auto-initialize count on load
if (typeof window !== 'undefined') {
  OsmdroidTileStore.init();
}
