import {
  SavedBuoy,
  SessionSummary,
  AthleteProfile,
  AppSettings,
  FinishedSession,
  GeoSample,
  PulseSample,
  TrackSnapshot,
  CircuitDefinition,
  StructuredWorkout,
} from '../types/openkayak';

const STORAGE_KEYS = {
  TRACK_SNAPSHOT: 'openkayak_track_snapshot',
  BUOYS: 'openkayak_buoys',
  HISTORY: 'openkayak_history',
  CIRCUIT: 'openkayak_circuit',
  CIRCUITS_LIST: 'openkayak_circuits_list',
  WORKOUT_TEMPLATES: 'openkayak_workout_templates',
  ACTIVE_WORKOUT_ID: 'openkayak_active_workout_id',
  PENDING_REVIEW: 'openkayak_pending_review',
  PROFILE: 'openkayak_profile',
  SETTINGS: 'openkayak_settings',
  GPS_RECENT: 'openkayak_gps_recent',
  HR_SAMPLES: 'openkayak_hr_samples',
};

// Initial default settings
export const DEFAULT_ROUTE_COLOR_ORANGE = 'FFFF8500';
export const DEFAULT_ROUTE_COLOR_CYAN = 'FF42D9FF';
export const DEFAULT_ROUTE_COLOR_PINK = 'FFFF4FA3';

export const TRASONA_CENTER = {
  latitude: 43.5585,
  longitude: -5.9102,
};

export const DEFAULT_CIRCUITS: CircuitDefinition[] = [
  {
    id: 'memorial',
    name: 'Memorial',
    startPoint: { latitude: 43.5585, longitude: -5.9102 },
    radiusM: 25,
    lapDistanceEstimatedM: 1850,
    colorHex: '#A855F7',
    isDeletable: true,
    waypoints: [
      { id: 'm1', order: 1, latitude: 43.5585, longitude: -5.9102, label: 'Meta / Salida (Boya 1)' },
      { id: 'm2', order: 2, latitude: 43.5620, longitude: -5.9080, label: 'Boya Giro Norte (Boya 2)' },
      { id: 'm3', order: 3, latitude: 43.5550, longitude: -5.9130, label: 'Boya Giro Sur (Boya 3)' },
    ],
    routeLine: [
      [43.5585, -5.9102],
      [43.5620, -5.9080],
      [43.5550, -5.9130],
      [43.5585, -5.9102],
    ],
    createdAt: Date.now() - 86400000 * 3,
  },
  {
    id: 'trasona-1000',
    name: 'Trasona 1000m Ida y Vuelta',
    startPoint: { latitude: 43.5585, longitude: -5.9102 },
    radiusM: 25,
    lapDistanceEstimatedM: 2000,
    colorHex: '#A855F7',
    isDeletable: true,
    waypoints: [
      { id: 't1', order: 1, latitude: 43.5585, longitude: -5.9102, label: 'Salida 0m (Boya 1)' },
      { id: 't2', order: 2, latitude: 43.5675, longitude: -5.9102, label: 'Viraje 1000m (Boya 2)' },
    ],
    routeLine: [
      [43.5585, -5.9102],
      [43.5675, -5.9102],
      [43.5585, -5.9102],
    ],
    createdAt: Date.now() - 86400000 * 2,
  },
];

export const DEFAULT_STRUCTURED_WORKOUTS: StructuredWorkout[] = [
  {
    id: 'workout-arturito-memorial',
    name: 'Paleo Foto + 3 Vueltas Memorial + Pausa 8km/h + 100m (IA)',
    description: '20 minutos a paleo foto (técnica y lento), 3 vueltas completas al Memorial, pausa indefinida hasta alcanzar 8 km/h y serie final de 100 metros.',
    repeatCycles: 1,
    totalEstimatedDurationSec: 1200 + 1500 + 120 + 30,
    phases: [
      {
        id: 'phase-1-paleofoto',
        name: 'Paleo foto (técnica y lento)',
        type: 'TECHNIQUE',
        triggerType: 'TIME',
        durationSec: 1200, // 20 minutes
        targetSpm: 48,
        targetSpeedKmh: 6.0,
        notes: 'Buscar apoyo firme en agua y torsión de tronco',
      },
      {
        id: 'phase-2-vueltas-memorial',
        name: '3 vueltas al circuito Memorial',
        type: 'LAP_SERIES',
        triggerType: 'LAPS',
        targetLaps: 3,
        targetCircuitName: 'Memorial',
        targetSpm: 72,
        targetSpeedKmh: 10.5,
        notes: 'Mantener ritmo constante en cada vuelta',
      },
      {
        id: 'phase-3-pausa-velocidad',
        name: 'Pausa indefinida hasta superar 8 km/h',
        type: 'PAUSE',
        triggerType: 'SPEED_TRIGGER',
        triggerSpeedKmh: 8.0,
        leadTimeSec: 5,
        notes: 'Pausa o paleo muy lento. Al acelerar y superar 8 km/h (contando 5s previos) se dispara la serie final',
      },
      {
        id: 'phase-4-sprint-100m',
        name: 'Serie final de 100m',
        type: 'DISTANCE_SERIES',
        triggerType: 'DISTANCE',
        targetDistanceM: 100,
        targetSpm: 105,
        targetSpeedKmh: 14.0,
        notes: '¡Sprint a tope hasta completar los 100 metros!',
      },
    ],
  },
  {
    id: 'fartlek-piraguismo',
    name: 'Fartlek Piragüismo (40m suave + Sprints)',
    description: '40 minutos de ritmo suave base, seguido de 5 repeticiones de sprint de 20s y 1 min de recuperación.',
    repeatCycles: 5,
    totalEstimatedDurationSec: 2400 + 5 * (20 + 60) + 300,
    phases: [
      {
        id: 'phase-warmup',
        name: 'Ritmo suave de fondo',
        type: 'STEADY',
        triggerType: 'TIME',
        durationSec: 2400,
        targetSpm: 68,
        targetSpeedKmh: 9.5,
        notes: 'Paleo fluido y constante',
      },
      {
        id: 'phase-sprint',
        name: 'Sprint a máxima intensidad',
        type: 'SPRINT',
        triggerType: 'TIME',
        durationSec: 20,
        targetSpm: 110,
        targetSpeedKmh: 14.5,
        notes: '¡Máxima frecuencia y potencia de palada!',
      },
      {
        id: 'phase-recovery',
        name: 'Recuperación activa suave',
        type: 'RECOVERY',
        triggerType: 'TIME',
        durationSec: 60,
        targetSpm: 60,
        targetSpeedKmh: 7.0,
        notes: 'Soltar hombros y respiración profunda',
      },
      {
        id: 'phase-cooldown',
        name: 'Vuelta a la calma / Enfriamiento',
        type: 'COOLDOWN',
        triggerType: 'TIME',
        durationSec: 300,
        targetSpm: 55,
        targetSpeedKmh: 6.5,
        notes: 'Deslizar el kayak suavemente',
      },
    ],
  },
  {
    id: 'series-velocidad-200',
    name: 'Series Velocidad (20s sprint / 1m recup x 6)',
    description: 'Calentamiento de 10 min seguido de 6 repeticiones de 20 segundos a tope y 1 minuto de recuperación.',
    repeatCycles: 6,
    totalEstimatedDurationSec: 600 + 6 * (20 + 60) + 300,
    phases: [
      {
        id: 'phase-v-warmup',
        name: 'Calentamiento progresivo',
        type: 'WARMUP',
        triggerType: 'TIME',
        durationSec: 600,
        targetSpm: 68,
        targetSpeedKmh: 9.0,
        notes: 'Movilidad articular y paleo suave',
      },
      {
        id: 'phase-v-sprint',
        name: 'Sprint explosivo 20s',
        type: 'SPRINT',
        triggerType: 'TIME',
        durationSec: 20,
        targetSpm: 115,
        targetSpeedKmh: 15.0,
        notes: 'Ataque limpio y torsión de tronco',
      },
      {
        id: 'phase-v-recovery',
        name: 'Recuperación 1 minuto',
        type: 'RECOVERY',
        triggerType: 'TIME',
        durationSec: 60,
        targetSpm: 55,
        targetSpeedKmh: 6.5,
        notes: 'Recuperar aire y deslizar',
      },
      {
        id: 'phase-v-cooldown',
        name: 'Enfriamiento',
        type: 'COOLDOWN',
        triggerType: 'TIME',
        durationSec: 300,
        targetSpm: 55,
        targetSpeedKmh: 6.5,
        notes: 'Paleo regenerativo final',
      },
    ],
  },
  {
    id: 'test-1000m-trasona',
    name: 'Test 1000m Trasona',
    description: 'Prueba cronometrada de 1000m en pista recta con calentamiento y descalentamiento.',
    repeatCycles: 1,
    totalEstimatedDurationSec: 900 + 240 + 600,
    phases: [
      {
        id: 'phase-1000-warmup',
        name: 'Calentamiento completo',
        type: 'WARMUP',
        triggerType: 'TIME',
        durationSec: 900,
        targetSpm: 70,
        targetSpeedKmh: 9.8,
      },
      {
        id: 'phase-1000-sprint',
        name: 'Test 1000m a ritmo máximo',
        type: 'SPRINT',
        triggerType: 'DISTANCE',
        targetDistanceM: 1000,
        targetSpm: 88,
        targetSpeedKmh: 12.8,
      },
      {
        id: 'phase-1000-cooldown',
        name: 'Soltar y enfriar',
        type: 'COOLDOWN',
        triggerType: 'TIME',
        durationSec: 600,
        targetSpm: 55,
        targetSpeedKmh: 6.5,
      },
    ],
  },
];

// Generate realistic starter track at Trasona
function generateInitialTrasonaTrack(): { points: GeoSample[]; gpx: string } {
  const points: GeoSample[] = [];
  const baseLat = 43.5585;
  const baseLon = -5.9102;
  const baseTime = Date.now() - 3600 * 1000 * 24; // Yesterday

  // 1000m lane back and forth with U-turn at 1000m
  for (let i = 0; i <= 60; i++) {
    const frac = i / 60;
    const lat = baseLat + frac * 0.009;
    const lon = baseLon + frac * 0.004;
    points.push({
      latitude: lat,
      longitude: lon,
      timeMs: baseTime + i * 5000,
      accuracyM: 3.5,
      speedKmh: 11.2,
      heartRateBpm: 152 + (i % 6),
    });
  }
  // Turn around
  points.push({
    latitude: baseLat + 0.0093,
    longitude: baseLon + 0.0044,
    timeMs: baseTime + 310000,
    accuracyM: 4.0,
    speedKmh: 6.5,
    heartRateBpm: 165,
  });
  // Return lane
  for (let i = 60; i >= 0; i--) {
    const frac = i / 60;
    const lat = baseLat + frac * 0.009 + 0.0002;
    const lon = baseLon + frac * 0.004 + 0.0001;
    points.push({
      latitude: lat,
      longitude: lon,
      timeMs: baseTime + 320000 + (60 - i) * 5000,
      accuracyM: 3.2,
      speedKmh: 11.5,
      heartRateBpm: 158 + (i % 5),
    });
  }

  const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="OpenKayak-WearOS" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>Entrenamiento K1 2000m Trasona</name><trkseg>
${points
  .map(
    (p) =>
      `<trkpt lat="${p.latitude.toFixed(7)}" lon="${p.longitude.toFixed(
        7
      )}"><time>${new Date(p.timeMs || 0).toISOString()}</time></trkpt>`
  )
  .join('\n')}
</trkseg></trk></gpx>`;

  return { points, gpx };
}

export class StorageService {
  public static getAthleteProfile(): AthleteProfile {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.PROFILE);
      if (data) return JSON.parse(data);
    } catch (_) {}
    return {
      weightKg: 72.5,
      ageYears: 28,
      heightCm: 180,
      biologicalSex: 'MALE',
    };
  }

  public static saveAthleteProfile(profile: Partial<AthleteProfile>): void {
    const current = this.getAthleteProfile();
    const updated = { ...current, ...profile };
    localStorage.setItem(STORAGE_KEYS.PROFILE, JSON.stringify(updated));
  }

  public static getAppSettings(): AppSettings {
    const profile = this.getAthleteProfile();
    try {
      const data = localStorage.getItem(STORAGE_KEYS.SETTINGS);
      if (data) {
        const parsed = JSON.parse(data);
        return {
          ...parsed,
          weightKg: profile.weightKg,
          ageYears: profile.ageYears,
          heightCm: profile.heightCm,
          biologicalSex: profile.biologicalSex,
          speedUnit: parsed.speedUnit || 'kmh',
          autoLapEnabled: parsed.autoLapEnabled !== undefined ? parsed.autoLapEnabled : true,
          autoLapRadiusM: parsed.autoLapRadiusM || 25,
          activeCircuitId: parsed.activeCircuitId || 'memorial',
          alertMode: parsed.alertMode || 'VOICE_AND_VIBRATION',
        };
      }
    } catch (_) {}
    return {
      weightKg: profile.weightKg,
      ageYears: profile.ageYears,
      heightCm: profile.heightCm,
      biologicalSex: profile.biologicalSex,
      mapZoom: 16,
      gpsAccuracyLimitM: 35,
      routeColorHex: DEFAULT_ROUTE_COLOR_ORANGE,
      speedUnit: 'kmh',
      autoLapEnabled: true,
      autoLapRadiusM: 25,
      activeCircuitId: 'memorial',
      alertMode: 'VOICE_AND_VIBRATION',
    };
  }

  public static saveAppSettings(settings: Partial<AppSettings>): void {
    const current = this.getAppSettings();
    const updated = { ...current, ...settings };
    if (settings.weightKg || settings.ageYears || settings.heightCm || settings.biologicalSex) {
      this.saveAthleteProfile({
        weightKg: updated.weightKg,
        ageYears: updated.ageYears,
        heightCm: updated.heightCm,
        biologicalSex: updated.biologicalSex as any,
      });
    }
    localStorage.setItem(STORAGE_KEYS.SETTINGS, JSON.stringify(updated));
  }

  public static getBuoys(): SavedBuoy[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.BUOYS);
      if (data) return JSON.parse(data);
    } catch (_) {}
    // Initial sample buoys for Trasona
    const defaultBuoys: SavedBuoy[] = [
      {
        id: 1,
        latitude: 43.5585,
        longitude: -5.9102,
        isTurn: false,
        turnType: 'PUNTO DE RUTA',
        angleDegrees: 0,
        repetitions: 1,
      },
      {
        id: 2,
        latitude: 43.5675,
        longitude: -5.9062,
        isTurn: true,
        turnType: 'UTURN',
        angleDegrees: 174,
        repetitions: 2,
      },
      {
        id: 3,
        latitude: 43.563,
        longitude: -5.9082,
        isTurn: true,
        turnType: 'BUOY',
        angleDegrees: 88,
        repetitions: 1,
      },
    ];
    this.saveBuoys(defaultBuoys);
    return defaultBuoys;
  }

  public static saveBuoys(buoys: SavedBuoy[]): void {
    localStorage.setItem(STORAGE_KEYS.BUOYS, JSON.stringify(buoys));
  }

  public static addBuoy(latitude: number, longitude: number, turnType: 'BUOY' | 'UTURN' | 'GIRO' | 'PUNTO DE RUTA'): SavedBuoy {
    const list = this.getBuoys();
    const newBuoy: SavedBuoy = {
      id: Date.now(),
      latitude,
      longitude,
      isTurn: turnType !== 'PUNTO DE RUTA',
      turnType,
      angleDegrees: turnType === 'UTURN' ? 175 : turnType === 'BUOY' ? 90 : 0,
      repetitions: 1,
    };
    list.push(newBuoy);
    this.saveBuoys(list);
    return newBuoy;
  }

  public static removeBuoy(id: number): void {
    const list = this.getBuoys().filter((b) => b.id !== id);
    this.saveBuoys(list);
  }

  public static removeLastBuoy(): boolean {
    const list = this.getBuoys();
    if (list.length === 0) return false;
    list.pop();
    this.saveBuoys(list);
    return true;
  }

  public static getWorkouts(): SessionSummary[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.HISTORY);
      if (data) return JSON.parse(data);
    } catch (_) {}
    // Seed initial demo session
    const sample = generateInitialTrasonaTrack();
    const demo: SessionSummary = {
      startAt: Date.now() - 3600 * 1000 * 24,
      endAt: Date.now() - 3600 * 1000 * 24 + 625000,
      elapsedMs: 625000, // 10 min 25 s
      distanceM: 2040.5,
      strokes: 680,
      trackPath: 'piraguismo-trasona-2000m.gpx',
      averageBpm: 154,
      maxBpm: 172,
      minBpm: 132,
      calories: 148,
      gpxContent: sample.gpx,
    };
    this.saveWorkouts([demo]);
    return [demo];
  }

  public static saveWorkouts(workouts: SessionSummary[]): void {
    localStorage.setItem(STORAGE_KEYS.HISTORY, JSON.stringify(workouts));
  }

  public static recordWorkout(workout: SessionSummary): void {
    const list = this.getWorkouts().filter((w) => w.startAt !== workout.startAt);
    list.unshift(workout);
    this.saveWorkouts(list.slice(0, 50));
  }

  public static deleteWorkout(startAt: number): void {
    const list = this.getWorkouts().filter((w) => w.startAt !== startAt);
    this.saveWorkouts(list);
  }

  public static getCircuit(): { points: GeoSample[]; label: string; gpxContent?: string } | null {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.CIRCUIT);
      if (data) return JSON.parse(data);
    } catch (_) {}
    const sample = generateInitialTrasonaTrack();
    const initialCircuit = {
      points: sample.points,
      label: 'Circuito 2000m Trasona',
      gpxContent: sample.gpx,
    };
    this.saveCircuit(initialCircuit.points, initialCircuit.label, initialCircuit.gpxContent);
    return initialCircuit;
  }

  public static saveCircuit(points: GeoSample[], label: string, gpxContent?: string): void {
    localStorage.setItem(
      STORAGE_KEYS.CIRCUIT,
      JSON.stringify({ points, label, gpxContent, savedAt: Date.now() })
    );
  }

  public static clearCircuit(): void {
    localStorage.removeItem(STORAGE_KEYS.CIRCUIT);
  }

  public static getPendingReview(): FinishedSession | null {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.PENDING_REVIEW);
      if (data) return JSON.parse(data);
    } catch (_) {}
    return null;
  }

  public static setPendingReview(review: FinishedSession | null): void {
    if (review) {
      localStorage.setItem(STORAGE_KEYS.PENDING_REVIEW, JSON.stringify(review));
    } else {
      localStorage.removeItem(STORAGE_KEYS.PENDING_REVIEW);
    }
  }

  public static getRecentPoints(): GeoSample[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.GPS_RECENT);
      if (data) return JSON.parse(data);
    } catch (_) {}
    return [];
  }

  public static appendRecentPoint(point: GeoSample): void {
    const current = this.getRecentPoints();
    current.push(point);
    if (current.length > 500) current.shift();
    localStorage.setItem(STORAGE_KEYS.GPS_RECENT, JSON.stringify(current));
  }

  public static clearRecentPoints(): void {
    localStorage.removeItem(STORAGE_KEYS.GPS_RECENT);
  }

  public static getHeartRateSamples(): PulseSample[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.HR_SAMPLES);
      if (data) return JSON.parse(data);
    } catch (_) {}
    return [];
  }

  public static appendHeartRateSample(sample: PulseSample): void {
    const current = this.getHeartRateSamples();
    current.push(sample);
    if (current.length > 600) current.shift();
    localStorage.setItem(STORAGE_KEYS.HR_SAMPLES, JSON.stringify(current));
  }

  public static clearHeartRateSamples(): void {
    localStorage.removeItem(STORAGE_KEYS.HR_SAMPLES);
  }

  // --- Circuits Management (Auto-Lap) ---
  public static getCircuits(): CircuitDefinition[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.CIRCUITS_LIST);
      if (data !== null) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed)) return parsed;
      }
    } catch (_) {}
    // Seed initial default circuits only if never saved
    this.saveCircuits(DEFAULT_CIRCUITS);
    return DEFAULT_CIRCUITS;
  }

  public static saveCircuits(circuits: CircuitDefinition[]): void {
    localStorage.setItem(STORAGE_KEYS.CIRCUITS_LIST, JSON.stringify(circuits));
  }

  public static saveCircuitDefinition(circuit: CircuitDefinition): void {
    const list = this.getCircuits();
    const idx = list.findIndex((c) => c.id === circuit.id);
    if (idx >= 0) {
      list[idx] = circuit;
    } else {
      list.push(circuit);
    }
    this.saveCircuits(list);
  }

  public static deleteCircuitDefinition(id: string): void {
    const list = this.getCircuits().filter((c) => c.id !== id);
    this.saveCircuits(list);
  }

  public static clearAllCircuits(): void {
    this.saveCircuits([]);
  }

  public static resetDefaultCircuits(): void {
    this.saveCircuits(DEFAULT_CIRCUITS);
  }

  // --- Structured Workouts Management ---
  public static getStructuredWorkouts(): StructuredWorkout[] {
    try {
      const data = localStorage.getItem(STORAGE_KEYS.WORKOUT_TEMPLATES);
      if (data) {
        const parsed = JSON.parse(data);
        if (Array.isArray(parsed) && parsed.length > 0) return parsed;
      }
    } catch (_) {}
    // Seed default structured workouts
    this.saveStructuredWorkouts(DEFAULT_STRUCTURED_WORKOUTS);
    return DEFAULT_STRUCTURED_WORKOUTS;
  }

  public static saveStructuredWorkouts(workouts: StructuredWorkout[]): void {
    localStorage.setItem(STORAGE_KEYS.WORKOUT_TEMPLATES, JSON.stringify(workouts));
  }

  public static saveStructuredWorkout(workout: StructuredWorkout): void {
    const list = this.getStructuredWorkouts();
    const idx = list.findIndex((w) => w.id === workout.id);
    if (idx >= 0) {
      list[idx] = workout;
    } else {
      list.push(workout);
    }
    this.saveStructuredWorkouts(list);
  }

  public static deleteStructuredWorkout(id: string): void {
    const list = this.getStructuredWorkouts().filter((w) => w.id !== id);
    this.saveStructuredWorkouts(list);
  }

  public static getActiveWorkoutId(): string | null {
    try {
      return localStorage.getItem(STORAGE_KEYS.ACTIVE_WORKOUT_ID);
    } catch (_) {
      return null;
    }
  }

  public static setActiveWorkoutId(id: string | null): void {
    if (id) {
      localStorage.setItem(STORAGE_KEYS.ACTIVE_WORKOUT_ID, id);
    } else {
      localStorage.removeItem(STORAGE_KEYS.ACTIVE_WORKOUT_ID);
    }
  }
}
