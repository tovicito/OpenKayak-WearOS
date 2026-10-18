import React, { useState, useEffect, useRef } from 'react';
import {
  TrackSnapshot,
  BleHrState,
  SavedBuoy,
  FinishedSession,
  GeoSample,
  SessionSummary,
} from '../types/openkayak';
import { MapView } from './MapView';
import { VoiceAiOverlay } from './VoiceAiOverlay';
import { StorageService } from '../services/storage';
import { GpxExporter } from '../services/gpxExporter';
import { RouteAnalyzer } from '../services/routeAnalyzer';
import { OsmdroidTileStore } from '../services/osmdroidTileStore';

interface WatchDeckViewProps {
  snapshot: TrackSnapshot;
  ble: BleHrState;
  recentPoints: GeoSample[];
  savedBuoys: SavedBuoy[];
  routeColorHex: string;
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
  const [settingsStatus, setSettingsStatus] = useState<string>('Health Connect: listo (emulado)');
  const [cachedTilesCount, setCachedTilesCount] = useState<number>(() =>
    OsmdroidTileStore.getCachedCount()
  );
  const [mapPackStatus, setMapPackStatus] = useState<string>(
    'Caché osmdroid Wear OS activa (hasta 512 teselas) · MBTiles disponible'
  );
  const [isVoiceWakeActive, setIsVoiceWakeActive] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);

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

  const pacePerKm = (elapsedMs: number, distanceM: number): string => {
    if (distanceM < 100) return '--:--';
    const totalSeconds = Math.floor(elapsedMs / 1000 / (distanceM / 1000));
    const min = Math.floor(totalSeconds / 60);
    const sec = totalSeconds % 60;
    return `${min}:${sec.toString().padStart(2, '0')}`;
  };

  const isBleFresh =
    ble.phase === 'CONNECTED' &&
    ble.bpm >= 25 &&
    ble.bpm <= 240 &&
    ble.lastSampleAt > 0 &&
    Date.now() - ble.lastSampleAt < 12000;

  const hexToCss = (hex: string): string => {
    if (hex.startsWith('FF') && hex.length === 8) return `#${hex.slice(2)}`;
    return hex.startsWith('#') ? hex : `#${hex}`;
  };

  const activeRouteColor = hexToCss(routeColorHex);

  const handleSaveProfile = () => {
    const w = parseFloat(weight);
    const a = parseInt(age, 10);
    if (!isNaN(w) && w >= 25 && w <= 250 && !isNaN(a) && a >= 8 && a <= 100) {
      StorageService.saveAthleteProfile({ weightKg: w, ageYears: a });
      alert('Perfil guardado correctamente');
    } else {
      alert('Valores no válidos (peso 25-250 kg, edad 8-100 años)');
    }
  };

  const handleImportMbtilesMock = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      setMapPackStatus(`Paquete importado: ${file.name} (${(file.size / 1024 / 1024).toFixed(1)} MB)`);
    }
  };

  return (
    <div className="flex flex-col items-center justify-center p-2 sm:p-4">
      {/* Top Toggle for Watch Simulation / Mode */}
      <div className="flex items-center gap-2 mb-3">
        <button
          onClick={() => setIsAmbient(!isAmbient)}
          className={`px-3 py-1 rounded-full text-xs font-bold transition-all border ${
            isAmbient
              ? 'bg-amber-400 text-black border-amber-300 shadow-md shadow-amber-400/20'
              : 'bg-[#101820] text-[#9FD9FF] border-[#24313C] hover:bg-[#17232D]'
          }`}
        >
          {isAmbient ? 'MODO AOD ACTIVO' : 'MODO AOD (PANTALLA AMBIENTE)'}
        </button>
      </div>

      {/* Watch Wrapper with Pure Green Glowing Border (solo el borde, verde puro) when "hola reloj" is heard */}
      <div className="relative flex items-center justify-center">
        {isVoiceWakeActive && !isAmbient && <div className="wearos-green-border" />}

        {/* Circular Watch Frame (representing Xiaomi Watch 2 Pro on kayak deck) */}
        <div
          className={`relative w-[340px] h-[340px] sm:w-[380px] sm:h-[380px] rounded-full border-8 shadow-2xl transition-all duration-300 flex items-center justify-center overflow-hidden z-10 ${
            isAmbient
              ? 'bg-black border-[#1a1a1a] shadow-black'
              : 'bg-[#05090D] border-[#18232d] shadow-[#000000]/80'
          }`}
          style={{
            boxShadow: isAmbient
              ? '0 0 40px rgba(0,0,0,0.9)'
              : '0 0 50px rgba(118,244,208,0.06), inset 0 0 25px rgba(0,0,0,0.8)',
          }}
        >
        {/* Decorative Watch Bezel Indices */}
        <div className="absolute inset-1 rounded-full pointer-events-none border border-white/5" />
        <div className="absolute top-1 left-1/2 -translate-x-1/2 w-1.5 h-1 bg-white/20 rounded-full pointer-events-none" />

        {/* Watch Content Container */}
        <div className="relative w-full h-full flex flex-col items-center justify-between px-7 pt-4 pb-4 select-none z-10 text-center">
          {/* Header (Hidden in Ambient) */}
          {!isAmbient && (
            <div className="flex flex-col items-center mt-1">
              <span className="text-[#76F4D0] font-black text-[11px] tracking-[2px] leading-tight">
                OPENKAYAK
              </span>
              <span className="text-[#8D9DA8] font-bold text-[8px] tracking-[1px] uppercase">
                {pageTitles[page] || 'OPENKAYAK'}
              </span>
            </div>
          )}

          {/* PAGE CONTENT */}
          <div className="w-full flex-1 flex flex-col items-center justify-center overflow-y-auto no-scrollbar py-1">
            {isAmbient ? (
              /* AMBIENT MODE DASHBOARD */
              <div className="flex flex-col items-center justify-center w-full">
                <span className="text-white text-2xl font-black tracking-tight">
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
                  {/* Radial Vignette */}
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
                      {snapshot.speedKmh.toFixed(1)}
                    </div>
                    <div className="text-[#8D9DA8] text-[6px]">km/h</div>
                  </div>
                </div>

                <span className="text-[#455460] text-[7px] mt-2">
                  AOD · bajo refresco · {snapshot.gpsStatus}
                </span>
              </div>
            ) : (
              /* INTERACTIVE PAGES */
              <>
                {page === 0 && (
                  /* PAGE 0: DASHBOARD */
                  <div className="flex flex-col items-center w-full max-w-[250px]">
                    <span
                      className={`text-[9px] font-bold tracking-wider mb-0.5 ${
                        snapshot.active
                          ? snapshot.paused
                            ? 'text-[#FFCA7A]'
                            : 'text-[#76F4D0]'
                          : 'text-[#76F4D0]'
                      }`}
                    >
                      {snapshot.active
                        ? snapshot.paused
                          ? 'EN PAUSA'
                          : 'EN SESIÓN'
                        : 'LISTO PARA REMAR'}
                    </span>

                    <span className="text-[#9FD9FF] text-[9px] font-bold tracking-wider">
                      DISTANCIA
                    </span>
                    <div className="flex items-baseline gap-1 my-0.5">
                      <span className="text-white text-3xl font-black">
                        {(snapshot.distanceM / 1000).toFixed(2)}
                      </span>
                      <span className="text-[#76F4D0] text-sm font-bold">km</span>
                    </div>

                    <span className="text-white text-base font-semibold mb-2">
                      {formatDuration(snapshot.elapsedMs)}
                    </span>

                    {/* Metric Tiles */}
                    <div className="grid grid-cols-3 gap-1 w-full mb-2">
                      <div className="bg-[#101820] border border-[#24313C] rounded-xl py-1 px-1">
                        <div className="text-[#8D9DA8] text-[6px] font-bold">VELOCIDAD</div>
                        <div className="text-[#76F4D0] text-xs font-black">
                          {snapshot.speedKmh.toFixed(1)}
                        </div>
                        <div className="text-[#8D9DA8] text-[6px]">km/h</div>
                      </div>
                      <div className="bg-[#101820] border border-[#24313C] rounded-xl py-1 px-1">
                        <div className="text-[#8D9DA8] text-[6px] font-bold">PULSO</div>
                        <div className="text-[#FF8FA3] text-xs font-black">
                          {isBleFresh ? ble.bpm : '--'}
                        </div>
                        <div className="text-[#8D9DA8] text-[6px]">lpm</div>
                      </div>
                      <div className="bg-[#101820] border border-[#24313C] rounded-xl py-1 px-1">
                        <div className="text-[#8D9DA8] text-[6px] font-bold">RITMO/KM</div>
                        <div className="text-[#9FD9FF] text-xs font-black">
                          {pacePerKm(snapshot.elapsedMs, snapshot.distanceM)}
                        </div>
                        <div className="text-[#8D9DA8] text-[6px]">min/km</div>
                      </div>
                    </div>

                    {/* GPS & BLE Status Indicators */}
                    <div className="flex items-center justify-center gap-2 mb-2 text-[7px] text-[#8D9DA8]">
                      <div className="flex items-center gap-1">
                        <span
                          className={`w-1.5 h-1.5 rounded-full ${
                            snapshot.gpsStatus.toLowerCase().includes('preciso') ||
                            snapshot.gpsStatus.toLowerCase().includes('activo')
                              ? 'bg-[#76F4D0]'
                              : 'bg-[#53616A]'
                          }`}
                        />
                        <span className="truncate max-w-[70px]">{snapshot.gpsStatus}</span>
                      </div>
                      <div className="flex items-center gap-1">
                        <span
                          className={`w-1.5 h-1.5 rounded-full ${
                            isBleFresh ? 'bg-[#FF8FA3]' : 'bg-[#53616A]'
                          }`}
                        />
                        <span>{isBleFresh ? 'BANDA BLE' : 'SIN FC'}</span>
                      </div>
                    </div>

                    {/* Controls */}
                    <div className="w-full">
                      {!snapshot.active ? (
                        <button
                          onClick={onStartSession}
                          className="w-full py-2 bg-[#76F4D0] hover:bg-[#5de0bd] text-[#05090D] font-black text-xs rounded-full shadow-md shadow-[#76F4D0]/20 active:scale-95 transition-all"
                        >
                          INICIAR PIRAGÜISMO
                        </button>
                      ) : (
                        <div className="flex gap-2 w-full">
                          <button
                            onClick={onPauseResumeSession}
                            className="flex-1 py-1.5 bg-[#17232D] hover:bg-[#20313f] text-white font-bold text-[10px] rounded-full border border-[#273A46] active:scale-95 transition-all"
                          >
                            {snapshot.paused ? 'REANUDAR' : 'PAUSA'}
                          </button>
                          <button
                            onClick={() => setShowFinishConfirm(true)}
                            className="flex-1 py-1.5 bg-[#4B2428] hover:bg-[#5e2d32] text-[#FF8FA3] font-bold text-[10px] rounded-full border border-[#FF8FA3]/30 active:scale-95 transition-all"
                          >
                            FINALIZAR
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                )}

                {page === 1 && (
                  /* PAGE 1: ROUTE MAP */
                  <div className="flex flex-col items-center w-full max-w-[260px]">
                    <div className="w-full h-[115px] rounded-2xl overflow-hidden border border-[#273A46] bg-[#101820] mb-1">
                      <MapView
                        points={recentPoints}
                        buoys={savedBuoys}
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
                    <div className="text-[#9FD9FF] text-[9px] font-bold">
                      {snapshot.gpsStatus}
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
                    <div className="text-[#8D9DA8] text-[7px] leading-tight text-center mt-1">
                      Cian: recorrido · Rosa: boyas · Naranja: U-turns
                    </div>
                    <div className="text-[#76F4D0] text-[7px] font-bold text-center mt-0.5">
                      osmdroid: {cachedTilesCount} teselas guardadas en Wear OS
                    </div>
                  </div>
                )}

                {page === 2 && (
                  /* PAGE 2: HEART RATE BLE */
                  <div className="flex flex-col items-center w-full max-w-[240px]">
                    <span className="text-[#FF8FA3] text-3xl font-black mb-0.5">
                      {isBleFresh ? ble.bpm : '--'}
                    </span>
                    <span className="text-[#8D9DA8] text-[7px] font-bold tracking-wider mb-1">
                      PULSACIONES/MIN
                    </span>
                    <span className="text-[#8D9DA8] text-[8px] mb-2 text-center px-1">
                      {ble.detail}
                    </span>

                    <button
                      onClick={onScanBle}
                      className="w-full py-1.5 bg-[#17232D] hover:bg-[#223545] text-white text-[9px] font-bold rounded-full border border-[#2a3c4a] mb-1 transition-all"
                    >
                      {ble.phase === 'SCANNING' ? 'BUSCANDO…' : 'BUSCAR BANDA BLE'}
                    </button>

                    {(ble.phase === 'CONNECTED' || ble.phase === 'CONNECTING') && (
                      <button
                        onClick={onDisconnectBle}
                        className="w-full py-1 bg-[#45242A] hover:bg-[#572d35] text-[#FF8FA3] text-[8px] font-bold rounded-full mb-1 border border-[#FF8FA3]/20"
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
                            className="flex items-center justify-between p-1 px-2 rounded-lg bg-[#17232D] text-left cursor-pointer hover:bg-[#20313f]"
                          >
                            <span className="text-white text-[8px] font-semibold truncate max-w-[120px]">
                              {c.name}
                            </span>
                            <span className="text-[#76F4D0] text-[7px] font-bold">
                              CONECTAR ›
                            </span>
                          </div>
                        ))}
                      </div>
                    )}

                    <span className="text-[#8D9DA8] text-[7px] leading-tight text-center mt-1">
                      El sensor óptico de muñeca no sirve montado en cubierta; usa una banda BLE.
                    </span>
                  </div>
                )}

                {page === 3 && (
                  /* PAGE 3: PADDLE STROKES */
                  <div className="flex flex-col items-center w-full max-w-[240px]">
                    <span className="text-white text-4xl font-black mb-0.5">
                      {snapshot.strokes}
                    </span>
                    <span className="text-[#8D9DA8] text-[8px] font-bold tracking-wider mb-2">
                      PALADAS DETECTADAS
                    </span>

                    <div className="w-full bg-[#101820] border border-[#24313C] rounded-xl p-2 mb-2">
                      <div className="text-[#8D9DA8] text-[7px] font-bold">RITMO ACTUAL</div>
                      <div className="text-[#9FD9FF] text-xl font-black">
                        {snapshot.cadenceSpm}
                      </div>
                      <div className="text-[#8D9DA8] text-[7px]">paladas/min</div>
                    </div>

                    <p className="text-white text-[8px] leading-tight text-center mb-1">
                      Algoritmo de picos de aceleración en cubierta con periodo refractario.
                    </p>
                    <p className="text-[#8D9DA8] text-[7px] leading-tight text-center">
                      Oleaje y golpes pueden sumar falsos positivos; calibrado para piragua.
                    </p>
                  </div>
                )}

                {page === 4 && (
                  /* PAGE 4: BUOYS & ROUTES */
                  <div className="flex flex-col items-center w-full max-w-[250px]">
                    <span className="text-[#8D9DA8] text-[8px] font-bold mb-1">
                      {savedBuoys.length} puntos guardados
                    </span>

                    <div className="flex gap-1 w-full mb-1.5">
                      <button
                        onClick={() => onSaveBuoy(true)}
                        className="flex-1 py-1 bg-[#17232D] hover:bg-[#20313f] text-[#FF8FA3] text-[8px] font-bold rounded-lg border border-[#FF8FA3]/30"
                      >
                        + GIRO/BOYA
                      </button>
                      <button
                        onClick={() => onSaveBuoy(false)}
                        className="flex-1 py-1 bg-[#17232D] hover:bg-[#20313f] text-[#76F4D0] text-[8px] font-bold rounded-lg border border-[#76F4D0]/30"
                      >
                        + PUNTO RUTA
                      </button>
                      <button
                        onClick={onUndoBuoy}
                        className="px-2 py-1 bg-[#101820] text-[#8D9DA8] text-[8px] font-bold rounded-lg border border-[#24313C]"
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
                              className="flex items-center justify-between p-1 px-2 rounded-lg bg-[#101820] border border-[#24313C] text-left"
                            >
                              <div className="flex flex-col">
                                <span
                                  className={`text-[8px] font-black ${
                                    b.turnType === 'UTURN'
                                      ? 'text-[#FFB54A]'
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
                                className="text-[#FF8FA3] hover:text-red-400 font-bold text-sm px-1"
                              >
                                ×
                              </button>
                            </div>
                          ))
                      )}
                    </div>
                  </div>
                )}

                {page === 5 && (
                  /* PAGE 5: SETTINGS */
                  <div className="flex flex-col items-center w-full max-w-[250px] text-center">
                    <span className="text-[#8D9DA8] text-[8px] mb-1">{settingsStatus}</span>

                    <div className="grid grid-cols-2 gap-1 w-full mb-1">
                      <div className="bg-[#17232D] p-1 rounded-lg text-left">
                        <label className="text-[#8D9DA8] text-[7px]">Peso (kg)</label>
                        <input
                          type="number"
                          value={weight}
                          onChange={(e) => setWeight(e.target.value)}
                          className="w-full bg-transparent text-white text-[10px] font-bold focus:outline-none"
                        />
                      </div>
                      <div className="bg-[#17232D] p-1 rounded-lg text-left">
                        <label className="text-[#8D9DA8] text-[7px]">Edad</label>
                        <input
                          type="number"
                          value={age}
                          onChange={(e) => setAge(e.target.value)}
                          className="w-full bg-transparent text-white text-[10px] font-bold focus:outline-none"
                        />
                      </div>
                    </div>

                    <button
                      onClick={handleSaveProfile}
                      className="w-full py-1 bg-[#17232D] hover:bg-[#20313f] text-[#9FD9FF] text-[8px] font-bold rounded-full mb-1.5 border border-[#9FD9FF]/30"
                    >
                      GUARDAR PERFIL
                    </button>

                    <div className="flex gap-1 w-full mb-1">
                      <button
                        onClick={onExportGpx}
                        className="flex-1 py-1 bg-[#17232D] hover:bg-[#20313f] text-[#76F4D0] text-[8px] font-bold rounded-full border border-[#76F4D0]/30"
                      >
                        EXPORTAR GPX
                      </button>
                      <button
                        onClick={() => fileInputRef.current?.click()}
                        className="flex-1 py-1 bg-[#17232D] hover:bg-[#20313f] text-[#9FD9FF] text-[8px] font-bold rounded-full border border-[#9FD9FF]/30"
                      >
                        MAPA MBTILES
                      </button>
                      <input
                        type="file"
                        ref={fileInputRef}
                        onChange={handleImportMbtilesMock}
                        accept=".mbtiles,.tar"
                        className="hidden"
                      />
                    </div>
                    <span className="text-[#76F4D0] text-[7px] font-bold block max-w-[240px]">
                      osmdroid: {cachedTilesCount} / 512 teselas offline en Wear OS
                    </span>
                    <span className="text-[#8D9DA8] text-[6px] truncate max-w-[240px]">
                      {mapPackStatus}
                    </span>
                  </div>
                )}

                {page === 6 && (
                  /* PAGE 6: HISTORY */
                  <div className="flex flex-col items-center w-full max-w-[250px]">
                    <span className="text-[#8D9DA8] text-[8px] mb-1">
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
                            className="bg-[#101820] border border-[#24313C] rounded-xl p-1.5 text-left"
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

          {/* Bottom Dot Nav / AOD Exit */}
          {isAmbient ? (
            <button
              onClick={() => setIsAmbient(false)}
              className="py-1 px-4 bg-[#151515] hover:bg-[#252525] border border-white/20 text-white font-bold text-[8px] rounded-full active:scale-95 transition-all shadow-md"
            >
              SALIR AOD
            </button>
          ) : (
            <div className="flex items-center justify-center gap-1.5 pb-1">
              {pageTitles.map((_, i) => (
                <button
                  key={i}
                  onClick={() => setPage(i)}
                  className={`w-1.5 h-1.5 rounded-full transition-all ${
                    page === i ? 'bg-[#76F4D0] scale-125' : 'bg-[#2a3c4a]'
                  }`}
                />
              ))}
            </div>
          )}
        </div>

        {/* Permanent Lateral Navigation Arrows ‹ and › */}
        {!isAmbient && (
          <>
            <button
              onClick={() => setPage((p) => (p + 6) % 7)}
              className="absolute left-1 top-1/2 -translate-y-1/2 w-7 h-7 rounded-full bg-[#131E26] hover:bg-[#1a2933] border border-[#314550] text-[#76F4D0] font-bold text-lg flex items-center justify-center z-20 active:scale-90 transition-all cursor-pointer shadow-md"
              title="Página anterior"
            >
              ‹
            </button>
            <button
              onClick={() => setPage((p) => (p + 1) % 7)}
              className="absolute right-1 top-1/2 -translate-y-1/2 w-7 h-7 rounded-full bg-[#131E26] hover:bg-[#1a2933] border border-[#314550] text-[#76F4D0] font-bold text-lg flex items-center justify-center z-20 active:scale-90 transition-all cursor-pointer shadow-md"
              title="Página siguiente"
            >
              ›
            </button>
          </>
        )}

        {/* CONFIRM FINISH MODAL */}
        {showFinishConfirm && !isAmbient && (
          <div className="absolute inset-0 bg-black/85 backdrop-blur-sm z-30 flex items-center justify-center p-6">
            <div className="bg-[#17232D] border border-[#273A46] rounded-2xl p-4 text-center max-w-[260px] shadow-2xl">
              <h4 className="text-white font-bold text-sm mb-1">¿Terminar sesión?</h4>
              <p className="text-[#8D9DA8] text-[9px] leading-tight mb-3">
                Se analizará el GPX para detectar giros y posibles circuitos. Después podrás
                guardar o borrar el resultado.
              </p>
              <div className="flex gap-2">
                <button
                  onClick={() => setShowFinishConfirm(false)}
                  className="flex-1 py-1.5 bg-[#101820] text-white font-bold text-[9px] rounded-full border border-[#24313C]"
                >
                  SEGUIR
                </button>
                <button
                  onClick={() => {
                    setShowFinishConfirm(false);
                    onFinishSession();
                  }}
                  className="flex-1 py-1.5 bg-[#76F4D0] text-[#05090D] font-bold text-[9px] rounded-full shadow-md shadow-[#76F4D0]/20"
                >
                  FINALIZAR
                </button>
              </div>
            </div>
          </div>
        )}

        {/* FINISH REVIEW MODAL (Post-Entreno) */}
        {pendingReview && !isAmbient && (
          <div className="absolute inset-0 bg-black/90 backdrop-blur-md z-40 flex items-center justify-center p-3">
            <div className="bg-[#17232D] border border-[#273A46] rounded-2xl p-3 text-center w-full max-h-[320px] overflow-y-auto no-scrollbar shadow-2xl flex flex-col items-center">
              <span className="text-[#76F4D0] font-black text-[9px] tracking-wider mb-0.5">
                RESUMEN DEL ENTRENAMIENTO
              </span>
              <span className="text-white font-extrabold text-sm mb-1">
                {(pendingReview.distanceM / 1000).toFixed(2)} km ·{' '}
                {formatDuration(pendingReview.elapsedMs)}
              </span>

              {/* Heart rate tiles */}
              <div className="grid grid-cols-3 gap-1 w-full mb-1.5">
                <div className="bg-[#101820] p-1 rounded-lg">
                  <div className="text-[#8D9DA8] text-[6px]">FC MED.</div>
                  <div className="text-[#FF8FA3] text-[10px] font-bold">
                    {pendingReview.averageBpm > 0 ? pendingReview.averageBpm : '--'}
                  </div>
                </div>
                <div className="bg-[#101820] p-1 rounded-lg">
                  <div className="text-[#8D9DA8] text-[6px]">FC MÁX.</div>
                  <div className="text-[#FF8FA3] text-[10px] font-bold">
                    {pendingReview.maxBpm > 0 ? pendingReview.maxBpm : '--'}
                  </div>
                </div>
                <div className="bg-[#101820] p-1 rounded-lg">
                  <div className="text-[#8D9DA8] text-[6px]">FC MÍN.</div>
                  <div className="text-[#FF8FA3] text-[10px] font-bold">
                    {pendingReview.minBpm > 0 ? pendingReview.minBpm : '--'}
                  </div>
                </div>
              </div>

              <div className="text-[#9FD9FF] text-[8px] font-semibold mb-0.5">
                {pendingReview.calories} kcal estimadas · {pendingReview.strokes} paladas ·{' '}
                {pendingReview.turns.length} giros
              </div>

              {pendingReview.repeatedBuoyCount > 0 && (
                <div className="text-[#76F4D0] text-[7px] font-bold mb-0.5">
                  {pendingReview.repeatedBuoyCount} posibles boyas repetidas agrupadas
                </div>
              )}
              {pendingReview.isCircuit && (
                <div className="text-[#76F4D0] text-[7px] font-black tracking-wide mb-1">
                  POSIBLE CIRCUITO CERRADO DETECTADO
                </div>
              )}

              {/* Map Preview in Review */}
              <div className="w-full h-[90px] rounded-xl overflow-hidden border border-[#24313C] bg-black my-1">
                <MapView
                  points={pendingReview.points}
                  buoys={pendingReview.turns}
                  interactive={false}
                  followPosition={false}
                  routeColor={activeRouteColor}
                  className="w-full h-full"
                />
              </div>

              {/* Action Buttons */}
              <div className="flex flex-col gap-1 w-full mt-1.5">
                <button
                  onClick={() => onSaveBoth(pendingReview)}
                  className="w-full py-1 bg-[#76F4D0] text-[#05090D] font-bold text-[8px] rounded-full shadow-sm"
                >
                  GUARDAR ENTRENO Y CIRCUITO
                </button>
                <button
                  onClick={() => onSaveWorkout(pendingReview)}
                  className="w-full py-1 bg-[#101820] hover:bg-[#1a2734] text-white font-bold text-[8px] rounded-full border border-[#24313C]"
                >
                  GUARDAR ENTRENO
                </button>
                <button
                  onClick={() => onSaveCircuit(pendingReview)}
                  className="w-full py-1 bg-[#101820] hover:bg-[#1a2734] text-[#9FD9FF] font-bold text-[8px] rounded-full border border-[#24313C]"
                >
                  GUARDAR CIRCUITO
                </button>
                <button
                  onClick={() => onDeleteReview(pendingReview)}
                  className="w-full py-1 bg-[#4B2428] hover:bg-[#5e2d32] text-[#FF8FA3] font-bold text-[8px] rounded-full"
                >
                  BORRAR ENTRENO Y CIRCUITO
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Offline Voice AI Chat Overlay (Escucha "hola reloj", Borde colorinchis, Di lo que quieras) */}
        {!isAmbient && (
          <VoiceAiOverlay
            snapshot={snapshot}
            ble={ble}
            onStartSession={onStartSession}
            onPauseResumeSession={onPauseResumeSession}
            onFinishSession={onFinishSession}
            onSaveBuoy={onSaveBuoy}
            onWakeActiveChange={setIsVoiceWakeActive}
          />
        )}
      </div>
    </div>
  </div>
  );
};
