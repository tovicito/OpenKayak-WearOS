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
  laps?: Lap[];
  structuredWorkoutName?: string;
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
  laps?: Lap[];
  structuredWorkoutName?: string;
}

export interface AthleteProfile {
  weightKg: number;
  ageYears: number;
  heightCm: number;
  biologicalSex: 'MALE' | 'FEMALE' | 'OTHER';
}

export type SpeedUnit = 'kmh' | 'ms' | 'knots' | 'pace_km' | 'pace_500m';
export type AlertMode = 'VOICE_AND_VIBRATION' | 'VOICE_ONLY' | 'VIBRATION_ONLY' | 'MUTED';

export interface Lap {
  lapNumber: number;
  startTimeMs: number;
  endTimeMs: number;
  durationMs: number;
  distanceM: number;
  avgSpeedKmh: number;
  maxSpeedKmh: number;
  strokes: number;
  avgCadenceSpm: number;
  isBestLap?: boolean;
}

export interface CircuitWaypoint {
  id: string;
  order: number;
  latitude: number;
  longitude: number;
  label: string;
}

export interface CircuitDefinition {
  id: string;
  name: string;
  startPoint: GeoSample;
  radiusM: number;
  lapDistanceEstimatedM?: number;
  points?: GeoSample[];
  waypoints?: CircuitWaypoint[];
  routeLine?: Array<[number, number]>;
  colorHex?: string;
  isDeletable?: boolean;
  createdAt: number;
}

export type WorkoutPhaseType =
  | 'WARMUP'
  | 'STEADY'
  | 'SPRINT'
  | 'RECOVERY'
  | 'COOLDOWN'
  | 'TECHNIQUE'
  | 'PAUSE'
  | 'DISTANCE_SERIES'
  | 'LAP_SERIES';

export type PhaseTriggerType = 'TIME' | 'LAPS' | 'SPEED_TRIGGER' | 'DISTANCE' | 'MANUAL';

export interface WorkoutPhase {
  id: string;
  name: string;
  type: WorkoutPhaseType;
  triggerType: PhaseTriggerType;
  durationSec?: number;
  targetLaps?: number;
  targetCircuitName?: string;
  targetCircuitId?: string;
  targetDistanceM?: number;
  triggerSpeedKmh?: number;
  leadTimeSec?: number; // e.g. 5 seconds before speed threshold reached
  targetSpm?: number;
  targetSpeedKmh?: number;
  notes?: string;
}

export interface StructuredWorkout {
  id: string;
  name: string;
  description: string;
  phases: WorkoutPhase[];
  repeatCycles: number;
  totalEstimatedDurationSec: number;
  createdAt?: number;
}

export interface ActiveWorkoutState {
  workoutId: string;
  workoutName: string;
  currentPhaseIndex: number;
  currentPhaseName: string;
  currentPhaseType: WorkoutPhaseType;
  triggerType: PhaseTriggerType;
  phaseElapsedSec: number;
  phaseDurationSec?: number;
  phaseRemainingSec?: number;
  phaseProgressPercent: number;
  phaseMetricDisplay: string;
  // Dynamic triggers tracking:
  lapsCompletedInPhase?: number;
  targetLapsInPhase?: number;
  distanceCoveredInPhaseM?: number;
  targetDistanceInPhaseM?: number;
  waitingForSpeedKmh?: number;
  speedTriggerArmed?: boolean;
  currentCycle: number;
  totalCycles: number;
  isCompleted: boolean;
}

export interface AppSettings {
  weightKg: number;
  ageYears: number;
  heightCm: number;
  biologicalSex: string;
  mapZoom: number;
  gpsAccuracyLimitM: number;
  routeColorHex: string;
  speedUnit: SpeedUnit;
  autoLapEnabled: boolean;
  autoLapRadiusM: number;
  activeCircuitId?: string;
  alertMode: AlertMode;
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
  laps?: Lap[];
  structuredWorkoutName?: string;
}
