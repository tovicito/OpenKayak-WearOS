import React, { useState, useEffect, useRef } from 'react';
import {
  TrackSnapshot,
  BleHrState,
  SavedBuoy,
  FinishedSession,
  GeoSample,
  PulseSample,
  SessionSummary,
  AppSettings,
  CircuitDefinition,
  StructuredWorkout,
  ActiveWorkoutState,
  Lap,
  AlertMode,
  SpeedUnit,
} from './types/openkayak';
import { WatchDeckView } from './components/WatchDeckView';
import { PhoneControlCenter } from './components/PhoneControlCenter';
import { PaddleDetector } from './services/paddleDetector';
import { TurnAiModel, calculateDistance } from './services/turnAiModel';
import { RouteAnalyzer } from './services/routeAnalyzer';
import { BleHeartRateManager } from './services/heartRateBle';
import { StorageService, TRASONA_CENTER } from './services/storage';
import { GpxExporter } from './services/gpxExporter';
import { soundEffects } from './services/soundEffects';
import { LapDetector } from './services/lapDetector';
import { WorkoutIntervalEngine } from './services/workoutIntervalEngine';

export const App: React.FC = () => {
  // View mode: 'watch' | 'phone' | 'dual'
  const [viewMode, setViewMode] = useState<'watch' | 'phone' | 'dual'>('dual');
  const [gpsSimMode, setGpsSimMode] = useState<boolean>(true);

  // Settings
  const [settings, setSettings] = useState<AppSettings>(() =>
    StorageService.getAppSettings()
  );

  // Core tracking snapshot
  const [snapshot, setSnapshot] = useState<TrackSnapshot>({
    active: false,
    paused: false,
    elapsedMs: 0,
    distanceM: 0,
    speedKmh: 0,
    strokes: 0,
    cadenceSpm: 0,
    pulse: 0,
    latitude: TRASONA_CENTER.latitude,
    longitude: TRASONA_CENTER.longitude,
    accuracyM: 3.5,
    gpsStatus: 'GPS activo',
    trackFilePath: null,
    startAt: 0,
    endAt: 0,
  });

  // BLE State & Manager
  const hrManagerRef = useRef<BleHeartRateManager | null>(null);
  const [bleState, setBleState] = useState<BleHrState>({
    phase: 'IDLE',
    candidates: [],
    name: '',
    address: '',
    bpm: 0,
    lastSampleAt: 0,
    notificationsEnabled: false,
    detail: 'Banda desconectada',
  });

  // Services
  const paddleDetectorRef = useRef<PaddleDetector>(new PaddleDetector());
  const turnModelRef = useRef<TurnAiModel>(new TurnAiModel());
  const lapDetectorRef = useRef<LapDetector>(
    new LapDetector({
      radiusM: settings.autoLapRadiusM || 25,
      maxAccuracyM: settings.gpsAccuracyLimitM || 15,
    })
  );
  const workoutEngineRef = useRef<WorkoutIntervalEngine>(
    new WorkoutIntervalEngine(settings.alertMode || 'VOICE_AND_VIBRATION')
  );

  // Circuits & Structured Workouts
  const [circuits, setCircuits] = useState<CircuitDefinition[]>(() =>
    StorageService.getCircuits()
  );
  const [structuredWorkouts, setStructuredWorkouts] = useState<StructuredWorkout[]>(() =>
    StorageService.getStructuredWorkouts()
  );
  const [activeWorkoutId, setActiveWorkoutId] = useState<string | null>(() =>
    StorageService.getActiveWorkoutId() || 'fartlek-piraguismo'
  );
  const [activeWorkoutState, setActiveWorkoutState] = useState<ActiveWorkoutState | null>(null);
  const [laps, setLaps] = useState<Lap[]>([]);

  // Data collections
  const [recentPoints, setRecentPoints] = useState<GeoSample[]>(() =>
    StorageService.getRecentPoints()
  );
  const [savedBuoys, setSavedBuoys] = useState<SavedBuoy[]>(() =>
    StorageService.getBuoys()
  );
  const [pendingReview, setPendingReview] = useState<FinishedSession | null>(() =>
    StorageService.getPendingReview()
  );
  const [workouts, setWorkouts] = useState<SessionSummary[]>(() =>
    StorageService.getWorkouts()
  );
  const [circuit, setCircuit] = useState(() => StorageService.getCircuit());

  // Simulation / GPS timers
  const simStepRef = useRef(0);
  const sessionTimerRef = useRef<any>(null);
  const gpsWatchIdRef = useRef<number | null>(null);
  const gpxPointsBufferRef = useRef<GeoSample[]>([]);
  const strokeTimesRef = useRef<number[]>([]);
  const wasGpsLockedRef = useRef<boolean>(false);

  // Keep workout engine alert mode in sync with settings
  useEffect(() => {
    workoutEngineRef.current.setAlertMode(settings.alertMode || 'VOICE_AND_VIBRATION');
  }, [settings.alertMode]);

  // Initialize BLE Manager
  useEffect(() => {
    const manager = new BleHeartRateManager();
    hrManagerRef.current = manager;
    const unsubscribe = manager.subscribe((newState) => {
      setBleState(newState);
      if (newState.bpm > 0) {
        setSnapshot((s) => ({ ...s, pulse: newState.bpm }));
        StorageService.appendHeartRateSample({
          timeMs: Date.now(),
          bpm: newState.bpm,
        });
      }
    });

    return () => {
      unsubscribe();
      manager.destroy();
    };
  }, []);

  // Listen to physical device motion if supported on mobile/deck mount
  useEffect(() => {
    const handleDeviceMotion = (e: DeviceMotionEvent) => {
      if (!snapshot.active || snapshot.paused) return;
      if (snapshot.speedKmh < 3.5 || snapshot.accuracyM > 12) {
        paddleDetectorRef.current.reset();
        return;
      }

      const acc = e.acceleration || e.accelerationIncludingGravity;
      if (!acc || acc.x === null || acc.y === null || acc.z === null) return;

      const detected = paddleDetectorRef.current.onSample(
        acc.x,
        acc.y,
        acc.z,
        performance.now()
      );
      if (detected) {
        recordStroke();
      }
    };

    window.addEventListener('devicemotion', handleDeviceMotion);
    return () => {
      window.removeEventListener('devicemotion', handleDeviceMotion);
    };
  }, [snapshot.active, snapshot.paused, snapshot.speedKmh, snapshot.accuracyM]);

  // Session Ticker (1 second)
  useEffect(() => {
    if (snapshot.active && !snapshot.paused) {
      sessionTimerRef.current = setInterval(() => {
        // Advance structured workout interval engine with live speed and distance
        workoutEngineRef.current.tick1s(snapshot.speedKmh, snapshot.distanceM);
        setActiveWorkoutState(workoutEngineRef.current.getState());

        setSnapshot((prev) => {
          const nextElapsed = prev.elapsedMs + 1000;
          const now = Date.now();
          const recentStrokes = strokeTimesRef.current.filter(
            (t) => now - t <= 60000
          ).length;

          return {
            ...prev,
            elapsedMs: nextElapsed,
            cadenceSpm: recentStrokes,
          };
        });
      }, 1000);
    } else {
      if (sessionTimerRef.current) clearInterval(sessionTimerRef.current);
    }

    return () => {
      if (sessionTimerRef.current) clearInterval(sessionTimerRef.current);
    };
  }, [snapshot.active, snapshot.paused]);

  // GPS Simulation or Real Geolocation
  useEffect(() => {
    if (!snapshot.active || snapshot.paused) {
      if (gpsWatchIdRef.current !== null) {
        navigator.geolocation.clearWatch(gpsWatchIdRef.current);
        gpsWatchIdRef.current = null;
      }
      return;
    }

    if (!gpsSimMode && navigator.geolocation) {
      // Real Geolocation Watch
      gpsWatchIdRef.current = navigator.geolocation.watchPosition(
        (pos) => {
          handleLocationUpdate(
            pos.coords.latitude,
            pos.coords.longitude,
            pos.coords.accuracy,
            pos.coords.speed ? pos.coords.speed * 3.6 : 0,
            pos.timestamp
          );
        },
        (err) => {
          setSnapshot((s) => ({
            ...s,
            gpsStatus: `GPS: ${err.message}`,
          }));
        },
        { enableHighAccuracy: true, maximumAge: 1000 }
      );
    } else {
      // Simulated Kayak Circuit at Trasona
      const simInterval = setInterval(() => {
        simStepRef.current += 1;
        const step = simStepRef.current;

        // Trasona 1000m course simulation with turns
        const baseLat = TRASONA_CENTER.latitude;
        const baseLon = TRASONA_CENTER.longitude;
        const cycle = step % 180; // 3 minute loop

        let lat: number;
        let lon: number;
        let speed = 11.4;

        if (cycle < 80) {
          // Outbound stretch (straight)
          const frac = cycle / 80;
          lat = baseLat + frac * 0.009;
          lon = baseLon + frac * 0.004;
          speed = 11.2 + Math.sin(cycle * 0.2) * 0.5;
        } else if (cycle < 95) {
          // U-Turn at far buoy
          const frac = (cycle - 80) / 15;
          const angle = Math.PI * frac;
          lat = baseLat + 0.009 + Math.sin(angle) * 0.0006;
          lon = baseLon + 0.004 + (1 - Math.cos(angle)) * 0.0004;
          speed = 6.2;
        } else if (cycle < 175) {
          // Return stretch
          const frac = (cycle - 95) / 80;
          lat = baseLat + 0.009 - frac * 0.009;
          lon = baseLon + 0.0048 - frac * 0.004;
          speed = 11.8 + Math.cos(cycle * 0.2) * 0.6;
        } else {
          // Turn back to start line
          lat = baseLat;
          lon = baseLon;
          speed = 7.5;
        }

        // Add paddle stroke simulated periodically
        if (speed > 4 && step % 2 === 0) {
          recordStroke();
        }

        handleLocationUpdate(lat, lon, 3.2, speed, Date.now());
      }, 1000);

      return () => clearInterval(simInterval);
    }

    return () => {
      if (gpsWatchIdRef.current !== null) {
        navigator.geolocation.clearWatch(gpsWatchIdRef.current);
        gpsWatchIdRef.current = null;
      }
    };
  }, [snapshot.active, snapshot.paused, gpsSimMode]);

  const recordStroke = () => {
    const now = Date.now();
    strokeTimesRef.current.push(now);
    strokeTimesRef.current = strokeTimesRef.current.filter((t) => now - t <= 60000);

    setSnapshot((s) => ({
      ...s,
      strokes: s.strokes + 1,
      cadenceSpm: strokeTimesRef.current.length,
    }));
  };

  const handleLocationUpdate = (
    lat: number,
    lon: number,
    accuracy: number,
    speedKmh: number,
    timestamp: number
  ) => {
    // Check accuracy limit from settings
    if (accuracy > settings.gpsAccuracyLimitM) {
      setSnapshot((s) => ({ ...s, gpsStatus: 'Precisión baja' }));
      return;
    }

    // Play confirmation tone when GPS locks (accuracy transitions to <= 15m)
    if (accuracy <= 15 && !wasGpsLockedRef.current) {
      wasGpsLockedRef.current = true;
      soundEffects.playGpsConnectedTone();
    } else if (accuracy > 15) {
      wasGpsLockedRef.current = false;
    }

    setSnapshot((prev) => {
      let deltaM = 0;
      if (prev.latitude && prev.longitude) {
        deltaM = calculateDistance(
          { latitude: prev.latitude, longitude: prev.longitude },
          { latitude: lat, longitude: lon }
        );
      }

      // Ignore teleport jump > 25m
      if (deltaM > 25) {
        return { ...prev, gpsStatus: 'Salto GPS descartado' };
      }

      const totalDist = prev.distanceM + (deltaM >= 0.45 ? deltaM : 0);

      const sample: GeoSample = {
        latitude: lat,
        longitude: lon,
        timeMs: timestamp,
        accuracyM: accuracy,
        speedKmh,
      };

      gpxPointsBufferRef.current.push(sample);
      StorageService.appendRecentPoint(sample);

      // Auto-Lap Detection Engine (only if enabled in settings)
      if (settings.autoLapEnabled !== false) {
        // Automatic Circuit Proximity Lock: If near a saved circuit start gate, lock onto it
        if (circuits.length > 0) {
          const nearbyCirc = circuits.find((c) => {
            const d = calculateDistance(
              { latitude: c.startPoint.latitude, longitude: c.startPoint.longitude },
              { latitude: lat, longitude: lon }
            );
            return d <= (c.radiusM || 30);
          });
          if (nearbyCirc && nearbyCirc.id !== settings.activeCircuitId) {
            handleUpdateSettings({ activeCircuitId: nearbyCirc.id });
            lapDetectorRef.current.setStartGate(nearbyCirc.startPoint, nearbyCirc.name);
          }
        }

        const completedLap = lapDetectorRef.current.onGpsSample(
          sample,
          totalDist,
          prev.strokes,
          speedKmh,
          timestamp
        );

        if (completedLap) {
          setLaps(lapDetectorRef.current.getLaps());
          // Notify interval engine if current phase is lap-based
          workoutEngineRef.current.notifyLapCompleted(completedLap, lapDetectorRef.current.getCircuitName());
          setActiveWorkoutState(workoutEngineRef.current.getState());
          // Subtle audio chime for lap completion
          soundEffects.playGpsConnectedTone();
        }
      }

      // Feed to TurnAiModel
      const origin = gpxPointsBufferRef.current[0] || sample;
      const turnPrediction = turnModelRef.current.onGpsPoint(
        origin,
        sample,
        accuracy,
        speedKmh,
        timestamp,
        performance.now()
      );

      if (turnPrediction && turnPrediction.type !== 'CORRECTION') {
        const buoyType = turnPrediction.type === 'UTURN' ? 'UTURN' : 'BUOY';
        StorageService.addBuoy(
          turnPrediction.latitude,
          turnPrediction.longitude,
          buoyType
        );
        setSavedBuoys(StorageService.getBuoys());
      }

      return {
        ...prev,
        distanceM: totalDist,
        speedKmh,
        latitude: lat,
        longitude: lon,
        accuracyM: accuracy,
        gpsStatus: accuracy <= 15 ? 'GPS preciso' : 'GPS activo',
      };
    });

    setRecentPoints(StorageService.getRecentPoints());
  };

  // Start Session with confirmation tone and structured workout/lap init
  const handleStartSession = () => {
    if (pendingReview) {
      alert('Guarda o borra el resumen anterior antes de iniciar otro entrenamiento.');
      return;
    }

    gpxPointsBufferRef.current = [];
    strokeTimesRef.current = [];
    StorageService.clearRecentPoints();
    StorageService.clearHeartRateSamples();
    paddleDetectorRef.current.reset();
    turnModelRef.current.reset();
    simStepRef.current = 0;
    wasGpsLockedRef.current = false;
    setLaps([]);

    const startAt = Date.now();

    // Start auto-lap detector with active circuit
    const activeCirc = circuits.find((c) => c.id === settings.activeCircuitId) || circuits[0] || null;
    lapDetectorRef.current.start(activeCirc, undefined, startAt);

    // Start structured workout if selected
    const activeWorkout = structuredWorkouts.find((w) => w.id === activeWorkoutId);
    if (activeWorkout) {
      workoutEngineRef.current.start(activeWorkout);
      setActiveWorkoutState(workoutEngineRef.current.getState());
    } else {
      workoutEngineRef.current.stop();
      setActiveWorkoutState(null);
    }

    // Play start audio chime
    soundEffects.playWorkoutStartTone();

    setSnapshot({
      active: true,
      paused: false,
      elapsedMs: 0,
      distanceM: 0,
      speedKmh: 0,
      strokes: 0,
      cadenceSpm: 0,
      pulse: bleState.bpm,
      latitude: TRASONA_CENTER.latitude,
      longitude: TRASONA_CENTER.longitude,
      accuracyM: 3.5,
      gpsStatus: 'Buscando satélites…',
      trackFilePath: `piraguismo-${startAt}.gpx`,
      startAt,
      endAt: 0,
    });
  };

  // Pause / Resume
  const handlePauseResume = () => {
    setSnapshot((s) => {
      const nextPaused = !s.paused;
      if (nextPaused) {
        workoutEngineRef.current.pause();
      } else {
        workoutEngineRef.current.resume();
      }
      return { ...s, paused: nextPaused };
    });
  };

  // Finish Session -> Tone, Lap aggregation & Review
  const handleFinishSession = () => {
    const endAt = Date.now();
    const currentPoints = [...gpxPointsBufferRef.current];
    const gpxText = GpxExporter.buildGpx('Entrenamiento OpenKayak', currentPoints);

    // Route analysis
    const analysis = RouteAnalyzer.analyze(gpxText);
    const hrSamples = StorageService.getHeartRateSamples();
    const bpms = hrSamples.map((h) => h.bpm).filter((b) => b >= 25 && b <= 240);

    const averageBpm = bpms.length > 0 ? Math.round(bpms.reduce((a, b) => a + b, 0) / bpms.length) : 0;
    const maxBpm = bpms.length > 0 ? Math.max(...bpms) : 0;
    const minBpm = bpms.length > 0 ? Math.min(...bpms) : 0;

    // MET formula
    const minutes = snapshot.elapsedMs / 60000;
    const calories = Math.round((6.0 * 3.5 * settings.weightKg) / 200.0 * minutes);

    const completedLaps = lapDetectorRef.current.getLaps();
    const activeWorkout = structuredWorkouts.find((w) => w.id === activeWorkoutId);

    // Play finish tone
    soundEffects.playWorkoutFinishTone();
    workoutEngineRef.current.stop();
    setActiveWorkoutState(null);

    const review: FinishedSession = {
      startAt: snapshot.startAt,
      endAt,
      elapsedMs: snapshot.elapsedMs,
      distanceM: snapshot.distanceM,
      strokes: snapshot.strokes,
      averageBpm,
      maxBpm,
      minBpm,
      calories,
      trackPath: snapshot.trackFilePath || `piraguismo-${snapshot.startAt}.gpx`,
      points: analysis.points.length > 0 ? analysis.points : currentPoints,
      turns: analysis.turns,
      repeatedBuoyCount: analysis.repeatedBuoyCount,
      isCircuit: analysis.isCircuit,
      laps: completedLaps,
      structuredWorkoutName: activeWorkout?.name,
    };

    StorageService.setPendingReview(review);
    setPendingReview(review);

    setSnapshot((s) => ({
      ...s,
      active: false,
      paused: false,
      endAt,
    }));
  };

  // Trigger lap manually (from watch button, phone, or voice command)
  const handleTriggerLap = () => {
    if (!snapshot.active) return;
    const manualLap = lapDetectorRef.current.triggerManualLap(
      Date.now(),
      snapshot.distanceM,
      snapshot.strokes
    );
    setLaps(lapDetectorRef.current.getLaps());
    workoutEngineRef.current.notifyLapCompleted(manualLap, lapDetectorRef.current.getCircuitName());
    setActiveWorkoutState(workoutEngineRef.current.getState());
    soundEffects.playGpsConnectedTone();
  };

  // Finish review actions
  const handleSaveBoth = (review: FinishedSession) => {
    handleSaveWorkout(review);
    handleSaveCircuit(review);
  };

  const handleSaveWorkout = (review: FinishedSession) => {
    const currentPoints = [...gpxPointsBufferRef.current];
    const gpxText = GpxExporter.buildGpx('Entrenamiento OpenKayak', currentPoints);

    const summary: SessionSummary = {
      startAt: review.startAt,
      endAt: review.endAt,
      elapsedMs: review.elapsedMs,
      distanceM: review.distanceM,
      strokes: review.strokes,
      trackPath: review.trackPath,
      averageBpm: review.averageBpm,
      maxBpm: review.maxBpm,
      minBpm: review.minBpm,
      calories: review.calories,
      gpxContent: gpxText,
      heartRateSamples: StorageService.getHeartRateSamples(),
      laps: review.laps,
      structuredWorkoutName: review.structuredWorkoutName,
    };

    StorageService.recordWorkout(summary);
    StorageService.setPendingReview(null);
    setPendingReview(null);
    setWorkouts(StorageService.getWorkouts());
  };

  const handleSaveCircuit = (review: FinishedSession) => {
    StorageService.saveCircuit(
      review.points,
      review.isCircuit ? 'Circuito cerrado detectado' : 'Recorrido guardado'
    );
    setCircuit(StorageService.getCircuit());

    review.turns.forEach((t) => {
      StorageService.addBuoy(t.latitude, t.longitude, t.turnType);
    });
    setSavedBuoys(StorageService.getBuoys());

    StorageService.setPendingReview(null);
    setPendingReview(null);
  };

  const handleDeleteReview = () => {
    StorageService.setPendingReview(null);
    setPendingReview(null);
  };

  // Export GPX trigger
  const handleExportGpx = () => {
    const points = gpxPointsBufferRef.current.length > 0
      ? gpxPointsBufferRef.current
      : recentPoints;
    const gpx = GpxExporter.buildGpx('OpenKayak-Track', points);
    GpxExporter.downloadGpx(`openkayak-${Date.now()}`, gpx);
  };

  // BLE actions
  const handleScanBle = () => {
    hrManagerRef.current?.scanAndConnect();
  };

  const handleConnectBle = () => {
    hrManagerRef.current?.startSimulatedConnection();
  };

  const handleDisconnectBle = () => {
    hrManagerRef.current?.disconnect();
  };

  // Buoy actions
  const handleSaveBuoy = (isTurn: boolean) => {
    if (snapshot.latitude && snapshot.longitude) {
      const type = isTurn ? 'BUOY' : 'PUNTO DE RUTA';
      StorageService.addBuoy(snapshot.latitude, snapshot.longitude, type);
      setSavedBuoys(StorageService.getBuoys());
    } else {
      alert('Espera a tener una posición GPS fijada.');
    }
  };

  const handleDeleteBuoy = (id: number) => {
    StorageService.removeBuoy(id);
    setSavedBuoys(StorageService.getBuoys());
  };

  const handleUndoBuoy = () => {
    StorageService.removeLastBuoy();
    setSavedBuoys(StorageService.getBuoys());
  };

  const handlePhoneAddBuoy = (
    lat: number,
    lon: number,
    type: 'BUOY' | 'UTURN' | 'GIRO' | 'PUNTO DE RUTA'
  ) => {
    StorageService.addBuoy(lat, lon, type);
    setSavedBuoys(StorageService.getBuoys());
  };

  // Circuit actions
  const handleImportCircuitGpx = (content: string, name: string) => {
    const points = GpxExporter.parseGpx(content);
    StorageService.saveCircuit(points, name, content);
    setCircuit(StorageService.getCircuit());
  };

  const handleDeleteCircuit = () => {
    StorageService.clearCircuit();
    setCircuit(null);
  };

  // Settings update
  const handleUpdateSettings = (newSettings: Partial<AppSettings>) => {
    StorageService.saveAppSettings(newSettings);
    setSettings(StorageService.getAppSettings());
  };

  // Circuit definitions management
  const handleSaveCircuitDef = (newCirc: CircuitDefinition) => {
    StorageService.saveCircuitDefinition(newCirc);
    setCircuits(StorageService.getCircuits());
    handleUpdateSettings({ activeCircuitId: newCirc.id });
  };

  const handleDeleteCircuitDef = (id: string) => {
    StorageService.deleteCircuitDefinition(id);
    const updated = StorageService.getCircuits();
    setCircuits(updated);
    if (settings.activeCircuitId === id) {
      handleUpdateSettings({ activeCircuitId: updated[0]?.id || '' });
    }
  };

  // Structured Workouts management
  const handleSaveStructuredWorkout = (w: StructuredWorkout) => {
    StorageService.saveStructuredWorkout(w);
    setStructuredWorkouts(StorageService.getStructuredWorkouts());
    handleSelectActiveWorkout(w.id);
  };

  const handleDeleteStructuredWorkout = (id: string) => {
    StorageService.deleteStructuredWorkout(id);
    const updated = StorageService.getStructuredWorkouts();
    setStructuredWorkouts(updated);
    if (activeWorkoutId === id) {
      handleSelectActiveWorkout(updated[0]?.id || null);
    }
  };

  const handleSelectActiveWorkout = (id: string | null) => {
    setActiveWorkoutId(id);
    StorageService.setActiveWorkoutId(id);
  };

  const activeCircuit =
    circuits.find((c) => c.id === settings.activeCircuitId) || circuits[0] || null;

  return (
    <div className="min-h-screen bg-[#05090D] text-white flex flex-col font-sans">
      {/* Top Navigation & Applet Controls */}
      <header className="bg-[#0c1622] border-b border-[#1c2e42] px-4 py-3 sticky top-0 z-50">
        <div className="max-w-7xl mx-auto flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-xl bg-gradient-to-tr from-[#76F4D0] to-[#70C5F0] flex items-center justify-center text-black font-black text-sm shadow-md shadow-[#76F4D0]/20">
              OK
            </div>
            <div>
              <h1 className="text-white font-extrabold text-sm sm:text-base tracking-wider flex items-center gap-2">
                OPENKAYAK
                <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-[#172738] text-[#76F4D0] border border-[#76F4D0]/30">
                  Wear OS 5 & Móvil
                </span>
              </h1>
              <p className="text-[#8D9DA8] text-[10px] hidden sm:block">
                Piragüismo · Vueltas automáticas, Fases por voz & Coach IA Gemini
              </p>
            </div>
          </div>

          {/* View Mode Selector */}
          <div className="flex items-center gap-1 bg-[#101b28] p-1 rounded-2xl border border-[#203144]">
            <button
              onClick={() => setViewMode('watch')}
              className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                viewMode === 'watch'
                  ? 'bg-[#76F4D0] text-[#05090D] shadow-sm'
                  : 'text-[#8D9DA8] hover:text-white'
              }`}
            >
              Reloj (Cubierta)
            </button>
            <button
              onClick={() => setViewMode('phone')}
              className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                viewMode === 'phone'
                  ? 'bg-[#70C5F0] text-[#05090D] shadow-sm'
                  : 'text-[#8D9DA8] hover:text-white'
              }`}
            >
              Centro de Control Móvil
            </button>
            <button
              onClick={() => setViewMode('dual')}
              className={`px-3 py-1.5 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                viewMode === 'dual'
                  ? 'bg-[#FF8FA3] text-[#05090D] shadow-sm'
                  : 'text-[#8D9DA8] hover:text-white'
              }`}
            >
              Vista Dual
            </button>
          </div>

          {/* GPS Simulation vs Real Device GPS toggle */}
          <div className="flex items-center gap-2">
            <button
              onClick={() => setGpsSimMode(!gpsSimMode)}
              className={`px-3 py-1.5 rounded-xl text-xs font-semibold border transition-all flex items-center gap-1.5 cursor-pointer ${
                gpsSimMode
                  ? 'bg-[#17283c] text-[#9FD9FF] border-[#29425e]'
                  : 'bg-[#291e14] text-[#FFCA7A] border-[#5e4329]'
              }`}
              title="Alternar entre recorrido simulado en Trasona y GPS real del dispositivo"
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  gpsSimMode ? 'bg-[#76F4D0]' : 'bg-[#FFCA7A]'
                }`}
              />
              <span>{gpsSimMode ? 'Simulación Trasona' : 'GPS Real Dispositivo'}</span>
            </button>
          </div>
        </div>
      </header>

      {/* Main Content Workspace */}
      <main className="flex-1 max-w-7xl w-full mx-auto p-3 sm:p-6 flex flex-col justify-center items-center">
        {viewMode === 'watch' && (
          <div className="w-full flex flex-col items-center">
            <div className="text-center mb-2">
              <h2 className="text-[#76F4D0] font-bold text-xs tracking-wider uppercase">
                Xiaomi Watch 2 Pro · Montado en la cubierta (Wear OS 5)
              </h2>
              <p className="text-[#8D9DA8] text-[11px]">
                8 pantallas Wear OS Expressive: Vueltas automáticas, Fases por voz, GPS, Pulso, Paladas y Ajustes
              </p>
            </div>
            <WatchDeckView
              snapshot={snapshot}
              ble={bleState}
              recentPoints={recentPoints}
              savedBuoys={savedBuoys}
              routeColorHex={settings.routeColorHex}
              speedUnit={settings.speedUnit}
              laps={laps}
              onTriggerLap={handleTriggerLap}
              activeWorkoutState={activeWorkoutState}
              activeCircuit={activeCircuit}
              circuits={circuits}
              onSelectCircuit={(id) => handleUpdateSettings({ activeCircuitId: id })}
              structuredWorkouts={structuredWorkouts}
              activeWorkoutId={activeWorkoutId}
              onSelectStructuredWorkout={handleSelectActiveWorkout}
              alertMode={settings.alertMode}
              onSetAlertMode={(mode) => handleUpdateSettings({ alertMode: mode })}
              onSetSpeedUnit={(unit: SpeedUnit) => handleUpdateSettings({ speedUnit: unit })}
              onStartSession={handleStartSession}
              onPauseResumeSession={handlePauseResume}
              onFinishSession={handleFinishSession}
              onScanBle={handleScanBle}
              onConnectBle={handleConnectBle}
              onDisconnectBle={handleDisconnectBle}
              onSaveBuoy={handleSaveBuoy}
              onDeleteBuoy={handleDeleteBuoy}
              onUndoBuoy={handleUndoBuoy}
              pendingReview={pendingReview}
              onSaveBoth={handleSaveBoth}
              onSaveWorkout={handleSaveWorkout}
              onSaveCircuit={handleSaveCircuit}
              onDeleteReview={handleDeleteReview}
              onExportGpx={handleExportGpx}
            />
          </div>
        )}

        {viewMode === 'phone' && (
          <div className="w-full">
            <PhoneControlCenter
              workouts={workouts}
              buoys={savedBuoys}
              currentCircuit={circuit}
              circuits={circuits}
              onSaveCircuitDefinition={handleSaveCircuitDef}
              onDeleteCircuitDefinition={handleDeleteCircuitDef}
              structuredWorkouts={structuredWorkouts}
              onSaveStructuredWorkout={handleSaveStructuredWorkout}
              onDeleteStructuredWorkout={handleDeleteStructuredWorkout}
              activeWorkoutId={activeWorkoutId}
              onSelectActiveWorkout={handleSelectActiveWorkout}
              settings={settings}
              onUpdateSettings={handleUpdateSettings}
              onAddBuoy={handlePhoneAddBuoy}
              onDeleteBuoy={handleDeleteBuoy}
              onDeleteWorkout={(startAt) => {
                StorageService.deleteWorkout(startAt);
                setWorkouts(StorageService.getWorkouts());
              }}
              onImportCircuitGpx={handleImportCircuitGpx}
              onDeleteCircuit={handleDeleteCircuit}
              onTriggerSync={() => {
                setWorkouts(StorageService.getWorkouts());
                setSavedBuoys(StorageService.getBuoys());
                setCircuits(StorageService.getCircuits());
                setStructuredWorkouts(StorageService.getStructuredWorkouts());
              }}
            />
          </div>
        )}

        {viewMode === 'dual' && (
          <div className="w-full grid grid-cols-1 xl:grid-cols-12 gap-6 items-start">
            {/* Left Watch Deck (5 cols) */}
            <div className="xl:col-span-5 flex flex-col items-center bg-[#0c1825]/80 p-4 rounded-3xl border border-[#1b2b3c] shadow-xl">
              <div className="text-center mb-1">
                <span className="text-[#76F4D0] font-bold text-xs tracking-wider uppercase block">
                  PANTALLA DE CUBIERTA (RELOJ WEAR OS 5)
                </span>
                <span className="text-[#8D9DA8] text-[10px]">
                  Toca ‹ y › para navegar entre las 8 pantallas o activa AOD
                </span>
              </div>
              <WatchDeckView
                snapshot={snapshot}
                ble={bleState}
                recentPoints={recentPoints}
                savedBuoys={savedBuoys}
                routeColorHex={settings.routeColorHex}
                speedUnit={settings.speedUnit}
                laps={laps}
                onTriggerLap={handleTriggerLap}
                activeWorkoutState={activeWorkoutState}
                activeCircuit={activeCircuit}
                circuits={circuits}
                onSelectCircuit={(id) => handleUpdateSettings({ activeCircuitId: id })}
                structuredWorkouts={structuredWorkouts}
                activeWorkoutId={activeWorkoutId}
                onSelectStructuredWorkout={handleSelectActiveWorkout}
                alertMode={settings.alertMode}
                onSetAlertMode={(mode) => handleUpdateSettings({ alertMode: mode })}
                onSetSpeedUnit={(unit: SpeedUnit) => handleUpdateSettings({ speedUnit: unit })}
                onStartSession={handleStartSession}
                onPauseResumeSession={handlePauseResume}
                onFinishSession={handleFinishSession}
                onScanBle={handleScanBle}
                onConnectBle={handleConnectBle}
                onDisconnectBle={handleDisconnectBle}
                onSaveBuoy={handleSaveBuoy}
                onDeleteBuoy={handleDeleteBuoy}
                onUndoBuoy={handleUndoBuoy}
                pendingReview={pendingReview}
                onSaveBoth={handleSaveBoth}
                onSaveWorkout={handleSaveWorkout}
                onSaveCircuit={handleSaveCircuit}
                onDeleteReview={handleDeleteReview}
                onExportGpx={handleExportGpx}
              />
            </div>

            {/* Right Phone Control Center (7 cols) */}
            <div className="xl:col-span-7 w-full">
              <PhoneControlCenter
                workouts={workouts}
                buoys={savedBuoys}
                currentCircuit={circuit}
                circuits={circuits}
                onSaveCircuitDefinition={handleSaveCircuitDef}
                onDeleteCircuitDefinition={handleDeleteCircuitDef}
                structuredWorkouts={structuredWorkouts}
                onSaveStructuredWorkout={handleSaveStructuredWorkout}
                onDeleteStructuredWorkout={handleDeleteStructuredWorkout}
                activeWorkoutId={activeWorkoutId}
                onSelectActiveWorkout={handleSelectActiveWorkout}
                settings={settings}
                onUpdateSettings={handleUpdateSettings}
                onAddBuoy={handlePhoneAddBuoy}
                onDeleteBuoy={handleDeleteBuoy}
                onDeleteWorkout={(startAt) => {
                  StorageService.deleteWorkout(startAt);
                  setWorkouts(StorageService.getWorkouts());
                }}
                onImportCircuitGpx={handleImportCircuitGpx}
                onDeleteCircuit={handleDeleteCircuit}
                onTriggerSync={() => {
                  setWorkouts(StorageService.getWorkouts());
                  setSavedBuoys(StorageService.getBuoys());
                  setCircuits(StorageService.getCircuits());
                  setStructuredWorkouts(StorageService.getStructuredWorkouts());
                }}
              />
            </div>
          </div>
        )}
      </main>
    </div>
  );
};
