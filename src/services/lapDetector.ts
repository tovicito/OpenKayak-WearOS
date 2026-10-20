import { GeoSample, Lap, CircuitDefinition } from '../types/openkayak';
import { calculateDistance } from './turnAiModel';

export interface LapDetectorOptions {
  radiusM?: number; // Detection radius around start gate (default 25m)
  minDepartDistanceM?: number; // Distance to paddle away before a return can trigger (default 70m)
  minLapDurationMs?: number; // Min duration to prevent false triggers (default 30000ms / 30s)
  maxAccuracyM?: number; // Discard GPS points with worse accuracy (default 15m)
}

export class LapDetector {
  private startPoint: GeoSample | null = null;
  private circuitName: string = 'Circuito Libre';
  private radiusM: number = 25;
  private minDepartDistanceM: number = 70;
  private minLapDurationMs: number = 30000;
  private maxAccuracyM: number = 15;

  private laps: Lap[] = [];
  private hasDeparted: boolean = false;
  private currentLapNumber: number = 1;
  private currentLapStartMs: number = 0;
  private currentLapStartDistM: number = 0;
  private currentLapStartStrokes: number = 0;
  private lastLapTriggerMs: number = 0;
  private maxSpeedInCurrentLap: number = 0;

  constructor(options?: LapDetectorOptions) {
    if (options?.radiusM) this.radiusM = options.radiusM;
    if (options?.minDepartDistanceM) this.minDepartDistanceM = options.minDepartDistanceM;
    if (options?.minLapDurationMs) this.minLapDurationMs = options.minLapDurationMs;
    if (options?.maxAccuracyM) this.maxAccuracyM = options.maxAccuracyM;
  }

  /**
   * Initializes or resets detector for a new session or circuit
   */
  public start(
    circuit?: CircuitDefinition | null,
    firstPoint?: GeoSample,
    startTimeMs: number = Date.now()
  ): void {
    this.laps = [];
    this.hasDeparted = false;
    this.currentLapNumber = 1;
    this.currentLapStartMs = startTimeMs;
    this.currentLapStartDistM = 0;
    this.currentLapStartStrokes = 0;
    this.lastLapTriggerMs = startTimeMs;
    this.maxSpeedInCurrentLap = 0;

    if (circuit) {
      this.startPoint = circuit.startPoint;
      this.circuitName = circuit.name;
      this.radiusM = circuit.radiusM || 25;
    } else if (firstPoint) {
      this.startPoint = firstPoint;
      this.circuitName = 'Circuito Autodirigido';
    } else {
      this.startPoint = null;
    }
  }

  public setStartGate(point: GeoSample, name?: string): void {
    this.startPoint = point;
    if (name) this.circuitName = name;
  }

  public getCircuitName(): string {
    return this.circuitName;
  }

  public getLaps(): Lap[] {
    return [...this.laps];
  }

  public getCurrentLapNumber(): number {
    return this.currentLapNumber;
  }

  public getBestLap(): Lap | null {
    if (this.laps.length === 0) return null;
    return this.laps.reduce((best, cur) => (cur.durationMs < best.durationMs ? cur : best), this.laps[0]);
  }

  /**
   * Process a GPS point during the workout
   * Returns newly completed Lap if a lap was just detected, null otherwise.
   */
  public onGpsSample(
    point: GeoSample,
    currentTotalDistanceM: number,
    currentTotalStrokes: number,
    currentSpeedKmh: number,
    currentTimeMs: number = Date.now()
  ): Lap | null {
    // If no start gate defined yet, anchor to the first GPS point with decent accuracy
    if (!this.startPoint) {
      if (point.accuracyM && point.accuracyM <= this.maxAccuracyM) {
        this.startPoint = point;
        this.currentLapStartMs = currentTimeMs;
        this.currentLapStartDistM = currentTotalDistanceM;
        this.currentLapStartStrokes = currentTotalStrokes;
      }
      return null;
    }

    // Filter out points with insufficient GPS accuracy to avoid false lap triggers
    if (point.accuracyM && point.accuracyM > this.maxAccuracyM) {
      return null;
    }

    if (currentSpeedKmh > this.maxSpeedInCurrentLap) {
      this.maxSpeedInCurrentLap = currentSpeedKmh;
    }

    const distFromStartGateM = calculateDistance(
      { latitude: this.startPoint.latitude, longitude: this.startPoint.longitude },
      { latitude: point.latitude, longitude: point.longitude }
    );

    // Track departure from start gate
    if (!this.hasDeparted && distFromStartGateM >= this.minDepartDistanceM) {
      this.hasDeparted = true;
    }

    // Check for lap completion: must have departed and returned into gate radius
    if (this.hasDeparted && distFromStartGateM <= this.radiusM) {
      const elapsedInLapMs = currentTimeMs - this.currentLapStartMs;
      const timeSinceLastLapMs = currentTimeMs - this.lastLapTriggerMs;

      if (elapsedInLapMs >= this.minLapDurationMs && timeSinceLastLapMs >= this.minLapDurationMs) {
        return this.completeLap(
          currentTimeMs,
          currentTotalDistanceM,
          currentTotalStrokes
        );
      }
    }

    return null;
  }

  /**
   * Manual lap trigger button (e.g., tap on watch screen or voice command "marcar vuelta")
   */
  public triggerManualLap(
    currentTimeMs: number,
    currentTotalDistanceM: number,
    currentTotalStrokes: number
  ): Lap {
    return this.completeLap(currentTimeMs, currentTotalDistanceM, currentTotalStrokes);
  }

  private completeLap(
    currentTimeMs: number,
    currentTotalDistanceM: number,
    currentTotalStrokes: number
  ): Lap {
    const durationMs = Math.max(1000, currentTimeMs - this.currentLapStartMs);
    const lapDistM = Math.max(0, currentTotalDistanceM - this.currentLapStartDistM);
    const lapStrokes = Math.max(0, currentTotalStrokes - this.currentLapStartStrokes);
    const hours = durationMs / 3600000;
    const avgSpeedKmh = hours > 0 && lapDistM > 0 ? (lapDistM / 1000) / hours : 0;
    const minutes = durationMs / 60000;
    const avgCadenceSpm = minutes > 0 && lapStrokes > 0 ? Math.round(lapStrokes / minutes) : 0;

    const newLap: Lap = {
      lapNumber: this.currentLapNumber,
      startTimeMs: this.currentLapStartMs,
      endTimeMs: currentTimeMs,
      durationMs,
      distanceM: Math.round(lapDistM),
      avgSpeedKmh: Number(avgSpeedKmh.toFixed(2)),
      maxSpeedKmh: Number(this.maxSpeedInCurrentLap.toFixed(2)),
      strokes: lapStrokes,
      avgCadenceSpm,
      isBestLap: false,
    };

    this.laps.push(newLap);

    // Recompute best lap
    const fastestDuration = Math.min(...this.laps.map((l) => l.durationMs));
    this.laps.forEach((l) => {
      l.isBestLap = l.durationMs === fastestDuration && this.laps.length > 1;
    });

    // Advance to next lap
    this.currentLapNumber += 1;
    this.currentLapStartMs = currentTimeMs;
    this.currentLapStartDistM = currentTotalDistanceM;
    this.currentLapStartStrokes = currentTotalStrokes;
    this.lastLapTriggerMs = currentTimeMs;
    this.hasDeparted = false;
    this.maxSpeedInCurrentLap = 0;

    // Trigger haptic vibration on Wear OS
    if (typeof navigator !== 'undefined' && navigator.vibrate) {
      try {
        navigator.vibrate([200, 100, 200]);
      } catch (_) {}
    }

    return newLap;
  }
}
