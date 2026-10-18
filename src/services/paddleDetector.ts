/**
 * Experimental peak detector for a watch fixed to the kayak deck.
 * It estimates strokes from acceleration peaks; waves and impacts can generate
 * false positives. The threshold must be calibrated on the actual kayak.
 * Port of PaddleDetector.kt.
 */
export class PaddleDetector {
  private threshold: number;
  private refractoryMs: number;
  private baseline = 0;
  private previousResidual = 0;
  private lastStrokeMs = -Infinity;
  private initialized = false;
  private peakCount = 0;
  private lastPeakMs = -Infinity;
  private lastPeakInterval = 0;

  constructor(threshold = 2.15, refractoryMs = 900) {
    this.threshold = threshold;
    this.refractoryMs = refractoryMs;
  }

  public reset(): void {
    this.baseline = 0;
    this.previousResidual = 0;
    this.lastStrokeMs = -Infinity;
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

    // Require three rhythmically consistent peaks, not a single bump or two arbitrary knocks.
    if (this.peakCount < 3) return false;
    if (this.lastStrokeMs !== -Infinity && elapsedMs - this.lastStrokeMs < this.refractoryMs) {
      return false;
    }

    this.lastStrokeMs = elapsedMs;
    return true;
  }
}
