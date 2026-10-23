import React, { useState, useEffect, useRef } from 'react';
import {
  TrackSnapshot,
  BleHrState,
  SavedBuoy,
  FinishedSession,
  GeoSample,
  SessionSummary,
  SpeedUnit,
  CircuitDefinition,
  StructuredWorkout,
  ActiveWorkoutState,
  Lap,
  AlertMode,
} from '../types/openkayak';
import { MapView } from './MapView';
import { VoiceAiOverlay } from './VoiceAiOverlay';
import { StorageService } from '../services/storage';
import { GpxExporter } from '../services/gpxExporter';
import { RouteAnalyzer } from '../services/routeAnalyzer';
import { OsmdroidTileStore } from '../services/osmdroidTileStore';
import { soundEffects } from '../services/soundEffects';
import { formatSpeed, getNextSpeedUnit, SPEED_UNITS } from '../services/speedUnits';

interface WatchDeckViewProps {
  snapshot: TrackSnapshot;
  ble: BleHrState;
  recentPoints: GeoSample[];
  savedBuoys: SavedBuoy[];
  routeColorHex: string;
  speedUnit?: SpeedUnit;
  laps?: Lap[];
  onTriggerLap?: () => void;
  activeWorkoutState?: ActiveWorkoutState | null;
  activeCircuit?: CircuitDefinition | null;
  circuits?: CircuitDefinition[];
  onSelectCircuit?: (circuitId: string) => void;
  structuredWorkouts?: StructuredWorkout[];
  activeWorkoutId?: string | null;
  onSelectStructuredWorkout?: (workoutId: string | null) => void;
  alertMode?: AlertMode;
  onSetAlertMode?: (mode: AlertMode) => void;
  onCycleSpeedUnit?: () => void;
  onSetSpeedUnit?: (unit: SpeedUnit) => void;
  onStartSession: () => void;
  onPauseResumeSession: () => void;
  onFinishSession: () => void;
  onScanBle: () => void;
  onConnectBle: (address: string) => void;
  onDisconnectBle: () => void;
  onSaveBuoy: (isTurn: boolean) => void;
  onDeleteBuoy: (id: number) => void;
  onUndoBuoy: () => void;
  pendingReview: FinishedSession | null;
  onSaveBoth: (review: FinishedSession) => void;
  onSaveWorkout: (review: FinishedSession) => void;
  onSaveCircuit: (review: FinishedSession) => void;
  onDeleteReview: (review: FinishedSession) => void;
  onExportGpx: () => void;
  onImportCircuitGpx?: (file: File) => void;
}

export const WatchDeckView: React.FC<WatchDeckViewProps> = ({
  snapshot,
  ble,
  recentPoints,
  savedBuoys,
  routeColorHex,
  speedUnit = 'kmh',
  laps = [],
  onTriggerLap,
  activeWorkoutState,
  activeCircuit,
  circuits = [],
  onSelectCircuit,
  structuredWorkouts = [],
  activeWorkoutId,
  onSelectStructuredWorkout,
  alertMode = 'VOICE_AND_VIBRATION',
  onSetAlertMode,
  onCycleSpeedUnit,
  onSetSpeedUnit,
  onStartSession,
  onPauseResumeSession,
  onFinishSession,
  onScanBle,
  onConnectBle,
  onDisconnectBle,
  onSaveBuoy,
  onDeleteBuoy,
  onUndoBuoy,
  pendingReview,
  onSaveBoth,
  onSaveWorkout,
  onSaveCircuit,
  onDeleteReview,
  onExportGpx,
}) => {
  const [page, setPage] = useState(0);
  const [isAmbient, setIsAmbient] = useState(false);
  const [showFinishConfirm, setShowFinishConfirm] = useState(false);
  const [weight, setWeight] = useState(StorageService.getAthleteProfile().weightKg.toString());
  const [age, setAge] = useState(StorageService.getAthleteProfile().ageYears.toString());
  const [sessions, setSessions] = useState<SessionSummary[]>(StorageService.getWorkouts());
  const [cachedTilesCount, setCachedTilesCount] = useState<number>(() =>
    OsmdroidTileStore.getCachedCount()
  );
  const [isVoiceWakeActive, setIsVoiceWakeActive] = useState(false);
  const [unitToast, setUnitToast] = useState<string | null>(null);
  const [currentTime, setCurrentTime] = useState<string>('');

  const fileInputRef = useRef<HTMLInputElement>(null);
  const toastTimeoutRef = useRef<any>(null);

  // Digital clock for Wear OS 5 status chip
  useEffect(() => {
    const updateTime = () => {
      const d = new Date();
      setCurrentTime(
        `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
      );
    };
    updateTime();
    const timer = setInterval(updateTime, 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    return OsmdroidTileStore.subscribe((count) => {
      setCachedTilesCount(count);
    });
  }, []);

  useEffect(() => {
    setSessions(StorageService.getWorkouts());
  }, [pendingReview]);

  const pageTitles = [
    'EN EL AGUA',
    'VUELTAS Y SERIES',
    'TRAZADO GPS',
    'BANDA DE PULSO',
    'PALADAS',
    'BOYAS Y RUTAS',
    'AJUSTES',
    'HISTORIAL',
  ];

  const formatDuration = (ms: number): string => {
    const totalSeconds = Math.max(0, Math.floor(ms / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
      return `${hours}:${minutes.toString().padStart(2, '0')}:${seconds
        .toString()
        .padStart(2, '0')}`;
    }
    return `${minutes.toString().padStart(2, '0')}:${seconds
      .toString()
      .padStart(2, '0')}`;
  };

  const isBleFresh =
    ble.phase === 'CONNECTED' &&
    ble.bpm > 0 &&
    ble.lastSampleAt > 0 &&
    Date.now() - ble.lastSampleAt < 12000;

  const hexRoute = routeColorHex.replace(/^FF/i, '#');
  const activeRouteColor = /^#[0-9A-Fa-f]{6}$/.test(hexRoute) ? hexRoute : '#FF8500';

  const showToast = (msg: string) => {
    if (toastTimeoutRef.current) clearTimeout(toastTimeoutRef.current);
    setUnitToast(msg);
    toastTimeoutRef.current = setTimeout(() => {
      setUnitToast(null);
    }, 1800);
  };

  const handleCycleSpeed = () => {
    if (onCycleSpeedUnit) {
      onCycleSpeedUnit();
    } else if (onSetSpeedUnit) {
      const next = getNextSpeedUnit(speedUnit);
      onSetSpeedUnit(next);
      const item = SPEED_UNITS.find((u) => u.id === next);
      showToast(`${item?.name || next} (${item?.symbol})`);
    }
  };

  const handleStartWithSound = () => {
    soundEffects.playWorkoutStartTone();
    onStartSession();
  };

  const handleFinishWithSound = () => {
    soundEffects.playWorkoutFinishTone();
    setShowFinishConfirm(false);
    onFinishSession();
  };

  const currentFormattedSpeed = formatSpeed(snapshot.speedKmh, speedUnit);
  const isGpsLocked = snapshot.accuracyM > 0 && snapshot.accuracyM <= 15;
  const bestLap = laps.find((l) => l.isBestLap) || null;

  return (
    <div className="relative flex flex-col items-center justify-center p-2 select-none font-sans">
      {/* Top Bar for Simulating Controls & Quick Wear OS Toggles */}
      <div className="flex items-center gap-2 mb-2 z-30 flex-wrap justify-center">
        <button
          onClick={() => setIsAmbient(!isAmbient)}
          className={`px-3 py-1 rounded-full text-xs font-bold border transition-all cursor-pointer ${
            isAmbient
              ? 'bg-amber-400 text-black border-amber-300 shadow-md shadow-amber-400/20'
              : 'bg-[#101820] text-[#9FD9FF] border-[#24313C] hover:bg-[#17232D]'
          }`}
        >
          {isAmbient ? 'MODO AOD ACTIVO' : 'MODO AOD (PANTALLA AMBIENTE)'}
        </button>

        {/* Speed unit quick button in outer bar */}
        <button
          onClick={handleCycleSpeed}
          className="px-2.5 py-1 rounded-full text-xs font-mono font-bold bg-[#142333] hover:bg-[#1f354c] text-[#76F4D0] border border-[#76F4D0]/30 transition-all cursor-pointer"
          title="Cambiar unidad de velocidad (km/h, /500m, /km, nudos, m/s)"
        >
          {currentFormattedSpeed.unitLabel}
        </button>

        {/* Quick Lap Button in simulator */}
        {snapshot.active && onTriggerLap && (
          <button
            onClick={onTriggerLap}
            className="px-2.5 py-1 rounded-full text-xs font-bold bg-[#291e14] hover:bg-[#3d2c1c] text-[#FFCA7A] border border-[#FFCA7A]/40 transition-all cursor-pointer"
            title="Marcar vuelta manual al circuito"
          >
            🏁 + Vuelta ({laps.length})
          </button>
        )}
      </div>

      {/* Watch Wrapper with Pure Green Glowing Border when "hola reloj" is heard */}
      <div className="relative flex items-center justify-center">
        {isVoiceWakeActive && !isAmbient && <div className="wearos-green-border" />}

        {/* Circular Watch Frame (Wear OS 5 Modern Bezel) */}
        <div
          className={`relative w-[345px] h-[345px] sm:w-[385px] sm:h-[385px] rounded-full border-[10px] shadow-2xl transition-all duration-300 flex items-center justify-center overflow-hidden z-10 ${
            isAmbient
              ? 'bg-black border-[#141414] shadow-black'
              : 'bg-[#000000] border-[#18232d] shadow-[#000000]/90'
          }`}
          style={{
            boxShadow: isAmbient
              ? '0 0 40px rgba(0,0,0,0.9)'
              : '0 0 50px rgba(0, 240, 255, 0.08), inset 0 0 35px rgba(0,0,0,0.9)',
          }}
        >
          {/* Wear OS 5 Outer Bezel Graduation Indices */}
          <div className="absolute inset-1 rounded-full pointer-events-none border border-white/5" />
          <div className="absolute top-1.5 left-1/2 -translate-x-1/2 w-2 h-1 bg-[#76F4D0]/40 rounded-full pointer-events-none" />

          {/* Unit Switch Toast inside watch screen */}
          {unitToast && (
            <div className="absolute top-12 left-1/2 -translate-x-1/2 bg-[#0c1a26]/95 border border-[#76F4D0] text-[#76F4D0] px-3 py-1 rounded-full text-[9px] font-black z-40 shadow-xl animate-fade-in">
              {unitToast}
            </div>
          )}

          {/* Watch Content Container */}
          <div className="relative w-full h-full flex flex-col items-center justify-between px-7 pt-4 pb-3 select-none z-10 text-center">
            {/* Wear OS 5 Top Status Chip (Curved Header) */}
            {!isAmbient && (
              <div className="w-full flex items-center justify-between px-4 mt-0.5 z-20">
                {/* Left: Time & GPS status */}
                <div className="flex items-center gap-1.5">
                  <span className="text-white/80 font-mono text-[9px] font-bold">
                    {currentTime || '11:42'}
                  </span>
                  <div
                    className="flex items-center gap-1 bg-[#131f2b]/90 border border-white/10 px-1.5 py-0.5 rounded-full"
                    title={snapshot.gpsStatus}
                  >
                    <span
                      className={`w-1.5 h-1.5 rounded-full ${
                        isGpsLocked ? 'bg-[#00FF88] shadow-sm shadow-[#00FF88]' : 'bg-[#FF9F0A] animate-pulse'
                      }`}
                    />
                    <span className="text-[7px] font-mono text-white/80">
                      {isGpsLocked ? 'GPS' : 'BUSC'}
                    </span>
                  </div>
                </div>

                {/* Right: Page Title & Speed Unit Chip */}
                <div className="flex items-center gap-1">
                  <button
                    onClick={handleCycleSpeed}
                    className="bg-[#131f2b] hover:bg-[#1a2c3d] border border-[#76F4D0]/40 text-[#76F4D0] text-[7px] font-black px-1.5 py-0.5 rounded-full uppercase tracking-wider active:scale-90 transition-all cursor-pointer"
                    title="Toca para cambiar unidad de velocidad"
                  >
                    {currentFormattedSpeed.shortLabel}
                  </button>
                  {isBleFresh && (
                    <div className="flex items-center gap-0.5 bg-[#2d1217] border border-[#FF8FA3]/30 px-1.5 py-0.5 rounded-full">
                      <span className="text-[7px] text-[#FF8FA3]">♥</span>
                      <span className="text-[7px] font-bold text-[#FF8FA3]">{ble.bpm}</span>
                    </div>
                  )}
                </div>
              </div>
            )}

            {/* PAGE CONTENT */}
            <div className="w-full flex-1 flex flex-col items-center justify-center overflow-y-auto no-scrollbar py-0.5">
              {isAmbient ? (
                /* AMBIENT MODE DASHBOARD */
                <div className="flex flex-col items-center justify-center w-full">
                  <span className="text-white text-3xl font-black tracking-tight tabular-nums">
                    {formatDuration(snapshot.elapsedMs)}
                  </span>
                  <span className="text-[#8D9DA8] text-[8px] font-bold tracking-wider mb-2">
                    {snapshot.active && !snapshot.paused
                      ? 'PIRAGÜISMO EN CURSO'
                      : 'CIRCUITO GUARDADO'}
                  </span>

                  {/* Circular Mini Map Preview in Ambient */}
                  <div className="relative w-[124px] h-[124px] rounded-full overflow-hidden bg-black border border-[#2a2a2a] mb-2">
                    <MapView
                      points={recentPoints}
                      buoys={savedBuoys}
                      circuitRouteLine={activeCircuit?.routeLine}
                      circuitWaypoints={activeCircuit?.waypoints}
                      circuitColor="#C084FC"
                      currentLocation={
                        snapshot.latitude && snapshot.longitude
                          ? { latitude: snapshot.latitude, longitude: snapshot.longitude }
                          : null
                      }
                      ambient={true}
                      interactive={false}
                      routeColor={activeRouteColor}
                      className="w-full h-full"
                    />
                    <div
                      className="absolute inset-0 pointer-events-none rounded-full"
                      style={{
                        background:
                          'radial-gradient(circle, transparent 55%, rgba(0,0,0,0.8) 85%, #000000 100%)',
                      }}
                    />
                  </div>

                  {/* Key Metrics in Ambient */}
                  <div className="grid grid-cols-3 gap-1.5 w-full max-w-[240px]">
                    <div className="bg-black border border-[#2a2a2a] rounded-xl py-1 px-1">
                      <div className="text-[#8D9DA8] text-[7px] font-bold">FC</div>
                      <div className="text-white text-sm font-extrabold">
                        {isBleFresh ? ble.bpm : '--'}
                      </div>
                      <div className="text-[#8D9DA8] text-[6px]">lpm</div>
                    </div>
                    <div className="bg-black border border-[#2a2a2a] rounded-xl py-1 px-1">
                      <div className="text-[#8D9DA8] text-[7px] font-bold">DISTANCIA</div>
                      <div className="text-[#9FD9FF] text-sm font-extrabold">
                        {(snapshot.distanceM / 1000).toFixed(2)}
                      </div>
                      <div className="text-[#8D9DA8] text-[6px]">km</div>
                    </div>
                    <div className="bg-black border border-[#2a2a2a] rounded-xl py-1 px-1">
                      <div className="text-[#8D9DA8] text-[7px] font-bold">VELOCIDAD</div>
                      <div className="text-[#76F4D0] text-sm font-extrabold">
                        {currentFormattedSpeed.value}
                      </div>
                      <div className="text-[#8D9DA8] text-[6px]">{currentFormattedSpeed.unitLabel}</div>
                    </div>
                  </div>

                  <span className="text-[#455460] text-[7px] mt-2">
                    AOD Wear OS 5 · {snapshot.gpsStatus}
                  </span>
                </div>
              ) : (
                /* INTERACTIVE PAGES */
                <>
                  {page === 0 && (
                    /* PAGE 0: WEAR OS 5 MODERN DASHBOARD */
                    <div className="flex flex-col items-center w-full max-w-[260px]">
                      {/* Active Workout Phase Banner (if structured workout is active) */}
                      {activeWorkoutState && !activeWorkoutState.isCompleted ? (
                        <div
                          onClick={() => setPage(1)}
                          className={`w-full py-1 px-2.5 rounded-full mb-1 flex items-center justify-between border cursor-pointer shadow-sm transition-all animate-pulse ${
                            activeWorkoutState.currentPhaseType === 'SPRINT'
                              ? 'bg-red-950/70 border-red-500/60 text-red-200'
                              : activeWorkoutState.currentPhaseType === 'RECOVERY'
                              ? 'bg-blue-950/70 border-blue-400/60 text-blue-200'
                              : 'bg-emerald-950/70 border-emerald-400/60 text-emerald-200'
                          }`}
                        >
                          <span className="text-[8px] font-black uppercase flex items-center gap-1">
                            <span>
                              {activeWorkoutState.currentPhaseType === 'SPRINT' ? '⚡' : '🔄'}
                            </span>
                            <span>{activeWorkoutState.currentPhaseName}</span>
                          </span>
                          <span className="text-[8px] font-mono font-black">
                            {activeWorkoutState.phaseRemainingSec}s · S{activeWorkoutState.currentCycle}/{activeWorkoutState.totalCycles}
                          </span>
                        </div>
                      ) : (
                        /* Active State Pill & Lap Counter */
                        <div className="flex items-center justify-between w-full px-1 mb-0.5">
                          <div className="flex items-center gap-1.5">
                            <span
                              className={`w-2 h-2 rounded-full ${
                                snapshot.active
                                  ? snapshot.paused
                                  : 'bg-[#00FF88] animate-pulse'
                              }`}
                            />
                            <span
                              className={`text-[9px] font-black tracking-wider uppercase ${
                                snapshot.active
                                  ? snapshot.paused
                                    ? 'text-[#FF9F0A]'
                                    : 'text-[#00FF88]'
                                  : 'text-[#76F4D0]'
                              }`}
                            >
                              {snapshot.active
                                ? snapshot.paused
                                  ? 'En Pausa'
                                  : 'Remando en vivo'
                                : 'Listo para remar'}
                            </span>
                          </div>

                          {/* Lap Badge */}
                          {snapshot.active && (
                            <button
                              onClick={() => setPage(1)}
                              className="text-[8px] font-bold px-2 py-0.5 rounded-full bg-[#182635] text-[#FFCA7A] border border-[#FFCA7A]/30 flex items-center gap-1 hover:bg-[#203348]"
                            >
                              <span>🏁</span>
                              <span>V{laps.length + 1}</span>
                              {bestLap && (
                                <span className="text-[7px] text-amber-300">🏆</span>
                              )}
                            </button>
                          )}
                        </div>
                      )}

                      {/* Primary Glanceable Metric: Distance with Big Bold Numerals */}
                      <div className="flex flex-col items-center my-0.5">
                        <div className="flex items-baseline gap-1">
                          <span className="text-white text-4xl sm:text-[42px] font-black tracking-tight tabular-nums">
                            {(snapshot.distanceM / 1000).toFixed(2)}
                          </span>
                          <span className="text-[#76F4D0] text-sm font-extrabold">km</span>
                        </div>
                        <span className="text-white/90 text-sm font-mono font-bold -mt-1 mb-1.5 tabular-nums">
                          {formatDuration(snapshot.elapsedMs)}
                        </span>
                      </div>

                      {/* Wear OS 5 Dynamic Rounded Metric Cards */}
                      <div className="grid grid-cols-3 gap-1.5 w-full mb-2">
                        {/* Speed Card (Tap to cycle units!) */}
                        <div
                          onClick={handleCycleSpeed}
                          className="bg-[#0f1b27] border border-[#203244] hover:border-[#76F4D0]/60 active:scale-95 transition-all rounded-2xl py-1.5 px-1 cursor-pointer group shadow-sm"
                          title="Toca para cambiar unidad de velocidad"
                        >
                          <div className="flex items-center justify-center gap-0.5 text-[#8D9DA8] group-hover:text-[#76F4D0] text-[7px] font-extrabold uppercase">
                            <span>VEL</span>
                            <span className="text-[6px] text-[#76F4D0]">⇄</span>
                          </div>
                          <div className="text-[#76F4D0] text-sm sm:text-base font-black tabular-nums">
                            {currentFormattedSpeed.value}
                          </div>
                          <div className="text-[#8D9DA8] text-[6px] font-bold truncate">
                            {currentFormattedSpeed.unitLabel}
                          </div>
                        </div>

                        {/* Heart Rate Card */}
                        <div className="bg-[#0f1b27] border border-[#203244] rounded-2xl py-1.5 px-1 shadow-sm">
                          <div className="flex items-center justify-center gap-0.5 text-[#8D9DA8] text-[7px] font-extrabold uppercase">
                            <span className="text-[#FF8FA3]">♥</span>
                            <span>PULSO</span>
                          </div>
                          <div className="text-[#FF8FA3] text-sm sm:text-base font-black tabular-nums">
                            {isBleFresh ? ble.bpm : '--'}
                          </div>
                          <div className="text-[#8D9DA8] text-[6px] font-bold">lpm</div>
                        </div>

                        {/* Strokes / Cadence Card */}
                        <div className="bg-[#0f1b27] border border-[#203244] rounded-2xl py-1.5 px-1 shadow-sm">
                          <div className="text-[#8D9DA8] text-[7px] font-extrabold uppercase">
                            CADENCIA
                          </div>
                          <div className="text-[#9FD9FF] text-sm sm:text-base font-black tabular-nums">
                            {snapshot.cadenceSpm}
                          </div>
                          <div className="text-[#8D9DA8] text-[6px] font-bold">spm ({snapshot.strokes})</div>
                        </div>
                      </div>

                      {/* Wear OS 5 Floating Action Button (FAB) Controls */}
                      <div className="w-full">
                        {!snapshot.active ? (
                          <button
                            onClick={handleStartWithSound}
                            className="w-full py-2.5 bg-gradient-to-r from-[#76F4D0] to-[#00F0FF] hover:from-[#5de0bd] hover:to-[#00d0e0] text-[#05090D] font-black text-xs rounded-full shadow-lg shadow-[#00F0FF]/25 active:scale-95 transition-all flex items-center justify-center gap-1.5 cursor-pointer"
                          >
                            <span>▶</span>
                            <span>INICIAR PIRAGÜISMO</span>
                          </button>
                        ) : (
                          <div className="flex gap-1.5 w-full">
                            <button
                              onClick={onPauseResumeSession}
                              className={`flex-1 py-2 font-black text-[10px] rounded-full border active:scale-95 transition-all cursor-pointer ${
                                snapshot.paused
                                  ? 'bg-[#18342B] border-[#00FF88] text-[#00FF88]'
                                  : 'bg-[#1c2936] border-[#2d4255] text-white hover:bg-[#253748]'
                              }`}
                            >
                              {snapshot.paused ? '▶ SEGUIR' : '❚❚ PAUSA'}
                            </button>
                            {onTriggerLap && (
                              <button
                                onClick={onTriggerLap}
                                className="px-2.5 py-2 bg-[#2a2215] hover:bg-[#3d301c] text-[#FFCA7A] font-black text-[10px] rounded-full border border-[#FFCA7A]/40 active:scale-95 transition-all cursor-pointer"
                                title="Cerrar vuelta actual"
                              >
                                🏁 VUELTA
                              </button>
                            )}
                            <button
                              onClick={() => setShowFinishConfirm(true)}
                              className="flex-1 py-2 bg-[#421a20] hover:bg-[#522028] text-[#FF8FA3] font-black text-[10px] rounded-full border border-[#FF8FA3]/30 active:scale-95 transition-all cursor-pointer shadow-sm shadow-[#FF8FA3]/15"
                            >
                              ■ FIN
                            </button>
                          </div>
                        )}
                      </div>
                    </div>
                  )}

                  {page === 1 && (
                    /* PAGE 1: VUELTAS AUTOMÁTICAS Y ENTRENAMIENTO POR FASES */
                    <div className="flex flex-col items-center w-full max-w-[260px] text-center">
                      <div className="flex items-center justify-between w-full mb-1">
                        <span className="text-[#FFCA7A] text-[9px] font-black uppercase flex items-center gap-1">
                          <span>🏁</span>
                          <span>{activeCircuit ? activeCircuit.name : 'Memorial'}</span>
                        </span>
                        <span className="text-[7px] text-[#76F4D0] font-bold bg-[#142333] px-1.5 py-0.5 rounded-full border border-[#76F4D0]/30">
                          Auto-Lap: ±25m
                        </span>
                      </div>

                      {/* Active Phase Card */}
                      {activeWorkoutState && !activeWorkoutState.isCompleted ? (
                        <div className="w-full bg-[#0f1b27] border border-[#203244] rounded-2xl p-2 mb-1.5 text-left">
                          <div className="flex justify-between items-center text-[8px] text-[#8D9DA8]">
                            <span className="font-bold text-[#76F4D0] truncate">
                              {activeWorkoutState.workoutName}
                            </span>
                            <span className="font-mono text-white font-bold">
                              Serie {activeWorkoutState.currentCycle}/{activeWorkoutState.totalCycles}
                            </span>
                          </div>

                          <div className="flex justify-between items-center my-1">
                            <span className="text-white text-xs font-black">
                              {activeWorkoutState.currentPhaseName}
                            </span>
                            <span className="text-sm font-black font-mono text-[#FF8FA3]">
                              {activeWorkoutState.phaseRemainingSec !== undefined
                                ? `${activeWorkoutState.phaseRemainingSec}s`
                                : activeWorkoutState.phaseMetricDisplay}
                            </span>
                          </div>

                          {/* Progress bar */}
                          <div className="w-full bg-[#1b2b3b] h-1.5 rounded-full overflow-hidden">
                            <div
                              className="bg-gradient-to-r from-[#76F4D0] to-[#FF8FA3] h-full transition-all"
                              style={{
                                width: `${Math.min(
                                  100,
                                  activeWorkoutState.phaseProgressPercent ||
                                    (activeWorkoutState.phaseElapsedSec /
                                      Math.max(1, activeWorkoutState.phaseDurationSec || 60)) *
                                      100
                                )}%`,
                              }}
                            />
                          </div>
                        </div>
                      ) : (
                        <div className="w-full bg-[#0f1b27] border border-[#203244] rounded-2xl p-1.5 mb-1.5">
                          <span className="text-[8px] text-[#8D9DA8] block mb-1">
                            Entrenamiento por fases no iniciado
                          </span>
                          <div className="flex gap-1 overflow-x-auto no-scrollbar">
                            {structuredWorkouts.slice(0, 3).map((sw) => (
                              <button
                                key={sw.id}
                                onClick={() => onSelectStructuredWorkout?.(sw.id)}
                                className={`text-[7px] px-2 py-0.5 rounded-full font-bold truncate border transition-all ${
                                  activeWorkoutId === sw.id
                                    ? 'bg-[#76F4D0] text-black border-[#76F4D0]'
                                    : 'bg-[#142333] text-[#9FD9FF] border-[#22364c]'
                                }`}
                              >
                                {sw.name.split(' ')[0]}
                              </button>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Laps List */}
                      <div className="w-full">
                        <div className="flex justify-between items-center text-[7px] text-[#8D9DA8] font-bold px-1 mb-1">
                          <span>VUELTAS COMPLETADAS ({laps.length})</span>
                          {onTriggerLap && snapshot.active && (
                            <button
                              onClick={onTriggerLap}
                              className="text-[#76F4D0] hover:underline"
                            >
                              + Marcar
                            </button>
                          )}
                        </div>

                        <div className="max-h-[90px] overflow-y-auto space-y-1 pr-0.5">
                          {laps.length === 0 ? (
                            <div className="bg-[#0b141f] rounded-xl p-2 text-[8px] text-[#5e778f]">
                              Al volver a la zona de salida se registrará cada vuelta automáticamente con vibración.
                            </div>
                          ) : (
                            laps
                              .slice()
                              .reverse()
                              .map((lap) => {
                                const min = Math.floor(lap.durationMs / 60000);
                                const sec = Math.round((lap.durationMs % 60000) / 1000);
                                const timeStr = `${min}:${sec.toString().padStart(2, '0')}`;
                                const speedObj = formatSpeed(lap.avgSpeedKmh, speedUnit);

                                return (
                                  <div
                                    key={lap.lapNumber}
                                    className={`flex items-center justify-between p-1 px-2 rounded-xl text-[8px] border ${
                                      lap.isBestLap
                                        ? 'bg-amber-950/40 border-amber-500/60 text-white'
                                        : 'bg-[#0f1b27] border-[#203244] text-[#8D9DA8]'
                                    }`}
                                  >
                                    <div className="flex items-center gap-1 font-bold">
                                      <span>V{lap.lapNumber}</span>
                                      {lap.isBestLap && (
                                        <span className="text-[7px] text-amber-300 font-extrabold">
                                          🏆 MEJOR
                                        </span>
                                      )}
                                      <span className="text-[#5e778f]">· {lap.distanceM}m</span>
                                    </div>
                                    <div className="font-mono font-black text-white">
                                      {timeStr} · {speedObj.value} {speedObj.shortLabel}
                                    </div>
                                  </div>
                                );
                              })
                          )}
                        </div>
                      </div>
                    </div>
                  )}

                  {page === 2 && (
                    /* PAGE 2: ROUTE MAP WITH OSMDROID CACHE */
                    <div className="flex flex-col items-center w-full max-w-[260px]">
                      <div className="w-full h-[120px] rounded-2xl overflow-hidden border border-[#203244] bg-[#0c1622] mb-1 relative shadow-inner">
                        <MapView
                          points={recentPoints}
                          buoys={savedBuoys}
                          circuitRouteLine={activeCircuit?.routeLine}
                          circuitWaypoints={activeCircuit?.waypoints}
                          circuitColor="#A855F7"
                          currentLocation={
                            snapshot.latitude && snapshot.longitude
                              ? { latitude: snapshot.latitude, longitude: snapshot.longitude }
                              : null
                          }
                          followPosition={snapshot.active && !snapshot.paused}
                          routeColor={activeRouteColor}
                          className="w-full h-full"
                        />
                      </div>
                      <div className="flex items-center gap-1.5 text-[#9FD9FF] text-[9px] font-bold">
                        <span
                          className={`w-1.5 h-1.5 rounded-full ${
                            isGpsLocked ? 'bg-[#00FF88]' : 'bg-[#FF9F0A]'
                          }`}
                        />
                        <span>{snapshot.gpsStatus}</span>
                      </div>
                      <div className="text-[#8D9DA8] text-[8px]">
                        {snapshot.accuracyM > 0
                          ? `Precisión GPS ±${snapshot.accuracyM.toFixed(0)} m`
                          : 'Precisión GPS pendiente'}
                      </div>
                      {snapshot.latitude && snapshot.longitude && (
                        <div className="text-white text-[8px] font-mono">
                          {snapshot.latitude.toFixed(5)}, {snapshot.longitude.toFixed(5)}
                        </div>
                      )}
                      <div className="text-[#76F4D0] text-[7px] font-bold text-center mt-1">
                        osmdroid: {cachedTilesCount} teselas en almacenamiento Wear OS
                      </div>
                    </div>
                  )}

                  {page === 3 && (
                    /* PAGE 3: HEART RATE BLE */
                    <div className="flex flex-col items-center w-full max-w-[240px]">
                      <div className="flex items-center gap-2 mb-0.5">
                        <span className="text-[#FF8FA3] text-4xl font-black tabular-nums">
                          {isBleFresh ? ble.bpm : '--'}
                        </span>
                        <span className="text-2xl animate-pulse">♥</span>
                      </div>
                      <span className="text-[#8D9DA8] text-[7px] font-bold tracking-wider mb-1 uppercase">
                        PULSACIONES POR MINUTO
                      </span>
                      <span className="text-[#8D9DA8] text-[8px] mb-2 text-center px-1">
                        {ble.detail}
                      </span>

                      <button
                        onClick={onScanBle}
                        className="w-full py-1.5 bg-[#142333] hover:bg-[#1f354c] text-white text-[9px] font-bold rounded-full border border-[#203244] mb-1 active:scale-95 transition-all cursor-pointer"
                      >
                        {ble.phase === 'SCANNING' ? 'BUSCANDO BANDA…' : 'BUSCAR BANDA BLE (0x180D)'}
                      </button>

                      {(ble.phase === 'CONNECTED' || ble.phase === 'CONNECTING') && (
                        <button
                          onClick={onDisconnectBle}
                          className="w-full py-1 bg-[#421a20] hover:bg-[#522028] text-[#FF8FA3] text-[8px] font-bold rounded-full mb-1 border border-[#FF8FA3]/20 cursor-pointer"
                        >
                          DESCONECTAR
                        </button>
                      )}

                      {ble.candidates.length > 0 && (
                        <div className="w-full flex flex-col gap-1 my-1 max-h-[50px] overflow-y-auto">
                          {ble.candidates.slice(0, 2).map((c) => (
                            <div
                              key={c.address}
                              onClick={() => onConnectBle(c.address)}
                              className="flex items-center justify-between p-1 px-2 rounded-xl bg-[#142333] text-left cursor-pointer hover:bg-[#1f354c]"
                            >
                              <span className="text-white text-[8px] font-bold truncate">
                                {c.name || 'Sensor Cardíaco'}
                              </span>
                              <span className="text-[#76F4D0] text-[7px] font-mono">
                                CONECTAR
                              </span>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}

                  {page === 4 && (
                    /* PAGE 4: PADDLE STROKES (Instant detection, no refractory delay) */
                    <div className="flex flex-col items-center w-full max-w-[240px]">
                      <span className="text-white text-4xl font-black mb-0.5 tabular-nums">
                        {snapshot.strokes}
                      </span>
                      <span className="text-[#8D9DA8] text-[8px] font-bold tracking-wider mb-2 uppercase">
                        PALADAS DETECTADAS EN CUBIERTA
                      </span>

                      <div className="w-full bg-[#0f1b27] border border-[#203244] rounded-2xl p-2 mb-2">
                        <div className="text-[#8D9DA8] text-[7px] font-bold uppercase">CADENCIA ACTUAL</div>
                        <div className="text-[#9FD9FF] text-2xl font-black tabular-nums">
                          {snapshot.cadenceSpm}
                        </div>
                        <div className="text-[#8D9DA8] text-[7px]">paladas por minuto (spm)</div>
                      </div>

                      <div className="p-1 px-2 rounded-xl bg-[#00FF88]/10 border border-[#00FF88]/30 text-[#00FF88] text-[8px] font-bold mb-1">
                        ✓ Periodo refractario eliminado: detección instantánea
                      </div>

                      <p className="text-[#8D9DA8] text-[7px] leading-tight text-center">
                        Picos de acelerómetro triaxial procesados en tiempo real.
                      </p>
                    </div>
                  )}

                  {page === 5 && (
                    /* PAGE 5: BOYAS Y RUTAS */
                    <div className="flex flex-col items-center w-full max-w-[250px]">
                      <span className="text-[#8D9DA8] text-[8px] font-bold mb-1">
                        {savedBuoys.length} puntos guardados
                      </span>

                      <div className="flex gap-1 w-full mb-1.5">
                        <button
                          onClick={() => onSaveBuoy(true)}
                          className="flex-1 py-1.5 bg-[#142333] hover:bg-[#1f354c] text-[#FF8FA3] text-[8px] font-bold rounded-xl border border-[#FF8FA3]/30 cursor-pointer"
                        >
                          + GIRO/BOYA
                        </button>
                        <button
                          onClick={() => onSaveBuoy(false)}
                          className="flex-1 py-1.5 bg-[#142333] hover:bg-[#1f354c] text-[#76F4D0] text-[8px] font-bold rounded-xl border border-[#76F4D0]/30 cursor-pointer"
                        >
                          + PUNTO RUTA
                        </button>
                        <button
                          onClick={onUndoBuoy}
                          className="px-2 py-1.5 bg-[#0f1b27] text-[#8D9DA8] text-[8px] font-bold rounded-xl border border-[#203244] cursor-pointer"
                          title="Deshacer último"
                        >
                          ↶
                        </button>
                      </div>

                      <div className="w-full flex flex-col gap-1 max-h-[85px] overflow-y-auto">
                        {savedBuoys.length === 0 ? (
                          <span className="text-[#8D9DA8] text-[8px] text-center py-2">
                            Sin boyas guardadas.
                          </span>
                        ) : (
                          savedBuoys
                            .slice(-3)
                            .reverse()
                            .map((b) => (
                              <div
                                key={b.id}
                                className="flex items-center justify-between p-1 px-2 rounded-xl bg-[#0f1b27] border border-[#203244] text-left"
                              >
                                <div className="flex flex-col">
                                  <span
                                    className={`text-[8px] font-black ${
                                      b.turnType === 'UTURN'
                                        ? 'text-[#FF9F0A]'
                                        : b.isTurn
                                        ? 'text-[#FF8FA3]'
                                        : 'text-[#76F4D0]'
                                    }`}
                                  >
                                    {b.turnType === 'UTURN'
                                      ? 'GIRO EN U'
                                      : b.turnType === 'BUOY'
                                      ? 'BOYA'
                                      : 'PUNTO RUTA'}
                                  </span>
                                  <span className="text-[#8D9DA8] text-[7px] font-mono">
                                    {b.latitude.toFixed(4)}, {b.longitude.toFixed(4)}
                                  </span>
                                </div>
                                <button
                                  onClick={() => onDeleteBuoy(b.id)}
                                  className="text-[#FF8FA3] hover:text-red-400 font-bold text-sm px-1 cursor-pointer"
                                >
                                  ×
                                </button>
                              </div>
                            ))
                        )}
                      </div>
                    </div>
                  )}

                  {page === 6 && (
                    /* PAGE 6: AJUSTES WEAR OS 5 (Circuito, Unidades y Avisos) */
                    <div className="flex flex-col items-center w-full max-w-[250px] text-center overflow-y-auto max-h-[195px] pr-1">
                      <span className="text-[#76F4D0] text-[8px] font-bold mb-1">
                        CIRCUITO ACTIVO (AUTO-LAP)
                      </span>
                      <div className="grid grid-cols-2 gap-1 w-full mb-1.5">
                        {circuits.map((c) => (
                          <button
                            key={c.id}
                            onClick={() => onSelectCircuit?.(c.id)}
                            className={`py-1 px-1 rounded-xl text-[7px] font-bold border transition-all ${
                              activeCircuit?.id === c.id
                                ? 'bg-[#FFCA7A] text-black border-[#FFCA7A]'
                                : 'bg-[#142333] text-[#A5B7C5] border-[#203244]'
                            }`}
                          >
                            {c.name}
                          </button>
                        ))}
                      </div>

                      <span className="text-[#76F4D0] text-[8px] font-bold mb-1">
                        AVISOS DE ENTRENAMIENTO
                      </span>
                      <div className="grid grid-cols-2 gap-1 w-full mb-2">
                        {(
                          [
                            ['VOICE_AND_VIBRATION', 'Voz y vibración'],
                            ['VOICE_ONLY', 'Solo voz'],
                            ['VIBRATION_ONLY', 'Solo vibración'],
                            ['MUTED', 'Silenciado'],
                          ] as const
                        ).map(([mode, label]) => (
                          <button
                            key={mode}
                            onClick={() => onSetAlertMode?.(mode)}
                            className={`py-1 px-1 rounded-xl text-[6px] font-bold border transition-all ${
                              alertMode === mode
                                ? 'bg-[#76F4D0] text-black border-[#76F4D0]'
                                : 'bg-[#142333] text-[#A5B7C5] border-[#203244]'
                            }`}
                          >
                            {label}
                          </button>
                        ))}
                      </div>

                      <span className="text-[#76F4D0] text-[8px] font-bold mb-1">
                        UNIDADES DE VELOCIDAD
                      </span>
                      <div className="grid grid-cols-2 gap-1 w-full mb-2">
                        {SPEED_UNITS.map((u) => (
                          <button
                            key={u.id}
                            onClick={() => {
                              onSetSpeedUnit?.(u.id);
                              showToast(`Unidad: ${u.symbol}`);
                            }}
                            className={`py-0.5 px-1 rounded-xl text-[7px] font-bold border transition-all cursor-pointer ${
                              speedUnit === u.id
                                ? 'bg-[#76F4D0] text-[#05090D] border-[#76F4D0] shadow-sm'
                                : 'bg-[#142333] text-[#8D9DA8] border-[#203244] hover:text-white'
                            }`}
                          >
                            <span className="block font-black">{u.symbol}</span>
                            <span className="text-[6px] truncate block opacity-80">{u.name}</span>
                          </button>
                        ))}
                      </div>

                      <div className="flex gap-1 w-full mb-1">
                        <button
                          onClick={onExportGpx}
                          className="flex-1 py-1 bg-[#142333] hover:bg-[#1f354c] text-[#76F4D0] text-[8px] font-bold rounded-full border border-[#76F4D0]/30 cursor-pointer"
                        >
                          EXPORTAR GPX
                        </button>
                      </div>
                    </div>
                  )}

                  {page === 7 && (
                    /* PAGE 7: HISTORIAL */
                    <div className="flex flex-col items-center w-full max-w-[250px]">
                      <span className="text-[#8D9DA8] text-[8px] mb-1 font-bold">
                        {sessions.length} entrenamientos guardados
                      </span>
                      <div className="w-full flex flex-col gap-1 max-h-[140px] overflow-y-auto">
                        {sessions.length === 0 ? (
                          <span className="text-[#8D9DA8] text-[8px] py-3">
                            Todavía no hay entrenamientos.
                          </span>
                        ) : (
                          sessions.slice(0, 5).map((s) => (
                            <div
                              key={s.startAt}
                              className="bg-[#0f1b27] border border-[#203244] rounded-2xl p-1.5 text-left"
                            >
                              <div className="flex justify-between items-center text-[7px] text-[#8D9DA8]">
                                <span>{new Date(s.startAt).toLocaleDateString()}</span>
                                <span className="text-[#9FD9FF] font-semibold">
                                  {formatDuration(s.elapsedMs)}
                                </span>
                              </div>
                              <div className="text-[#76F4D0] font-black text-[11px]">
                                {(s.distanceM / 1000).toFixed(2)} km
                              </div>
                              <div className="text-[7px] text-[#8D9DA8] flex gap-2">
                                <span>{s.strokes} paladas</span>
                                <span>• {s.calories} kcal</span>
                                {s.averageBpm > 0 && <span>• FC {s.averageBpm} lpm</span>}
                              </div>
                            </div>
                          ))
                        )}
                      </div>
                    </div>
                  )}
                </>
              )}
            </div>

            {/* Wear OS 5 Circular Page Indicator */}
            {isAmbient ? (
              <button
                onClick={() => setIsAmbient(false)}
                className="py-1 px-4 bg-[#151515] hover:bg-[#252525] border border-white/20 text-white font-bold text-[8px] rounded-full active:scale-95 transition-all shadow-md cursor-pointer"
              >
                SALIR AOD
              </button>
            ) : (
              <div className="flex items-center justify-center gap-1.5 pb-0.5">
                {pageTitles.map((_, i) => (
                  <button
                    key={i}
                    onClick={() => setPage(i)}
                    className={`rounded-full transition-all cursor-pointer ${
                      page === i ? 'w-4 h-1.5 bg-[#76F4D0]' : 'w-1.5 h-1.5 bg-[#203244] hover:bg-[#344b62]'
                    }`}
                  />
                ))}
              </div>
            )}
          </div>

          {/* Offline Local Voice AI Microphone & Floating Bubble Component */}
          {!isAmbient && (
            <VoiceAiOverlay
              snapshot={snapshot}
              ble={ble}
              activeWorkoutState={activeWorkoutState}
              laps={laps}
              onStartSession={handleStartWithSound}
              onPauseResumeSession={onPauseResumeSession}
              onFinishSession={handleFinishWithSound}
              onSaveBuoy={onSaveBuoy}
              onTriggerLap={onTriggerLap}
              onWakeActiveChange={setIsVoiceWakeActive}
            />
          )}

          {/* Confirm Dialog for Finish Session */}
          {showFinishConfirm && (
            <div className="absolute inset-0 bg-black/90 backdrop-blur-sm z-50 flex flex-col items-center justify-center p-6 text-center">
              <span className="text-[#FF8FA3] text-2xl mb-1">■</span>
              <span className="text-white font-black text-sm mb-1">¿Finalizar sesión?</span>
              <span className="text-[#8D9DA8] text-[9px] mb-4">
                Se guardará el track GPX y se calculará el análisis de vueltas.
              </span>
              <div className="flex gap-2 w-full max-w-[200px]">
                <button
                  onClick={() => setShowFinishConfirm(false)}
                  className="flex-1 py-1.5 rounded-full bg-[#1c2936] text-white text-[9px] font-bold cursor-pointer"
                >
                  Seguir
                </button>
                <button
                  onClick={handleFinishWithSound}
                  className="flex-1 py-1.5 rounded-full bg-[#421a20] text-[#FF8FA3] text-[9px] font-bold border border-[#FF8FA3]/30 cursor-pointer"
                >
                  Finalizar
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Carousel Arrow Buttons on the physical watch sides */}
        {!isAmbient && (
          <>
            <button
              onClick={() => setPage((p) => (p > 0 ? p - 1 : pageTitles.length - 1))}
              className="absolute -left-6 sm:-left-7 top-1/2 -translate-y-1/2 w-8 h-12 bg-[#0c1622]/90 hover:bg-[#152332] border border-[#203244] rounded-r-2xl flex items-center justify-center text-[#76F4D0] font-black text-base transition-all z-20 cursor-pointer shadow-lg active:scale-90"
              title="Página anterior"
            >
              ‹
            </button>
            <button
              onClick={() => setPage((p) => (p < pageTitles.length - 1 ? p + 1 : 0))}
              className="absolute -right-6 sm:-right-7 top-1/2 -translate-y-1/2 w-8 h-12 bg-[#0c1622]/90 hover:bg-[#152332] border border-[#203244] rounded-l-2xl flex items-center justify-center text-[#76F4D0] font-black text-base transition-all z-20 cursor-pointer shadow-lg active:scale-90"
              title="Página siguiente"
            >
              ›
            </button>
          </>
        )}
      </div>

      {/* Review Dialog when session is finished */}
      {pendingReview && (
        <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-3 select-text">
          <div className="bg-[#0c1825] border border-[#1b2b3c] rounded-3xl p-5 max-w-sm w-full shadow-2xl flex flex-col items-center text-center">
            <span className="text-[#76F4D0] text-3xl mb-1">🏁</span>
            <h3 className="text-white font-black text-base mb-0.5">RESUMEN DEL ENTRENO</h3>
            <span className="text-[#8D9DA8] text-[10px] mb-3">
              {pendingReview.isCircuit
                ? 'Circuito cerrado detectado automáticamente'
                : 'Recorrido en aguas abiertas'}
            </span>

            <div className="grid grid-cols-2 gap-2 w-full mb-3 text-left">
              <div className="bg-[#122232] p-2 rounded-2xl">
                <span className="text-[#8D9DA8] text-[8px] font-bold block uppercase">
                  DISTANCIA
                </span>
                <span className="text-white font-black text-base">
                  {(pendingReview.distanceM / 1000).toFixed(2)} km
                </span>
              </div>
              <div className="bg-[#122232] p-2 rounded-2xl">
                <span className="text-[#8D9DA8] text-[8px] font-bold block uppercase">
                  DURACIÓN
                </span>
                <span className="text-[#76F4D0] font-black text-base font-mono">
                  {formatDuration(pendingReview.elapsedMs)}
                </span>
              </div>
              <div className="bg-[#122232] p-2 rounded-2xl">
                <span className="text-[#8D9DA8] text-[8px] font-bold block uppercase">
                  PALADAS
                </span>
                <span className="text-white font-bold text-sm">
                  {pendingReview.strokes}
                </span>
              </div>
              <div className="bg-[#122232] p-2 rounded-2xl">
                <span className="text-[#8D9DA8] text-[8px] font-bold block uppercase">
                  CALORÍAS
                </span>
                <span className="text-[#FF8FA3] font-bold text-sm">
                  {pendingReview.calories} kcal
                </span>
              </div>
            </div>

            {/* Laps Summary in Review Dialog */}
            {pendingReview.laps && pendingReview.laps.length > 0 && (
              <div className="w-full bg-[#122232] p-2.5 rounded-2xl mb-3 text-left">
                <span className="text-[#FFCA7A] text-[9px] font-bold uppercase block mb-1">
                  VUELTAS ({pendingReview.laps.length}):
                </span>
                <div className="max-h-[60px] overflow-y-auto space-y-1">
                  {pendingReview.laps.map((l) => (
                    <div key={l.lapNumber} className="flex justify-between text-[8px]">
                      <span className="text-white">
                        V{l.lapNumber} {l.isBestLap && '🏆 Mejor'}
                      </span>
                      <span className="text-[#76F4D0] font-mono">
                        {Math.round(l.durationMs / 1000)}s · {l.avgSpeedKmh} km/h
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="flex flex-col gap-1.5 w-full">
              <button
                onClick={() => onSaveBoth(pendingReview)}
                className="w-full py-2 bg-gradient-to-r from-[#76F4D0] to-[#00F0FF] text-[#05090D] font-black text-xs rounded-xl shadow-md cursor-pointer hover:opacity-90"
              >
                GUARDAR ENTRENO Y CIRCUITO
              </button>
              <div className="flex gap-1.5 w-full">
                <button
                  onClick={() => onSaveWorkout(pendingReview)}
                  className="flex-1 py-1.5 bg-[#172a3d] hover:bg-[#20384f] text-white text-[10px] font-bold rounded-xl cursor-pointer"
                >
                  Solo entreno
                </button>
                <button
                  onClick={() => onSaveCircuit(pendingReview)}
                  className="flex-1 py-1.5 bg-[#172a3d] hover:bg-[#20384f] text-[#76F4D0] text-[10px] font-bold rounded-xl cursor-pointer"
                >
                  Solo circuito
                </button>
                <button
                  onClick={() => onDeleteReview(pendingReview)}
                  className="px-3 py-1.5 bg-[#3d181e] hover:bg-[#4f2028] text-[#FF8FA3] text-[10px] font-bold rounded-xl cursor-pointer"
                >
                  Descartar
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
