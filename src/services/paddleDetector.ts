/**
 * Experimental peak detector for a watch fixed to the kayak deck.
 * It estimates strokes from acceleration peaks in real-time.
 * The refractory period has been removed for immediate instant detection without cooldown delay.
 */
export class PaddleDetector {
  private threshold: number;
  private baseline = 0;
  private previousResidual = 0;
  private initialized = false;
  private peakCount = 0;
  private lastPeakMs = -Infinity;
  private lastPeakInterval = 0;

  constructor(threshold = 2.15) {
    this.threshold = threshold;
  }

  public reset(): void {
    this.baseline = 0;
    this.previousResidual = 0;
    this.initialized = false;
    this.peakCount = 0;
    this.lastPeakMs = -Infinity;
    this.lastPeakInterval = 0;
  }

  public onSample(x: number, y: number, z: number, elapsedMs: number): boolean {
    const magnitude = Math.sqrt(x * x + y * y + z * z);
    if (!Number.isFinite(magnitude)) return false;

    if (!this.initialized) {
      this.baseline = magnitude;
      this.initialized = true;
      this.previousResidual = 0;
      return false;
    }

    const residual = magnitude - this.baseline;
    this.baseline += (magnitude - this.baseline) * 0.015;
    const risingPeak = residual >= this.threshold && this.previousResidual < this.threshold * 0.55;
    this.previousResidual = residual;

    if (!risingPeak) return false;

    if (this.lastPeakMs !== -Infinity) {
      const interval = elapsedMs - this.lastPeakMs;
      if (
        interval < 350 ||
        interval > 2200 ||
        (this.lastPeakInterval > 0 &&
          Math.abs(interval - this.lastPeakInterval) >
            Math.max(250, (this.lastPeakInterval * 45) / 100))
      ) {
        this.peakCount = 1;
        this.lastPeakInterval = 0;
        this.lastPeakMs = elapsedMs;
        return false;
      }
      this.peakCount++;
      this.lastPeakInterval = interval;
    } else {
      this.peakCount = 1;
      this.lastPeakInterval = 0;
    }

    this.lastPeakMs = elapsedMs;

    // Require rhythmically consistent peaks
    if (this.peakCount < 3) return false;
    // Refractory period check removed for immediate responsive detection

    return true;
  }
}
