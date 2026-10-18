import { GeoSample, SavedBuoy } from '../types/openkayak';
import { calculateDistance, calculateBearing } from './turnAiModel';

export interface RouteAnalysis {
  points: GeoSample[];
  turns: SavedBuoy[];
  repeatedBuoyCount: number;
  isCircuit: boolean;
}

interface TimedPoint {
  point: GeoSample;
  timeMs: number;
}

interface Candidate {
  point: GeoSample;
  timeMs: number;
  angle: number;
  type: 'BUOY' | 'UTURN';
}

interface Cluster {
  latSum: number;
  lonSum: number;
  count: number;
  type: 'BUOY' | 'UTURN';
  angle: number;
  lastTimeMs: number;
}

export class RouteAnalyzer {
  private static readonly MAX_MAP_POINTS = 14000;
  private static readonly TURN_BASELINE_METERS = 12.0;
  private static readonly TURN_THRESHOLD_DEGREES = 62.0;
  private static readonly UTURN_THRESHOLD_DEGREES = 150.0;
  private static readonly EVENT_COOLDOWN_MS = 22000;
  private static readonly EVENT_COOLDOWN_METERS = 22.0;
  private static readonly CLUSTER_RADIUS_METERS = 20.0;
  private static readonly MIN_REPEAT_INTERVAL_MS = 40000;

  public static parseGpxPoints(gpxContent: string): TimedPoint[] {
    const timed: TimedPoint[] = [];
    const pointRegex = /<trkpt\b[^>]*\blat="([^"]+)"[^>]*\blon="([^"]+)"[^>]*>[\s\S]*?<time>([^<]+)<\/time>[\s\S]*?<\/trkpt>/g;
    let match: RegExpExecArray | null;

    while ((match = pointRegex.exec(gpxContent)) !== null) {
      const lat = parseFloat(match[1]);
      const lon = parseFloat(match[2]);
      const timeMs = Date.parse(match[3]);

      if (
        !isNaN(lat) &&
        !isNaN(lon) &&
        !isNaN(timeMs) &&
        lat >= -90 &&
        lat <= 90 &&
        lon >= -180 &&
        lon <= 180 &&
        timeMs > 0
      ) {
        timed.push({ point: { latitude: lat, longitude: lon, timeMs }, timeMs });
      }
    }
    return timed;
  }

  public static analyze(pointsOrGpx: TimedPoint[] | string): RouteAnalysis {
    let timed: TimedPoint[] = [];
    if (typeof pointsOrGpx === 'string') {
      timed = this.parseGpxPoints(pointsOrGpx);
    } else {
      timed = pointsOrGpx;
    }

    if (timed.length === 0) {
      return {
        points: [],
        turns: [],
        repeatedBuoyCount: 0,
        isCircuit: false,
      };
    }

    const cleaned: TimedPoint[] = [];
    for (const next of timed) {
      const previous = cleaned[cleaned.length - 1];
      if (!previous) {
        cleaned.push(next);
      } else {
        const elapsedMs = next.timeMs - previous.timeMs;
        if (elapsedMs <= 0) continue;
        const dist = calculateDistance(previous.point, next.point);
        const speed = dist / (elapsedMs / 1000.0);
        // A kayak track should not contain GNSS teleport spikes.
        if (speed <= 8.0 && (dist >= 0.65 || elapsedMs >= 5000)) {
          cleaned.push(next);
        }
      }
    }

    const stable = this.removeIsolatedGpsSpikes(cleaned);
    const candidates = this.detectTurnCandidates(stable);
    const turns = this.clusterRepeatVisits(candidates);

    let totalMeters = 0;
    for (let i = 0; i < stable.length - 1; i++) {
      totalMeters += calculateDistance(stable[i].point, stable[i + 1].point);
    }

    const closingMeters =
      stable.length >= 3
        ? calculateDistance(stable[0].point, stable[stable.length - 1].point)
        : Number.MAX_VALUE;

    const repeated = turns.filter((t) => t.repetitions >= 2).length;
    const isCircuit =
      totalMeters > 250.0 &&
      closingMeters < Math.max(25.0, totalMeters * 0.04) &&
      turns.length >= 3;

    return {
      points: this.downsample(
        stable.map((s) => s.point),
        this.MAX_MAP_POINTS
      ),
      turns,
      repeatedBuoyCount: repeated,
      isCircuit,
    };
  }

  private static removeIsolatedGpsSpikes(points: TimedPoint[]): TimedPoint[] {
    if (points.length < 3) return points;
    const result: TimedPoint[] = [points[0]];

    for (let index = 1; index < points.length - 1; index++) {
      const previous = points[index - 1];
      const current = points[index];
      const next = points[index + 1];
      const dtBefore = current.timeMs - previous.timeMs;
      const dtAfter = next.timeMs - current.timeMs;

      if (dtBefore >= 1 && dtBefore <= 4000 && dtAfter >= 1 && dtAfter <= 4000) {
        const direct = calculateDistance(previous.point, next.point);
        const detour =
          calculateDistance(previous.point, current.point) +
          calculateDistance(current.point, next.point) -
          direct;
        const sideError = this.perpendicularDistanceMeters(
          current.point,
          previous.point,
          next.point
        );
        if (detour > 18.0 && sideError > 12.0) {
          continue;
        }
      }
      result.push(current);
    }

    result.push(points[points.length - 1]);
    return result;
  }

  private static perpendicularDistanceMeters(
    point: GeoSample,
    start: GeoSample,
    end: GeoSample
  ): number {
    const referenceLat = ((start.latitude + end.latitude) / 2.0 * Math.PI) / 180;
    const metersPerLon = 111320.0 * Math.cos(referenceLat);
    const dx = (end.longitude - start.longitude) * metersPerLon;
    const dy = (end.latitude - start.latitude) * 111320.0;
    const px = (point.longitude - start.longitude) * metersPerLon;
    const py = (point.latitude - start.latitude) * 111320.0;
    const lengthSquared = dx * dx + dy * dy;

    if (lengthSquared < 0.01) return Math.hypot(px, py);
    const projection = Math.max(0.0, Math.min(1.0, (px * dx + py * dy) / lengthSquared));
    return Math.hypot(px - projection * dx, py - projection * dy);
  }

  private static detectTurnCandidates(points: TimedPoint[]): Candidate[] {
    if (points.length < 6) return [];
    const candidates: Candidate[] = [];
    let lastKept: Candidate | null = null;

    for (let pivotIndex = 2; pivotIndex < points.length - 2; pivotIndex++) {
      const beforeIndex = this.indexAtDistance(
        points,
        pivotIndex,
        -1,
        this.TURN_BASELINE_METERS
      );
      if (beforeIndex === null) continue;

      const afterIndex = this.indexAtDistance(
        points,
        pivotIndex,
        1,
        this.TURN_BASELINE_METERS
      );
      if (afterIndex === null) continue;

      const before = points[beforeIndex];
      const pivot = points[pivotIndex];
      const after = points[afterIndex];

      const beforeSeconds = (pivot.timeMs - before.timeMs) / 1000.0;
      const afterSeconds = (after.timeMs - pivot.timeMs) / 1000.0;
      if (
        beforeSeconds < 2.0 ||
        beforeSeconds > 35.0 ||
        afterSeconds < 2.0 ||
        afterSeconds > 35.0
      ) {
        continue;
      }

      const inbound = calculateBearing(before.point, pivot.point);
      const outbound = calculateBearing(pivot.point, after.point);
      const angle = Math.abs((((outbound - inbound + 540.0) % 360.0) - 180.0));

      if (angle < this.TURN_THRESHOLD_DEGREES) continue;

      const candidate: Candidate = {
        point: pivot.point,
        timeMs: pivot.timeMs,
        angle,
        type: angle >= this.UTURN_THRESHOLD_DEGREES ? 'UTURN' : 'BUOY',
      };

      const previous = lastKept;
      if (
        previous &&
        pivot.timeMs - previous.timeMs < this.EVENT_COOLDOWN_MS &&
        calculateDistance(previous.point, pivot.point) < this.EVENT_COOLDOWN_METERS
      ) {
        continue;
      }

      candidates.push(candidate);
      lastKept = candidate;
    }

    return candidates;
  }

  private static indexAtDistance(
    points: TimedPoint[],
    pivotIndex: Int32Array | number,
    direction: number,
    targetMeters: number
  ): number | null {
    let walked = 0.0;
    let index = pivotIndex as number;
    let steps = 0;

    while (true) {
      const next = index + direction;
      if (next < 0 || next >= points.length || steps++ > 90) return null;
      walked += calculateDistance(points[index].point, points[next].point);
      index = next;
      if (walked >= targetMeters) return index;
    }
  }

  private static clusterRepeatVisits(candidates: Candidate[]): SavedBuoy[] {
    const clusters: Cluster[] = [];

    for (const candidate of candidates) {
      const nearby = clusters.find(
        (c) =>
          calculateDistance(
            { latitude: c.latSum / c.count, longitude: c.lonSum / c.count },
            candidate.point
          ) <= this.CLUSTER_RADIUS_METERS
      );

      if (!nearby) {
        clusters.push({
          latSum: candidate.point.latitude,
          lonSum: candidate.point.longitude,
          count: 1,
          type: candidate.type,
          angle: candidate.angle,
          lastTimeMs: candidate.timeMs,
        });
      } else if (
        candidate.timeMs - nearby.lastTimeMs >=
        this.MIN_REPEAT_INTERVAL_MS
      ) {
        nearby.latSum += candidate.point.latitude;
        nearby.lonSum += candidate.point.longitude;
        nearby.count++;
        nearby.lastTimeMs = candidate.timeMs;
        if (candidate.type === 'UTURN' || candidate.angle > nearby.angle) {
          nearby.type = candidate.type;
          nearby.angle = candidate.angle;
        }
      }
    }

    return clusters.map((c, index) => ({
      id: index + 1,
      latitude: c.latSum / c.count,
      longitude: c.lonSum / c.count,
      isTurn: true,
      turnType: c.type,
      angleDegrees: c.angle,
      repetitions: c.count,
    }));
  }

  private static downsample(points: GeoSample[], maximum: number): GeoSample[] {
    if (points.length <= maximum) return points;
    const step = Math.max(1, Math.ceil((points.length - 1) / (maximum - 1)));
    const sampled = points.filter((_, index) => index % step === 0);
    if (sampled[sampled.length - 1] !== points[points.length - 1]) {
      sampled.push(points[points.length - 1]);
    }
    return sampled.slice(0, maximum);
  }
}
