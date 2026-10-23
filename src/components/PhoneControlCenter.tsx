import React, { useState, useRef } from 'react';
import {
  SessionSummary,
  SavedBuoy,
  AppSettings,
  GeoSample,
  SpeedUnit,
  CircuitDefinition,
  CircuitWaypoint,
  StructuredWorkout,
  AlertMode,
  WorkoutPhase,
} from '../types/openkayak';
import { MapView } from './MapView';
import { WorkoutAnalyticsDialog } from './WorkoutAnalyticsDialog';
import { PhoneCoachChatbot } from './PhoneCoachChatbot';
import { StorageService } from '../services/storage';
import { GpxExporter } from '../services/gpxExporter';
import { SPEED_UNITS, formatSpeed } from '../services/speedUnits';
import { parseWorkoutOffline } from '../services/naturalLanguageWorkoutParser';
import { calculateDistance } from '../services/turnAiModel';

interface PhoneControlCenterProps {
  workouts: SessionSummary[];
  buoys: SavedBuoy[];
  currentCircuit: { points: GeoSample[]; label: string; gpxContent?: string } | null;
  circuits?: CircuitDefinition[];
  onSaveCircuitDefinition?: (c: CircuitDefinition) => void;
  onDeleteCircuitDefinition?: (id: string) => void;
  structuredWorkouts?: StructuredWorkout[];
  onSaveStructuredWorkout?: (w: StructuredWorkout) => void;
  onDeleteStructuredWorkout?: (id: string) => void;
  activeWorkoutId?: string | null;
  onSelectActiveWorkout?: (id: string | null) => void;
  settings: AppSettings;
  onUpdateSettings: (newSettings: Partial<AppSettings>) => void;
  onAddBuoy: (lat: number, lon: number, type: 'BUOY' | 'UTURN' | 'GIRO' | 'PUNTO DE RUTA') => void;
  onDeleteBuoy: (id: number) => void;
  onDeleteWorkout: (startAt: number) => void;
  onImportCircuitGpx: (content: string, name: string) => void;
  onDeleteCircuit: () => void;
  onTriggerSync: () => void;
}

export const PhoneControlCenter: React.FC<PhoneControlCenterProps> = ({
  workouts,
  buoys,
  currentCircuit,
  circuits = [],
  onSaveCircuitDefinition,
  onDeleteCircuitDefinition,
  structuredWorkouts = [],
  onSaveStructuredWorkout,
  onDeleteStructuredWorkout,
  activeWorkoutId,
  onSelectActiveWorkout,
  settings,
  onUpdateSettings,
  onAddBuoy,
  onDeleteBuoy,
  onDeleteWorkout,
  onImportCircuitGpx,
  onDeleteCircuit,
  onTriggerSync,
}) => {
  // Tabs: 0: Entrenos, 1: Chatbot Coach IA, 2: Series y Vueltas, 3: Mapa y boyas, 4: Ajustes, 5: APKs WearOS
  const [tab, setTab] = useState(0);
  const [statusMessage, setStatusMessage] = useState(
    'Abre OpenKayak en el reloj y solicita la sincronización.'
  );
  const [selectedWorkout, setSelectedWorkout] = useState<SessionSummary | null>(null);
  const [detailWorkout, setDetailWorkout] = useState<SessionSummary | null>(null);

  // Map & Buoys state
  const [pickedCoord, setPickedCoord] = useState<{ latitude: number; longitude: number }>({
    latitude: 43.5585,
    longitude: -5.9102,
  });
  const [buoyType, setBuoyType] = useState<'BUOY' | 'UTURN' | 'PUNTO DE RUTA'>('BUOY');

  // Settings form state
  const [weight, setWeight] = useState(settings.weightKg.toString());
  const [age, setAge] = useState(settings.ageYears.toString());
  const [height, setHeight] = useState(settings.heightCm.toString());
  const [sex, setSex] = useState(settings.biologicalSex || 'MALE');
  const [zoom, setZoom] = useState(settings.mapZoom);
  const [accuracy, setAccuracy] = useState(settings.gpsAccuracyLimitM);
  const [routeColorHex, setRouteColorHex] = useState(settings.routeColorHex);
  const [speedUnit, setSpeedUnit] = useState<SpeedUnit>(settings.speedUnit || 'kmh');
  const [autoLapEnabled, setAutoLapEnabled] = useState(settings.autoLapEnabled !== false);
  const [autoLapRadiusM, setAutoLapRadiusM] = useState(settings.autoLapRadiusM || 25);
  const [alertMode, setAlertMode] = useState<AlertMode>(settings.alertMode || 'VOICE_AND_VIBRATION');

  // --- "AÑADIR CIRCUITOS" WORKFLOW (Interactive Point-by-Point Confirmation & Purple Shortest Line) ---
  const [isAddingCircuit, setIsAddingCircuit] = useState(false);
  const [newCircuitName, setNewCircuitName] = useState('Memorial');
  const [newCircuitRadius, setNewCircuitRadius] = useState(25);
  const [confirmedWaypoints, setConfirmedWaypoints] = useState<CircuitWaypoint[]>([]);
  const [pendingPoint, setPendingPoint] = useState<{ latitude: number; longitude: number } | null>(null);
  const [isLocatingGps, setIsLocatingGps] = useState(false);

  // --- AI NATURAL LANGUAGE WORKOUT CREATOR ---
  const [isCreatingAiWorkout, setIsCreatingAiWorkout] = useState(false);
  const [aiWorkoutPrompt, setAiWorkoutPrompt] = useState(
    '20 minutos a paleo foto (tecnica, lento), 3 vueltas al circuito del memorial y una pausa indefinida hasta que llegue a 8km/h (y se contará a partir de ahí y 5 segundos antes) y entonces empezará la última serie la cual será hacer 100m, luego, el entreno finalizará'
  );
  const [isAiGenerating, setIsAiGenerating] = useState(false);
  const [isVoiceDictating, setIsVoiceDictating] = useState(false);
  const [generatedAiWorkout, setGeneratedAiWorkout] = useState<StructuredWorkout | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const routeColorOptions = [
    { label: 'Naranja', hex: 'FFFF8500', color: '#FF8500' },
    { label: 'Cian', hex: 'FF42D9FF', color: '#42D9FF' },
    { label: 'Rosa', hex: 'FFFF4FA3', color: '#FF4FA3' },
  ];

  const handleSync = () => {
    onTriggerSync();
    setStatusMessage('Sincronización completada con la capa de datos de Wear OS.');
  };

  const handleSaveBuoy = () => {
    onAddBuoy(pickedCoord.latitude, pickedCoord.longitude, buoyType);
    setStatusMessage(
      `Punto guardado y enviado: ${pickedCoord.latitude.toFixed(6)}, ${pickedCoord.longitude.toFixed(6)}`
    );
  };

  const handleCircuitFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (event) => {
        const text = event.target?.result as string;
        if (text) {
          onImportCircuitGpx(text, file.name.replace(/\.gpx$/i, ''));
          setStatusMessage(
            `Circuito "${file.name}" enviado al reloj. Se actualizará en el trazado.`
          );
        }
      };
      reader.readAsText(file);
    }
  };

  const handleSaveSettings = () => {
    const w = parseFloat(weight);
    const a = parseInt(age, 10);
    const h = parseInt(height, 10);
    if (!isNaN(w) && !isNaN(a) && !isNaN(h)) {
      onUpdateSettings({
        weightKg: w,
        ageYears: a,
        heightCm: h,
        biologicalSex: sex,
        mapZoom: zoom,
        gpsAccuracyLimitM: accuracy,
        routeColorHex,
        speedUnit,
        autoLapEnabled,
        autoLapRadiusM,
        alertMode,
      });
      setStatusMessage('Ajustes guardados y transmitidos al reloj Wear OS.');
    } else {
      alert('Por favor verifica los números introducidos');
    }
  };

  // --- AÑADIR CIRCUITOS ACTIONS ---
  const handleMapPickForCircuit = (coord: { latitude: number; longitude: number }) => {
    setPendingPoint(coord);
  };

  const handleAddCurrentGpsPoint = () => {
    if (!navigator.geolocation) {
      setStatusMessage('Geolocalización no disponible en este dispositivo.');
      return;
    }
    setIsLocatingGps(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        setIsLocatingGps(false);
        const coord = { latitude: pos.coords.latitude, longitude: pos.coords.longitude };
        setPendingPoint(coord);
        setStatusMessage(`GPS capturado: ±${Math.round(pos.coords.accuracy || 5)}m de precisión.`);
      },
      (err) => {
        setIsLocatingGps(false);
        setStatusMessage(`Error GPS: ${err.message}. Toca en el mapa para marcar punto.`);
      },
      { enableHighAccuracy: true, timeout: 8000 }
    );
  };

  const handleConfirmCurrentPoint = () => {
    if (!pendingPoint) return;
    const newOrder = confirmedWaypoints.length + 1;
    const newWp: CircuitWaypoint = {
      id: `wp-${Date.now()}-${newOrder}`,
      order: newOrder,
      latitude: pendingPoint.latitude,
      longitude: pendingPoint.longitude,
      label: newOrder === 1 ? 'Meta / Salida (Boya 1)' : `Boya / Giro #${newOrder}`,
    };
    setConfirmedWaypoints((prev) => [...prev, newWp]);
    setPendingPoint(null);
    setStatusMessage(`Boya #${newOrder} confirmada en el circuito.`);
  };

  const handleRemoveLastWaypoint = () => {
    setConfirmedWaypoints((prev) => prev.slice(0, prev.length - 1));
  };

  const handleClearWaypoints = () => {
    setConfirmedWaypoints([]);
    setPendingPoint(null);
  };

  // Calculate purple shortest-path line from buoy to buoy and loop back to start
  const calculatedPurpleRouteLine: Array<[number, number]> = React.useMemo(() => {
    if (confirmedWaypoints.length < 2) return [];
    const line: Array<[number, number]> = confirmedWaypoints.map((w) => [w.latitude, w.longitude]);
    // Close the circuit back to buoy 1
    line.push([confirmedWaypoints[0].latitude, confirmedWaypoints[0].longitude]);
    return line;
  }, [confirmedWaypoints]);

  // Calculate estimated total circuit perimeter distance in meters
  const estimatedCircuitDistanceM: number = React.useMemo(() => {
    if (calculatedPurpleRouteLine.length < 2) return 0;
    let dist = 0;
    for (let i = 0; i < calculatedPurpleRouteLine.length - 1; i++) {
      const p1 = calculatedPurpleRouteLine[i];
      const p2 = calculatedPurpleRouteLine[i + 1];
      dist += calculateDistance(
        { latitude: p1[0], longitude: p1[1] },
        { latitude: p2[0], longitude: p2[1] }
      );
    }
    return Math.round(dist);
  }, [calculatedPurpleRouteLine]);

  const handleFinalizeCreateCircuit = () => {
    if (!newCircuitName.trim()) {
      alert('Por favor escribe un nombre para el circuito');
      return;
    }
    if (confirmedWaypoints.length < 2) {
      alert('Debes confirmar al menos 2 boyas/puntos para formar un circuito');
      return;
    }

    const createdCircuit: CircuitDefinition = {
      id: `circuit-${Date.now()}`,
      name: newCircuitName.trim(),
      startPoint: {
        latitude: confirmedWaypoints[0].latitude,
        longitude: confirmedWaypoints[0].longitude,
      },
      radiusM: newCircuitRadius,
      lapDistanceEstimatedM: estimatedCircuitDistanceM,
      waypoints: confirmedWaypoints,
      routeLine: calculatedPurpleRouteLine,
      colorHex: '#A855F7',
      isDeletable: true,
      createdAt: Date.now(),
    };

    onSaveCircuitDefinition?.(createdCircuit);
    onUpdateSettings({ activeCircuitId: createdCircuit.id });
    setIsAddingCircuit(false);
    setConfirmedWaypoints([]);
    setPendingPoint(null);
    setStatusMessage(`Circuito "${createdCircuit.name}" creado con éxito. Línea morada activa en el reloj y AOD.`);
  };

  const handleClearAllCircuits = () => {
    if (window.confirm('¿Seguro que deseas borrar TODOS los circuitos guardados? Empezarás de cero.')) {
      StorageService.clearAllCircuits();
      onTriggerSync();
      setStatusMessage('Todos los circuitos han sido borrados.');
    }
  };

  const handleResetDefaults = () => {
    StorageService.resetDefaultCircuits();
    onTriggerSync();
    setStatusMessage('Circuitos predeterminados restablecidos (Memorial y Trasona).');
  };

  // --- AI NATURAL LANGUAGE WORKOUT CREATOR ACTIONS ---
  const handleGenerateAiWorkout = async () => {
    if (!aiWorkoutPrompt.trim()) return;
    setIsAiGenerating(true);
    setStatusMessage('Analizando descripción del entrenamiento con IA...');

    try {
      const res = await fetch('/api/gemini/parse-workout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          promptText: aiWorkoutPrompt,
          availableCircuits: circuits.map((c) => ({ id: c.id, name: c.name })),
        }),
      });

      if (res.ok) {
        const data = await res.json();
        if (data.workout) {
          setGeneratedAiWorkout(data.workout);
          setStatusMessage(`Entrenamiento compilado exitosamente (${data.source}).`);
        } else {
          fallbackOfflineParse();
        }
      } else {
        fallbackOfflineParse();
      }
    } catch (_) {
      fallbackOfflineParse();
    } finally {
      setIsAiGenerating(false);
    }
  };

  const fallbackOfflineParse = () => {
    const offlineParsed = parseWorkoutOffline(
      aiWorkoutPrompt,
      circuits.map((c) => ({ id: c.id, name: c.name }))
    );
    setGeneratedAiWorkout(offlineParsed);
    setStatusMessage('Entrenamiento generado con el motor offline de alta precisión.');
  };

  const handleSaveAndActivateAiWorkout = () => {
    if (!generatedAiWorkout) return;
    onSaveStructuredWorkout?.(generatedAiWorkout);
    onSelectActiveWorkout?.(generatedAiWorkout.id);
    setGeneratedAiWorkout(null);
    setIsCreatingAiWorkout(false);
    setStatusMessage(`Entrenamiento "${generatedAiWorkout.name}" activado y enviado al reloj.`);
  };

  const handleStartVoiceDictation = () => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) {
      alert('Reconocimiento por voz no soportado en este navegador');
      return;
    }
    const recognition = new SpeechRecognition();
    recognition.lang = 'es-ES';
    recognition.interimResults = true;

    recognition.onstart = () => {
      setIsVoiceDictating(true);
      setStatusMessage('Dicta tu entrenamiento (ej: 20m paleo foto, 3 vueltas memorial, pausa hasta 8 km/h y 100m sprint)...');
    };

    recognition.onresult = (event: any) => {
      let text = '';
      for (let i = 0; i < event.results.length; i++) {
        text += event.results[i][0].transcript;
      }
      setAiWorkoutPrompt(text);
    };

    recognition.onerror = () => {
      setIsVoiceDictating(false);
    };

    recognition.onend = () => {
      setIsVoiceDictating(false);
    };

    recognition.start();
  };

  const formatSec = (ms: number): string => {
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return `${m}:${sec.toString().padStart(2, '0')}`;
  };

  return (
    <div className="w-full max-w-4xl mx-auto bg-[#07111C] border border-[#142333] rounded-3xl overflow-hidden shadow-2xl flex flex-col min-h-[660px]">
      {/* Top App Bar */}
      <div className="bg-[#0c1926] p-4 sm:p-5 border-b border-[#142333] flex items-center justify-between">
        <div>
          <div className="text-white font-extrabold text-base tracking-widest flex items-center gap-2">
            <span>OPENKAYAK</span>
            <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-[#142333] text-[#70E5C5] border border-[#70E5C5]/30">
              Wear OS 5 & Móvil
            </span>
          </div>
          <div className="text-[#A5B7C5] text-[10px] tracking-wider font-semibold uppercase">
            CENTRO DE CONTROL PIRAGÜISMO · VUELTAS & COACH IA
          </div>
        </div>

        <button
          onClick={handleSync}
          className="px-4 py-2 bg-[#142333] hover:bg-[#1f354c] text-[#70E5C5] font-black text-xs rounded-xl border border-[#70E5C5]/40 transition-all flex items-center gap-1.5 active:scale-95 cursor-pointer shadow-sm"
        >
          <span>↻</span>
          <span>SINCRONIZAR</span>
        </button>
      </div>

      {/* Status Bar */}
      <div className="bg-[#101e2e] px-4 py-2 text-xs text-[#A5B7C5] border-b border-[#142333] flex items-center justify-between">
        <div className="flex items-center gap-2 truncate">
          <span className="w-2 h-2 rounded-full bg-[#70E5C5] animate-pulse" />
          <span className="truncate">{statusMessage}</span>
        </div>
        <span className="text-[10px] text-[#70C5F0] font-mono hidden sm:inline">
          Data Layer Wear OS: Conectado
        </span>
      </div>

      {/* Main Body Content based on active tab */}
      <div className="flex-1 p-4 sm:p-6 overflow-y-auto">
        {tab === 0 && (
          /* TAB 0: HISTORIAL DE ENTRENAMIENTOS */
          <div className="space-y-4">
            <div className="flex items-center justify-between">
              <h3 className="text-white font-bold text-sm tracking-wide">
                HISTORIAL DE ENTRENAMIENTOS ({workouts.length})
              </h3>
              <button
                onClick={handleSync}
                className="text-xs text-[#70E5C5] hover:underline"
              >
                Solicitar historial completo
              </button>
            </div>

            {workouts.length === 0 ? (
              <div className="bg-[#142333] rounded-2xl p-8 text-center text-[#A5B7C5] text-xs">
                No hay entrenamientos sincronizados. Termina una sesión en el reloj para verla aquí.
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {workouts.map((w) => {
                  const bestLap = w.laps?.find((l) => l.isBestLap);
                  return (
                    <div
                      key={w.startAt}
                      className="bg-[#142333] border border-[#203144] rounded-2xl p-4 hover:border-[#70E5C5]/50 transition-all text-left flex flex-col justify-between"
                    >
                      <div>
                        <div className="flex justify-between items-start mb-2">
                          <div>
                            <div className="text-white font-black text-lg">
                              {(w.distanceM / 1000).toFixed(2)} km
                            </div>
                            <div className="text-[#A5B7C5] text-[11px]">
                              {new Date(w.startAt).toLocaleString('es-ES', {
                                dateStyle: 'medium',
                                timeStyle: 'short',
                              })}
                            </div>
                          </div>
                          <span className="px-2 py-0.5 rounded-full bg-[#07111C] text-[#70C5F0] font-mono text-xs font-bold border border-[#203144]">
                            {formatSec(w.elapsedMs)}
                          </span>
                        </div>

                        <div className="grid grid-cols-3 gap-2 bg-[#0a1827] rounded-xl p-2 mb-2 text-center text-[10px]">
                          <div>
                            <span className="text-[#A5B7C5] block">Paladas</span>
                            <span className="text-white font-bold">{w.strokes}</span>
                          </div>
                          <div>
                            <span className="text-[#A5B7C5] block">FC Med.</span>
                            <span className="text-[#FF829B] font-bold">
                              {w.averageBpm > 0 ? `${w.averageBpm} lpm` : '--'}
                            </span>
                          </div>
                          <div>
                            <span className="text-[#A5B7C5] block">Calorías</span>
                            <span className="text-[#70E5C5] font-bold">
                              {w.calories} kcal
                            </span>
                          </div>
                        </div>

                        {/* Laps mini badge if present */}
                        {w.laps && w.laps.length > 0 && (
                          <div className="bg-[#0b141e] border border-amber-500/30 rounded-xl p-2 mb-2 text-[10px] flex items-center justify-between text-[#FFCA7A]">
                            <span className="font-bold flex items-center gap-1">
                              <span>🏁</span>
                              <span>{w.laps.length} vueltas completadas</span>
                            </span>
                            {bestLap && (
                              <span className="font-mono text-amber-300 font-extrabold">
                                Mejor: {Math.round(bestLap.durationMs / 1000)}s ({bestLap.avgSpeedKmh} km/h)
                              </span>
                            )}
                          </div>
                        )}
                      </div>

                      <div className="flex gap-2 pt-2 border-t border-[#203144]">
                        <button
                          onClick={() => setDetailWorkout(w)}
                          className="flex-1 py-1.5 bg-gradient-to-r from-[#70E5C5] to-[#70C5F0] hover:from-[#5cd4b4] hover:to-[#5bb3db] text-[#07111C] font-black text-xs rounded-xl shadow-sm transition-all flex items-center justify-center gap-1 cursor-pointer"
                        >
                          <span>✨</span>
                          <span>Análisis IA Coach & Métricas</span>
                        </button>
                        <button
                          onClick={() => onDeleteWorkout(w.startAt)}
                          className="px-3 py-1.5 bg-[#3d181e] hover:bg-[#4f2028] text-[#FF829B] font-bold text-xs rounded-xl border border-[#FF829B]/30 cursor-pointer"
                        >
                          Borrar
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        )}

        {tab === 1 && (
          /* TAB 1: CHATBOT COACH IA (GEMINI GOOGLE AI STUDIO) */
          <div>
            <PhoneCoachChatbot
              workouts={workouts}
              athleteProfile={{
                weightKg: parseFloat(weight) || 72.5,
                ageYears: parseInt(age, 10) || 28,
                heightCm: parseInt(height, 10) || 180,
                biologicalSex: sex as any,
              }}
            />
          </div>
        )}

        {tab === 2 && (
          /* TAB 2: SERIES Y VUELTAS AUTOMÁTICAS (CIRCUITOS & INTERVALOS) */
          <div className="space-y-6">
            {/* SECTION 1: DETECCIÓN AUTOMÁTICA DE VUELTAS Y "AÑADIR CIRCUITOS" */}
            <div className="bg-[#0f1d2c] border border-[#203244] rounded-2xl p-4 sm:p-5 space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                <div>
                  <h4 className="text-white font-extrabold text-sm flex items-center gap-1.5">
                    <span className="text-[#A855F7]">📍</span>
                    <span>DETECCIÓN AUTOMÁTICA DE VUELTAS (CIRCUITOS)</span>
                  </h4>
                  <p className="text-[#8D9DA8] text-xs">
                    Define circuitos con línea morada trazada de boya a boya. El reloj Wear OS detecta el paso por meta por GPS y avisa por vibración.
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={() => {
                      setIsAddingCircuit(!isAddingCircuit);
                      setConfirmedWaypoints([]);
                      setPendingPoint(null);
                    }}
                    className={`px-3.5 py-1.5 font-bold text-xs rounded-xl border transition-all cursor-pointer ${
                      isAddingCircuit
                        ? 'bg-[#3b123f] border-[#A855F7] text-[#C084FC]'
                        : 'bg-gradient-to-r from-[#A855F7] to-[#70C5F0] text-black shadow-md font-black'
                    }`}
                  >
                    {isAddingCircuit ? 'Cerrar Creador' : '➕ Añadir Circuitos'}
                  </button>
                </div>
              </div>

              {/* DEDICATED INTERACTIVE "AÑADIR CIRCUITOS" CREATOR */}
              {isAddingCircuit && (
                <div className="bg-[#09131d] border-2 border-[#A855F7]/60 rounded-2xl p-4 space-y-4 animate-fade-in shadow-xl">
                  <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 border-b border-[#1b2b3a] pb-3">
                    <div>
                      <span className="text-xs font-black text-[#C084FC] uppercase tracking-wider flex items-center gap-1">
                        <span>🗺️</span>
                        <span>CREADOR DE CIRCUITOS (LÍNEA MORADA DE BOYA A BOYA)</span>
                      </span>
                      <p className="text-[11px] text-[#8D9DA8]">
                        Toca en el mapa para marcar cada boya o pulsa "Tomar GPS actual". Confirma cada punto y al final pulsa "Crear circuito".
                      </p>
                    </div>
                    <div className="flex gap-2">
                      <button
                        onClick={handleAddCurrentGpsPoint}
                        disabled={isLocatingGps}
                        className="px-2.5 py-1 bg-[#17283c] hover:bg-[#203752] text-[#70C5F0] border border-[#70C5F0]/30 rounded-xl text-xs font-bold transition-all cursor-pointer flex items-center gap-1"
                      >
                        <span>📍</span>
                        <span>{isLocatingGps ? 'Localizando GPS…' : 'Tomar GPS Actual'}</span>
                      </button>
                      {confirmedWaypoints.length > 0 && (
                        <button
                          onClick={handleRemoveLastWaypoint}
                          className="px-2 py-1 bg-[#261318] text-[#FF8FA3] border border-[#FF8FA3]/30 rounded-xl text-xs font-bold hover:bg-[#381a22] transition-all cursor-pointer"
                        >
                          ↩ Deshacer
                        </button>
                      )}
                    </div>
                  </div>

                  {/* Interactive Map for Pinning Waypoints */}
                  <div className="relative w-full h-[240px] sm:h-[280px] rounded-xl overflow-hidden border border-[#2a3e54]">
                    <MapView
                      circuitRouteLine={calculatedPurpleRouteLine}
                      circuitWaypoints={confirmedWaypoints}
                      circuitColor="#A855F7"
                      pickedCoordinate={pendingPoint}
                      onPickCoordinate={handleMapPickForCircuit}
                      followPosition={false}
                      zoom={15}
                      className="w-full h-full"
                    />
                    {/* Badge Overlay */}
                    <div className="absolute top-2 left-2 bg-[#0c1622]/90 border border-[#A855F7]/50 px-2.5 py-1 rounded-xl text-[10px] text-white font-mono z-20 backdrop-blur-sm">
                      Boyas confirmadas: <b className="text-[#C084FC]">{confirmedWaypoints.length}</b>
                      {estimatedCircuitDistanceM > 0 && (
                        <span> · Perímetro: <b className="text-[#76F4D0]">{estimatedCircuitDistanceM}m</b></span>
                      )}
                    </div>
                  </div>

                  {/* Point Confirmation Bar */}
                  <div className="bg-[#101e2c] border border-[#223548] rounded-xl p-3 flex flex-col sm:flex-row items-center justify-between gap-3">
                    <div className="text-xs text-left">
                      {pendingPoint ? (
                        <div>
                          <span className="text-[#C084FC] font-extrabold block">
                            📍 Punto seleccionado en mapa listo para confirmar:
                          </span>
                          <span className="font-mono text-white text-[11px]">
                            {pendingPoint.latitude.toFixed(6)}, {pendingPoint.longitude.toFixed(6)}
                          </span>
                        </div>
                      ) : (
                        <span className="text-[#8D9DA8] italic">
                          Toca en el mapa o pulsa "Tomar GPS Actual" para colocar la siguiente boya.
                        </span>
                      )}
                    </div>

                    <div className="flex gap-2 w-full sm:w-auto">
                      <button
                        onClick={handleConfirmCurrentPoint}
                        disabled={!pendingPoint}
                        className={`flex-1 sm:flex-none px-4 py-2 rounded-xl text-xs font-black transition-all cursor-pointer flex items-center justify-center gap-1.5 ${
                          pendingPoint
                            ? 'bg-[#A855F7] hover:bg-[#9333EA] text-white shadow-md shadow-[#A855F7]/30 active:scale-95'
                            : 'bg-[#1a2530] text-[#5e778f] cursor-not-allowed border border-white/5'
                        }`}
                      >
                        <span>✅</span>
                        <span>Confirmar punto #{confirmedWaypoints.length + 1}</span>
                      </button>
                    </div>
                  </div>

                  {/* Circuit Settings & Create Button */}
                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 items-end pt-1">
                    <div>
                      <label className="text-[#A5B7C5] text-[11px] block mb-1 font-bold">
                        Nombre del circuito
                      </label>
                      <input
                        type="text"
                        value={newCircuitName}
                        onChange={(e) => setNewCircuitName(e.target.value)}
                        placeholder="Ej: Memorial, Trasona 1000m, Embalse"
                        className="w-full bg-[#101e2c] border border-[#223548] rounded-xl px-3 py-2 text-xs text-white"
                      />
                    </div>

                    <div>
                      <label className="text-[#A5B7C5] text-[11px] block mb-1 font-bold">
                        Radio puerta de meta: ±{newCircuitRadius} m
                      </label>
                      <input
                        type="range"
                        min="15"
                        max="50"
                        step="5"
                        value={newCircuitRadius}
                        onChange={(e) => setNewCircuitRadius(Number(e.target.value))}
                        className="w-full accent-[#A855F7]"
                      />
                    </div>

                    <div>
                      <button
                        onClick={handleFinalizeCreateCircuit}
                        disabled={confirmedWaypoints.length < 2}
                        className={`w-full py-2.5 rounded-xl text-xs font-black transition-all cursor-pointer flex items-center justify-center gap-1.5 ${
                          confirmedWaypoints.length >= 2
                            ? 'bg-gradient-to-r from-[#A855F7] via-[#C084FC] to-[#70C5F0] text-black shadow-lg shadow-[#A855F7]/30 hover:opacity-90 active:scale-95'
                            : 'bg-[#18232e] text-[#5e778f] cursor-not-allowed'
                        }`}
                      >
                        <span>🏁</span>
                        <span>Crear Circuito ({confirmedWaypoints.length} boyas)</span>
                      </button>
                    </div>
                  </div>
                </div>
              )}

              {/* Circuits Management Header & Reset / Clear all */}
              <div className="flex items-center justify-between pt-1">
                <span className="text-xs font-bold text-[#8D9DA8]">
                  Circuitos guardados ({circuits.length})
                </span>
                <div className="flex items-center gap-2">
                  <button
                    onClick={handleClearAllCircuits}
                    className="text-[11px] text-[#FF8FA3] hover:underline cursor-pointer"
                  >
                    🗑️ Borrar todos
                  </button>
                  <span className="text-[#203144]">|</span>
                  <button
                    onClick={handleResetDefaults}
                    className="text-[11px] text-[#70C5F0] hover:underline cursor-pointer"
                  >
                    ↺ Restablecer predeterminados
                  </button>
                </div>
              </div>

              {/* Saved Circuits List */}
              {circuits.length === 0 ? (
                <div className="bg-[#09131d] border border-dashed border-[#203144] rounded-2xl p-6 text-center text-xs text-[#8D9DA8]">
                  No tienes circuitos guardados. Pulsa en <b className="text-[#C084FC]">"➕ Añadir Circuitos"</b> para trazar uno con tus boyas o pulsa <b className="text-[#70C5F0]">"Restablecer predeterminados"</b>.
                </div>
              ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {circuits.map((circ) => {
                    const isSelected = settings.activeCircuitId === circ.id;
                    return (
                      <div
                        key={circ.id}
                        className={`p-3.5 rounded-2xl border text-left flex flex-col justify-between transition-all ${
                          isSelected
                            ? 'bg-[#131f2d] border-[#A855F7] shadow-md shadow-[#A855F7]/10'
                            : 'bg-[#0a1622] border-[#1d2d3e] hover:border-[#2d4257]'
                        }`}
                      >
                        <div>
                          <div className="flex items-center justify-between mb-1.5">
                            <span className="font-extrabold text-white text-xs flex items-center gap-1.5">
                              <span className="text-[#A855F7]">🏁</span>
                              <span>{circ.name}</span>
                            </span>
                            {isSelected ? (
                              <span className="text-[9px] font-black px-2 py-0.5 rounded-full bg-[#A855F7] text-white">
                                ACTIVO EN RELOJ
                              </span>
                            ) : (
                              <button
                                onClick={() => {
                                  onUpdateSettings({ activeCircuitId: circ.id });
                                  setStatusMessage(`Circuito "${circ.name}" activado en el reloj.`);
                                }}
                                className="text-[10px] font-black text-[#76F4D0] hover:underline cursor-pointer"
                              >
                                Activar
                              </button>
                            )}
                          </div>

                          <div className="text-[10px] text-[#8D9DA8] space-y-0.5">
                            <div>Radio meta: ±{circ.radiusM} m · Perímetro est.: {circ.lapDistanceEstimatedM ? `${circ.lapDistanceEstimatedM}m` : 'Variable'}</div>
                            <div className="font-mono text-[#5e778f]">
                              Salida: {circ.startPoint.latitude.toFixed(5)}, {circ.startPoint.longitude.toFixed(5)}
                            </div>
                            {circ.waypoints && (
                              <div className="text-[9px] text-[#C084FC]">
                                {circ.waypoints.length} boyas conectadas por línea morada
                              </div>
                            )}
                          </div>
                        </div>

                        <div className="flex justify-between items-center mt-3 pt-2 border-t border-[#172636]">
                          <span className="text-[9px] text-[#5e778f]">
                            Vibración WearOS al paso por meta
                          </span>
                          <button
                            onClick={() => {
                              onDeleteCircuitDefinition?.(circ.id);
                              setStatusMessage(`Circuito "${circ.name}" eliminado.`);
                            }}
                            className="text-[#FF8FA3] text-xs font-bold hover:underline cursor-pointer"
                          >
                            Eliminar
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* SECTION 2: ENTRENAMIENTOS POR FASES GENERADOS POR IA */}
            <div className="bg-[#0f1d2c] border border-[#203244] rounded-2xl p-4 sm:p-5 space-y-4">
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2">
                <div>
                  <h4 className="text-white font-extrabold text-sm flex items-center gap-1.5">
                    <span>🤖</span>
                    <span>CREADOR DE ENTRENAMIENTOS POR IA (GEMINI & OFFLINE)</span>
                  </h4>
                  <p className="text-[#8D9DA8] text-xs">
                    Dicta o escribe en lenguaje natural (ej: 20 min paleo foto, 3 vueltas al circuito memorial, pausa hasta 8 km/h y serie 100m). La IA lo compilará automáticamente.
                  </p>
                </div>
                <button
                  onClick={() => setIsCreatingAiWorkout(!isCreatingAiWorkout)}
                  className="px-3.5 py-1.5 bg-[#142637] hover:bg-[#1e344a] text-[#76F4D0] font-bold text-xs rounded-xl border border-[#76F4D0]/30 transition-all cursor-pointer"
                >
                  {isCreatingAiWorkout ? 'Cerrar Generador' : '✨ Generar con IA'}
                </button>
              </div>

              {/* Natural Language Prompt Editor */}
              {isCreatingAiWorkout && (
                <div className="bg-[#08121c] border-2 border-[#76F4D0]/50 rounded-2xl p-4 space-y-3 animate-fade-in shadow-xl">
                  <div className="flex items-center justify-between">
                    <label className="text-xs font-black text-[#76F4D0] flex items-center gap-1">
                      <span>🎙️</span>
                      <span>Describe tu entrenamiento (por voz o texto):</span>
                    </label>
                    <button
                      onClick={handleStartVoiceDictation}
                      className={`px-3 py-1 rounded-xl text-xs font-bold transition-all cursor-pointer flex items-center gap-1 ${
                        isVoiceDictating
                          ? 'bg-[#FF8FA3] text-black animate-pulse font-black'
                          : 'bg-[#18293a] text-[#76F4D0] border border-[#76F4D0]/30'
                      }`}
                    >
                      <span>🎙️</span>
                      <span>{isVoiceDictating ? 'Escuchando dictado…' : 'Dictar por voz'}</span>
                    </button>
                  </div>

                  <textarea
                    rows={3}
                    value={aiWorkoutPrompt}
                    onChange={(e) => setAiWorkoutPrompt(e.target.value)}
                    placeholder="Ej: 20 minutos a paleo foto (tecnica, lento), 3 vueltas al circuito del memorial y una pausa indefinida hasta que llegue a 8km/h y entonces empezará la última serie la cual será hacer 100m, luego el entreno finalizará"
                    className="w-full bg-[#101e2c] border border-[#223548] rounded-xl p-3 text-xs text-white leading-relaxed focus:border-[#76F4D0] focus:outline-none"
                  />

                  {/* Quick Preset Buttons */}
                  <div className="flex flex-wrap gap-1.5 items-center">
                    <span className="text-[10px] text-[#8D9DA8]">Prueba rápida:</span>
                    <button
                      onClick={() =>
                        setAiWorkoutPrompt(
                          '20 minutos a paleo foto (tecnica, lento), 3 vueltas al circuito del memorial y una pausa indefinida hasta que llegue a 8km/h (y se contará a partir de ahí y 5 segundos antes) y entonces empezará la última serie la cual será hacer 100m, luego, el entreno finalizará'
                        )
                      }
                      className="px-2 py-0.5 bg-[#142333] hover:bg-[#1b2f44] text-[#9FD9FF] text-[10px] font-bold rounded-lg border border-[#9FD9FF]/20"
                    >
                      Series Paleo Foto + 3 Vueltas + Pausa 8km/h + 100m
                    </button>
                    <button
                      onClick={() =>
                        setAiWorkoutPrompt(
                          'Calentamiento de 15 minutos suave, 6 repeticiones de sprint de 30 segundos y 1 minuto de recuperación, y descalentamiento de 10 minutos'
                        )
                      }
                      className="px-2 py-0.5 bg-[#142333] hover:bg-[#1b2f44] text-[#9FD9FF] text-[10px] font-bold rounded-lg border border-[#9FD9FF]/20"
                    >
                      Fartlek 6x (30s / 1m)
                    </button>
                  </div>

                  <div className="flex gap-2 pt-1">
                    <button
                      onClick={handleGenerateAiWorkout}
                      disabled={isAiGenerating}
                      className="flex-1 py-2.5 bg-gradient-to-r from-[#76F4D0] to-[#70C5F0] text-black font-black text-xs rounded-xl shadow-md transition-all active:scale-95 cursor-pointer flex items-center justify-center gap-1.5"
                    >
                      <span>✨</span>
                      <span>{isAiGenerating ? 'Analizando con Gemini...' : 'Compilar Entrenamiento con IA'}</span>
                    </button>
                  </div>

                  {/* Generated Workout Preview Card */}
                  {generatedAiWorkout && (
                    <div className="bg-[#0e1a26] border border-[#76F4D0] rounded-xl p-3.5 space-y-2 mt-3 animate-fade-in text-left">
                      <div className="flex items-center justify-between">
                        <span className="font-extrabold text-white text-xs">
                          {generatedAiWorkout.name}
                        </span>
                        <span className="text-[10px] text-[#76F4D0] font-mono font-bold">
                          {generatedAiWorkout.phases.length} fases compiladas
                        </span>
                      </div>
                      <p className="text-[11px] text-[#8D9DA8]">
                        {generatedAiWorkout.description}
                      </p>

                      <div className="space-y-1.5 pt-1">
                        {generatedAiWorkout.phases.map((ph, idx) => (
                          <div
                            key={ph.id || idx}
                            className="bg-[#142333] p-2 rounded-xl border border-[#203244] flex items-center justify-between text-xs"
                          >
                            <div className="flex items-center gap-2">
                              <span className="w-5 h-5 rounded-full bg-[#1b2b3a] text-white flex items-center justify-center font-bold text-[10px]">
                                {idx + 1}
                              </span>
                              <div>
                                <span className="font-bold text-white block">{ph.name}</span>
                                <span className="text-[10px] text-[#8D9DA8]">{ph.notes || ph.type}</span>
                              </div>
                            </div>
                            <span className="px-2 py-0.5 rounded-full bg-[#101b26] text-[#76F4D0] text-[10px] font-mono font-bold border border-[#76F4D0]/30">
                              {ph.triggerType === 'SPEED_TRIGGER'
                                ? `Disparador > ${ph.triggerSpeedKmh} km/h`
                                : ph.triggerType === 'LAPS'
                                ? `${ph.targetLaps} vueltas ${ph.targetCircuitName || ''}`
                                : ph.triggerType === 'DISTANCE'
                                ? `${ph.targetDistanceM} metros`
                                : `${Math.floor((ph.durationSec || 60) / 60)}m ${(ph.durationSec || 60) % 60}s`}
                            </span>
                          </div>
                        ))}
                      </div>

                      <button
                        onClick={handleSaveAndActivateAiWorkout}
                        className="w-full py-2 bg-[#76F4D0] hover:bg-[#5de0bd] text-black font-black text-xs rounded-xl shadow-md transition-all active:scale-95 cursor-pointer mt-2"
                      >
                        💾 Guardar y Activar en Reloj Wear OS
                      </button>
                    </div>
                  )}
                </div>
              )}

              {/* Saved Structured Workouts List */}
              <div className="space-y-2 pt-1">
                <span className="text-xs font-bold text-[#8D9DA8] block">
                  Entrenamientos por fases disponibles ({structuredWorkouts.length})
                </span>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {structuredWorkouts.map((w) => {
                    const isSelected = activeWorkoutId === w.id;
                    return (
                      <div
                        key={w.id}
                        className={`p-3.5 rounded-2xl border text-left flex flex-col justify-between transition-all ${
                          isSelected
                            ? 'bg-[#132332] border-[#76F4D0] shadow-sm'
                            : 'bg-[#0a1622] border-[#1d2d3e]'
                        }`}
                      >
                        <div>
                          <div className="flex items-center justify-between mb-1">
                            <span className="font-extrabold text-white text-xs truncate">
                              {w.name}
                            </span>
                            {isSelected ? (
                              <span className="text-[9px] font-black px-2 py-0.5 rounded-full bg-[#76F4D0] text-black">
                                ACTIVO
                              </span>
                            ) : (
                              <button
                                onClick={() => {
                                  onSelectActiveWorkout?.(w.id);
                                  setStatusMessage(`Entrenamiento "${w.name}" activado en el reloj.`);
                                }}
                                className="text-[10px] font-bold text-[#76F4D0] hover:underline cursor-pointer"
                              >
                                Activar
                              </button>
                            )}
                          </div>
                          <p className="text-[10px] text-[#8D9DA8] line-clamp-2 mb-2">
                            {w.description}
                          </p>

                          {/* Phases pills */}
                          <div className="flex flex-wrap gap-1 mb-2">
                            {w.phases.slice(0, 4).map((p, idx) => (
                              <span
                                key={idx}
                                className="text-[8px] font-bold px-1.5 py-0.5 rounded-md bg-[#142333] text-[#A5B7C5] border border-white/5"
                              >
                                {p.name.slice(0, 20)}
                              </span>
                            ))}
                            {w.phases.length > 4 && (
                              <span className="text-[8px] text-[#5e778f]">
                                +{w.phases.length - 4} más
                              </span>
                            )}
                          </div>
                        </div>

                        <div className="flex justify-between items-center pt-2 border-t border-[#172636] text-[10px]">
                          <span className="text-[#5e778f] font-mono">
                            {w.phases.length} fases · {w.repeatCycles} ciclos
                          </span>
                          <button
                            onClick={() => onDeleteStructuredWorkout?.(w.id)}
                            className="text-[#FF8FA3] text-xs hover:underline cursor-pointer"
                          >
                            Eliminar
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </div>
        )}

        {tab === 3 && (
          /* TAB 3: MAPA Y BOYAS */
          <div className="space-y-4">
            <div className="bg-[#101e2e] rounded-2xl p-4 border border-[#1b2b3a] space-y-3">
              <div className="flex items-center justify-between">
                <span className="text-white text-xs font-black uppercase tracking-wider">
                  VISOR DE MAPA Y BOYAS EN CUBIERTA
                </span>
                <span className="text-[10px] text-[#70E5C5] font-mono">
                  {buoys.length} marcas registradas
                </span>
              </div>

              <div className="w-full h-[280px] rounded-xl overflow-hidden border border-[#203144]">
                <MapView
                  buoys={buoys}
                  currentLocation={pickedCoord}
                  pickedCoordinate={pickedCoord}
                  onPickCoordinate={(c) => setPickedCoord(c)}
                  className="w-full h-full"
                />
              </div>

              <div className="flex items-center justify-between gap-2 pt-1">
                <div className="text-xs text-white font-mono">
                  {pickedCoord.latitude.toFixed(5)}, {pickedCoord.longitude.toFixed(5)}
                </div>
                <div className="flex gap-2">
                  <select
                    value={buoyType}
                    onChange={(e: any) => setBuoyType(e.target.value)}
                    className="bg-[#142333] border border-[#203144] rounded-xl px-2 py-1 text-xs text-white"
                  >
                    <option value="BUOY">Boya Giro</option>
                    <option value="UTURN">Giro en U</option>
                    <option value="PUNTO DE RUTA">Punto de Ruta</option>
                  </select>
                  <button
                    onClick={handleSaveBuoy}
                    className="px-3 py-1 bg-[#70E5C5] text-black font-black text-xs rounded-xl"
                  >
                    Guardar Marca
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {tab === 4 && (
          /* TAB 4: AJUSTES */
          <div className="space-y-4 text-left">
            <h3 className="text-white font-bold text-sm tracking-wide">
              CONFIGURACIÓN DEL PALISTA Y RELOJ WEAR OS
            </h3>

            <div className="bg-[#101e2e] rounded-2xl p-4 sm:p-5 border border-[#1b2b3a] space-y-4">
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
                <div>
                  <label className="text-[#A5B7C5] text-xs block mb-1">Peso (kg)</label>
                  <input
                    type="number"
                    value={weight}
                    onChange={(e) => setWeight(e.target.value)}
                    className="w-full bg-[#07111C] border border-[#203144] rounded-xl p-2 text-white font-bold"
                  />
                </div>
                <div>
                  <label className="text-[#A5B7C5] text-xs block mb-1">Edad (años)</label>
                  <input
                    type="number"
                    value={age}
                    onChange={(e) => setAge(e.target.value)}
                    className="w-full bg-[#07111C] border border-[#203144] rounded-xl p-2 text-white font-bold"
                  />
                </div>
                <div>
                  <label className="text-[#A5B7C5] text-xs block mb-1">Altura (cm)</label>
                  <input
                    type="number"
                    value={height}
                    onChange={(e) => setHeight(e.target.value)}
                    className="w-full bg-[#07111C] border border-[#203144] rounded-xl p-2 text-white font-bold"
                  />
                </div>
                <div>
                  <label className="text-[#A5B7C5] text-xs block mb-1">Sexo Biológico</label>
                  <select
                    value={sex}
                    onChange={(e) => setSex(e.target.value)}
                    className="w-full bg-[#07111C] border border-[#203144] rounded-xl p-2 text-white font-bold text-xs"
                  >
                    <option value="MALE">Hombre</option>
                    <option value="FEMALE">Mujer</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="text-[#A5B7C5] text-xs block mb-2">
                  Modo de alertas para cambios de fase y vueltas
                </label>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                  {[
                    { mode: 'VOICE_AND_VIBRATION', label: 'Voz y Vibración (Recomendado)' },
                    { mode: 'VOICE_ONLY', label: 'Solo Voz' },
                    { mode: 'VIBRATION_ONLY', label: 'Solo Vibración (Silencioso)' },
                  ].map(({ mode, label }) => (
                    <button
                      key={mode}
                      type="button"
                      onClick={() => setAlertMode(mode as AlertMode)}
                      className={`p-2 rounded-xl text-xs font-bold border transition-all cursor-pointer ${
                        alertMode === mode
                          ? 'bg-[#70E5C5] text-[#07111C] border-[#70E5C5]'
                          : 'bg-[#07111C] text-[#A5B7C5] border-[#203144]'
                      }`}
                    >
                      {label}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="text-[#A5B7C5] text-xs block mb-2">
                  Unidad de velocidad preferida (Reloj Wear OS & Móvil)
                </label>
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                  {SPEED_UNITS.map((u) => (
                    <button
                      key={u.id}
                      type="button"
                      onClick={() => setSpeedUnit(u.id)}
                      className={`p-2.5 rounded-xl text-left border transition-all cursor-pointer ${
                        speedUnit === u.id
                          ? 'bg-[#70E5C5] text-[#07111C] border-[#70E5C5] shadow-sm font-black'
                          : 'bg-[#07111C] text-[#A5B7C5] border-[#203144] hover:text-white'
                      }`}
                    >
                      <div className="text-xs font-black">{u.symbol}</div>
                      <div className="text-[10px] opacity-80 leading-tight">{u.name}</div>
                    </button>
                  ))}
                </div>
              </div>

              <button
                onClick={handleSaveSettings}
                className="w-full py-3 bg-[#70E5C5] hover:bg-[#5cd4b4] text-[#07111C] font-black text-sm rounded-2xl shadow-lg transition-all active:scale-95 cursor-pointer"
              >
                GUARDAR Y ENVIAR AL RELOJ
              </button>
            </div>
          </div>
        )}

        {tab === 5 && (
          /* TAB 5: APKs PARA WEAR OS Y MÓVIL */
          <div className="space-y-4 text-left animate-fade-in">
            <div className="flex items-center justify-between">
              <div>
                <h3 className="text-white font-extrabold text-sm tracking-wide flex items-center gap-2">
                  <span>📦</span>
                  <span>PAQUETES APK PARA WEAR OS Y MÓVIL ANDROID</span>
                </h3>
                <p className="text-[#8D9DA8] text-xs">
                  Instaladores autónomos compilados para Xiaomi Watch 2 Pro (Wear OS 5) y tu smartphone.
                </p>
              </div>
              <span className="text-[10px] font-bold px-2.5 py-1 rounded-full bg-[#172738] text-[#76F4D0] border border-[#76F4D0]/30">
                v2.2.2 Release
              </span>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              {/* Wear OS Standalone APK Card */}
              <div className="bg-[#0e1a26] border-2 border-[#76F4D0]/60 rounded-2xl p-5 flex flex-col justify-between shadow-xl">
                <div>
                  <div className="flex items-center justify-between mb-3">
                    <span className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-[#76F4D0] to-[#00F0FF] text-black font-black flex items-center justify-center text-lg shadow-md">
                      ⌚
                    </span>
                    <span className="text-[10px] font-mono text-[#76F4D0] font-black px-2 py-0.5 rounded-full bg-[#132836]">
                      Wear OS 3/4/5 Standalone
                    </span>
                  </div>

                  <h4 className="text-white font-black text-base mb-1">
                    OpenKayak-WearOS.apk
                  </h4>
                  <p className="text-[11px] text-[#8D9DA8] leading-relaxed mb-4">
                    App autónoma para el reloj montado en la cubierta: GPS de alta frecuencia, vueltas automáticas con vibración háptica, trazado de línea morada en AOD, y escucha activa de voz continua («Hola reloj»).
                  </p>

                  <div className="bg-[#07111c] rounded-xl p-3 text-[10px] space-y-1 mb-4 border border-[#1b2b3a]">
                    <div className="text-white font-bold">✓ Características activas en APK:</div>
                    <div className="text-[#8D9DA8]">• Detección automática de paso por meta (±25m)</div>
                    <div className="text-[#8D9DA8]">• Pantalla AOD con línea morada de boya a boya</div>
                    <div className="text-[#8D9DA8]">• Micrófono siempre abierto para «Hola reloj»</div>
                    <div className="text-[#8D9DA8]">• Compatible con Xiaomi Watch 2 Pro, Galaxy Watch, Pixel Watch</div>
                  </div>
                </div>

                <a
                  href="/downloads/OpenKayak-WearOS.apk"
                  download="OpenKayak-WearOS.apk"
                  className="w-full py-3 bg-gradient-to-r from-[#76F4D0] to-[#00F0FF] hover:opacity-95 text-[#05090D] font-black text-xs rounded-xl shadow-lg shadow-[#76F4D0]/20 flex items-center justify-center gap-2 active:scale-95 transition-all text-center"
                >
                  <span>📥</span>
                  <span>DESCARGAR OPENKAYAK-WEAROS.APK</span>
                </a>
              </div>

              {/* Mobile Companion APK Card */}
              <div className="bg-[#0e1a26] border-2 border-[#70C5F0]/60 rounded-2xl p-5 flex flex-col justify-between shadow-xl">
                <div>
                  <div className="flex items-center justify-between mb-3">
                    <span className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-[#70C5F0] to-[#A855F7] text-white font-black flex items-center justify-center text-lg shadow-md">
                      📱
                    </span>
                    <span className="text-[10px] font-mono text-[#70C5F0] font-black px-2 py-0.5 rounded-full bg-[#132836]">
                      Android Mobile Companion
                    </span>
                  </div>

                  <h4 className="text-white font-black text-base mb-1">
                    OpenKayak-Mobile.apk
                  </h4>
                  <p className="text-[11px] text-[#8D9DA8] leading-relaxed mb-4">
                    Centro de control móvil: Creador interactivo de circuitos con boyas punto a punto, compilador de entrenamientos por voz con IA Gemini, y Chatbot Coach con historial de los últimos 10 días.
                  </p>

                  <div className="bg-[#07111c] rounded-xl p-3 text-[10px] space-y-1 mb-4 border border-[#1b2b3a]">
                    <div className="text-white font-bold">✓ Características activas en APK:</div>
                    <div className="text-[#8D9DA8]">• Menú interactivo "Añadir circuitos" con GPS</div>
                    <div className="text-[#8D9DA8]">• Chatbot Coach IA con análisis de cadencia y récords</div>
                    <div className="text-[#8D9DA8]">• Generador de entrenamientos por intervalos con IA</div>
                    <div className="text-[#8D9DA8]">• Sincronización bidireccional con Wear OS</div>
                  </div>
                </div>

                <a
                  href="/downloads/OpenKayak-Mobile.apk"
                  download="OpenKayak-Mobile.apk"
                  className="w-full py-3 bg-gradient-to-r from-[#70C5F0] to-[#A855F7] hover:opacity-95 text-white font-black text-xs rounded-xl shadow-lg shadow-[#70C5F0]/20 flex items-center justify-center gap-2 active:scale-95 transition-all text-center"
                >
                  <span>📥</span>
                  <span>DESCARGAR OPENKAYAK-MOBILE.APK</span>
                </a>
              </div>
            </div>

            {/* Wear OS Installation & ADB Guide */}
            <div className="bg-[#0a1622] border border-[#1b2b3a] rounded-2xl p-4 sm:p-5 space-y-2.5">
              <h4 className="text-white font-black text-xs flex items-center gap-2 uppercase tracking-wider">
                <span>⚡</span>
                <span>CÓMO INSTALAR OPENKAYAK DIRECTAMENTE EN TU RELOJ WEAR OS</span>
              </h4>

              <div className="text-[11px] text-[#A5B7C5] space-y-2 leading-relaxed">
                <div className="flex items-start gap-2">
                  <span className="font-bold text-[#76F4D0]">1.</span>
                  <span>En tu Xiaomi Watch 2 Pro o reloj Wear OS, ve a <b>Ajustes &gt; Sistema &gt; Información</b> y pulsa 7 veces seguidas en <b>Número de compilación</b> para activar el modo desarrollador.</span>
                </div>
                <div className="flex items-start gap-2">
                  <span className="font-bold text-[#76F4D0]">2.</span>
                  <span>En <b>Ajustes &gt; Opciones para desarrolladores</b>, activa <b>Depuración ADB</b> y <b>Depuración por Wi-Fi</b> (anota la IP y puerto que muestra el reloj).</span>
                </div>
                <div className="flex items-start gap-2">
                  <span className="font-bold text-[#76F4D0]">3.</span>
                  <span>Desde tu ordenador o desde apps gratuitas de móvil como <i>Wear Installer 2</i> o <i>Easy Fire Tools</i>, ejecuta:
                    <div className="bg-[#05090D] p-2 rounded-lg font-mono text-[#76F4D0] mt-1 text-[10px]">
                      adb connect &lt;IP_DE_TU_RELOJ&gt;:5555<br />
                      adb install -r OpenKayak-WearOS.apk
                    </div>
                  </span>
                </div>
                <div className="flex items-start gap-2">
                  <span className="font-bold text-[#76F4D0]">4.</span>
                  <span>¡Listo! La aplicación funcionará de forma 100% independiente en la cubierta de tu kayak, sin requerir conexión a internet mientras navegas.</span>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Bottom Nav Bar with 6 modern tabs */}
      <div className="bg-[#0c1926] border-t border-[#142333] flex items-center justify-around p-2">
        {[
          { label: 'Entrenos', icon: '⏱️' },
          { label: 'Coach IA', icon: '✨' },
          { label: 'Series & Vueltas', icon: '⚡' },
          { label: 'Mapa & Boyas', icon: '⌖' },
          { label: 'Ajustes', icon: '⚙' },
          { label: 'APKs WearOS', icon: '📦' },
        ].map((item, idx) => (
          <button
            key={item.label}
            onClick={() => setTab(idx)}
            className={`flex flex-col items-center py-1.5 px-2.5 sm:px-3 rounded-xl transition-all cursor-pointer ${
              tab === idx
                ? 'text-[#70E5C5] font-black bg-[#142333]'
                : 'text-[#A5B7C5] hover:text-white'
            }`}
          >
            <span className="text-sm sm:text-base leading-none mb-1">{item.icon}</span>
            <span className="text-[10px] sm:text-[11px] font-bold">{item.label}</span>
          </button>
        ))}
      </div>

      {/* Workout Detail Analytics Dialog */}
      {detailWorkout && (
        <WorkoutAnalyticsDialog
          workout={detailWorkout}
          weightKg={settings.weightKg}
          heightCm={settings.heightCm}
          ageYears={settings.ageYears}
          biologicalSex={settings.biologicalSex}
          speedUnit={settings.speedUnit}
          onDismiss={() => setDetailWorkout(null)}
          onDelete={(startAt) => {
            onDeleteWorkout(startAt);
            setDetailWorkout(null);
          }}
        />
      )}
    </div>
  );
};
