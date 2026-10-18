export interface GeoSample {
  latitude: number;
  longitude: number;
  timeMs?: number;
  accuracyM?: number;
  speedKmh?: number;
  heartRateBpm?: number;
}

export interface PulseSample {
  timeMs: number;
  bpm: number;
}

export interface SavedBuoy {
  id: number;
  latitude: number;
  longitude: number;
  isTurn: boolean;
  turnType: 'BUOY' | 'UTURN' | 'GIRO' | 'PUNTO DE RUTA';
  angleDegrees: number;
  repetitions: number;
}

export interface TrackSnapshot {
  active: boolean;
  paused: boolean;
  elapsedMs: number;
  distanceM: number;
  speedKmh: number;
  strokes: number;
  cadenceSpm: number;
  pulse: number;
  latitude: number | null;
  longitude: number | null;
  accuracyM: number;
  gpsStatus: string;
  trackFilePath: string | null;
  startAt: number;
  endAt: number;
}

export interface FinishedSession {
  startAt: number;
  endAt: number;
  elapsedMs: number;
  distanceM: number;
  strokes: number;
  averageBpm: number;
  maxBpm: number;
  minBpm: number;
  calories: number;
  trackPath: string;
  points: GeoSample[];
  turns: SavedBuoy[];
  repeatedBuoyCount: number;
  isCircuit: boolean;
}

export interface SessionSummary {
  startAt: number;
  endAt: number;
  elapsedMs: number;
  distanceM: number;
  strokes: number;
  trackPath: string;
  averageBpm: number;
  maxBpm: number;
  minBpm: number;
  calories: number;
  gpxContent?: string;
  heartRateSamples?: PulseSample[];
}

export interface AthleteProfile {
  weightKg: number;
  ageYears: number;
  heightCm: number;
  biologicalSex: 'MALE' | 'FEMALE' | 'OTHER';
}

export interface AppSettings {
  weightKg: number;
  ageYears: number;
  heightCm: number;
  biologicalSex: string;
  mapZoom: number;
  gpsAccuracyLimitM: number;
  routeColorHex: string;
}

export type BlePhase = 'IDLE' | 'SCANNING' | 'CONNECTING' | 'CONNECTED' | 'ERROR';

export interface BleCandidate {
  name: string;
  address: string;
}

export interface BleHrState {
  phase: BlePhase;
  candidates: BleCandidate[];
  name: string;
  address: string;
  bpm: number;
  lastSampleAt: number;
  notificationsEnabled: boolean;
  detail: string;
}

export type TurnType = 'CORRECTION' | 'TURN' | 'UTURN';

export interface TurnPrediction {
  latitude: number;
  longitude: number;
  timestampMs: number;
  angleDegrees: number;
  type: TurnType;
  confidence: number;
}

export interface PhoneWorkout {
  startAt: number;
  endAt: number;
  elapsedMs: number;
  distanceM: number;
  strokes: number;
  trackPath: string;
  gpxPath?: string;
  gpxContent?: string;
  averageBpm: number;
  maxBpm: number;
  minBpm: number;
  calories: number;
  heartRateSamples: { timeMs: number; value: number }[];
}
