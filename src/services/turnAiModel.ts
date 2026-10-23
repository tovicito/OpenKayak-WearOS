import { GeoSample, TurnPrediction, TurnType } from '../types/openkayak';

export function calculateDistance(a: GeoSample, b: GeoSample): number {
  const R = 6371000; // Earth radius in meters
  const dLat = ((b.latitude - a.latitude) * Math.PI) / 180;
  const dLon = ((b.longitude - a.longitude) * Math.PI) / 180;
  const lat1 = (a.latitude * Math.PI) / 180;
  const lat2 = (b.latitude * Math.PI) / 180;

  const sinDLat2 = Math.sin(dLat / 2);
  const sinDLon2 = Math.sin(dLon / 2);
  const h = sinDLat2 * sinDLat2 + Math.cos(lat1) * Math.cos(lat2) * sinDLon2 * sinDLon2;
  return 2 * R * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}

export function calculateBearing(a: GeoSample, b: GeoSample): number {
  const lat1 = (a.latitude * Math.PI) / 180;
  const lat2 = (b.latitude * Math.PI) / 180;
  const dLon = ((b.longitude - a.longitude) * Math.PI) / 180;

  const y = Math.sin(dLon) * Math.cos(lat2);
  const x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
  const brng = (Math.atan2(y, x) * 180) / Math.PI;
  return (brng + 360) % 360;
}

class SyntheticTurnClassifier {
  private weights: number[][];

  constructor(weights: number[][]) {
    this.weights = weights;
  }

  public predict(angle: number, speed: number, accuracy: number): number[] {
    const x = SyntheticTurnClassifier.features(angle, speed, accuracy);
    const logits = [0, 1, 2].map((c) =>
      this.weights[c].reduce((sum, w, j) => sum + w * x[j], 0)
    );
    const maxLogit = Math.max(...logits);
    const exps = logits.map((l) =>
      Math.exp(Math.max(-30, Math.min(30, l - maxLogit)))
    );
    const total = Math.max(1e-12, exps.reduce((a, b) => a + b, 0));
    return exps.map((e) => e / total);
  }

  private static features(angle: number, speed: number, accuracy: number): number[] {
    const x = Math.max(0, Math.min(1, angle / 180.0));
    return [
      1.0,
      x,
      x * x,
      x * x * x,
      Math.max(0, Math.min(1.5, speed / 10.0)),
      Math.max(0, Math.min(2.0, accuracy / 20.0)),
    ];
  }

  public static train(): SyntheticTurnClassifier {
    interface Example {
      x: number[];
      target: number;
    }
    const examples: Example[] = [];

    for (let angle = 0; angle <= 180; angle += 2) {
      for (const speed of [2.0, 4.0, 7.0, 10.0]) {
        for (const accuracy of [2.5, 6.0, 12.0]) {
          const target = angle >= 160 ? 2 : angle >= 25 ? 1 : 0;
          examples.push({
            x: SyntheticTurnClassifier.features(angle, speed, accuracy),
            target,
          });
        }
      }
    }

    const w: number[][] = [
      new Array(6).fill(0),
      new Array(6).fill(0),
      new Array(6).fill(0),
    ];

    for (let epoch = 0; epoch < 220; epoch++) {
      for (const sample of examples) {
        const logits = [0, 1, 2].map((c) =>
          w[c].reduce((sum, weight, j) => sum + weight * sample.x[j], 0)
        );
        const maxLogit = Math.max(...logits);
        const es = logits.map((l) =>
          Math.exp(Math.max(-30, Math.min(30, l - maxLogit)))
        );
        const total = Math.max(1e-12, es.reduce((a, b) => a + b, 0));

        for (let c = 0; c <= 2; c++) {
          const error = es[c] / total - (sample.target === c ? 1.0 : 0.0);
          for (let j = 0; j < 6; j++) {
            w[c][j] -=
              0.035 *
              (error * sample.x[j] + (j === 0 ? 0.0 : 0.0002 * w[c][j]));
          }
        }
      }
    }

    return new SyntheticTurnClassifier(w);
  }
}

/**
 * Tiny on-device 3-class softmax model trained at startup on deterministic synthetic GPS turns.
 * Port of TurnAiModel.kt.
 */
export class TurnAiModel {
  private window: { point: GeoSample; timeMs: number }[] = [];
  private classifier: SyntheticTurnClassifier = SyntheticTurnClassifier.train();
  private lastInferenceElapsed = -Infinity;
  private lastEventElapsed = -Infinity;
  private lastInputTime = -Infinity;

  public reset(): void {
    this.window = [];
    this.lastInferenceElapsed = -Infinity;
    this.lastEventElapsed = -Infinity;
    this.lastInputTime = -Infinity;
  }

  public onGpsPoint(
    origin: GeoSample | null,
    point: GeoSample,
    accuracyM: number,
    speedKmh: number,
    timestampMs: number,
    elapsedRealtimeMs: number
  ): TurnPrediction | null {
    if (!origin || timestampMs === this.lastInputTime) return null;
    if (
      this.lastInferenceElapsed !== -Infinity &&
      elapsedRealtimeMs - this.lastInferenceElapsed < 1000
    ) {
      return null;
    }

    this.lastInputTime = timestampMs;
    this.lastInferenceElapsed = elapsedRealtimeMs;
    this.window.push({ point, timeMs: timestampMs });

    while (this.window.length > 9) this.window.shift();
    while (
      this.window.length > 5 &&
      timestampMs - this.window[0].timeMs > 9000
    ) {
      this.window.shift();
    }

    if (accuracyM < 0 || accuracyM > 15 || speedKmh < 1.0 || this.window.length < 5) {
      return null;
    }

    const before = this.window[this.window.length - 5];
    const pivot = this.window[this.window.length - 3];
    const after = this.window[this.window.length - 1];

    if (calculateDistance(origin, pivot.point) < 8.0) return null;

    const d1 = calculateDistance(before.point, pivot.point);
    const d2 = calculateDistance(pivot.point, after.point);
    if (d1 < 2.5 || d2 < 2.5) return null;

    const seconds = (after.timeMs - before.timeMs) / 1000.0;
    if (seconds < 2.0 || seconds > 10.0 || ((d1 + d2) / seconds) * 3.6 < 2.0) {
      return null;
    }

    const angle = Math.abs(
      (((calculateBearing(pivot.point, after.point) -
        calculateBearing(before.point, pivot.point) +
        540.0) %
        360.0) -
        180.0)
    );
    if (angle < 8.0) return null;

    const probabilities = this.classifier.predict(angle, speedKmh, accuracyM);
    const kindIndex = angle >= 160.0 ? 2 : angle >= 25.0 ? 1 : 0;
    if (probabilities[kindIndex] < 0.34) return null;

    const cooldown = angle >= 160.0 ? 7000 : 4000;
    if (
      this.lastEventElapsed !== -Infinity &&
      elapsedRealtimeMs - this.lastEventElapsed < cooldown
    ) {
      return null;
    }

    this.lastEventElapsed = elapsedRealtimeMs;
    const turnTypes: TurnType[] = ['CORRECTION', 'TURN', 'UTURN'];
    return {
      latitude: pivot.point.latitude,
      longitude: pivot.point.longitude,
      timestampMs: pivot.timeMs,
      angleDegrees: angle,
      type: turnTypes[kindIndex],
      confidence: probabilities[kindIndex],
    };
  }
}
