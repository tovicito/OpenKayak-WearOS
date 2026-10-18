import {
  SavedBuoy,
  SessionSummary,
  AthleteProfile,
  AppSettings,
  FinishedSession,
  GeoSample,
  PulseSample,
  TrackSnapshot,
} from '../types/openkayak';

const STORAGE_KEYS = {
  TRACK_SNAPSHOT: 'openkayak_track_snapshot',
  BUOYS: 'openkayak_buoys',
  HISTORY: 'openkayak_history',
  CIRCUIT: 'openkayak_circuit',
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
}
