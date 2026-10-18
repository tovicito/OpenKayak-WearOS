import React, { useState, useRef } from 'react';
import {
  SessionSummary,
  SavedBuoy,
  AppSettings,
  GeoSample,
} from '../types/openkayak';
import { MapView } from './MapView';
import { WorkoutAnalyticsDialog } from './WorkoutAnalyticsDialog';
import { StorageService } from '../services/storage';
import { GpxExporter } from '../services/gpxExporter';

interface PhoneControlCenterProps {
  workouts: SessionSummary[];
  buoys: SavedBuoy[];
  currentCircuit: { points: GeoSample[]; label: string; gpxContent?: string } | null;
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
  settings,
  onUpdateSettings,
  onAddBuoy,
  onDeleteBuoy,
  onDeleteWorkout,
  onImportCircuitGpx,
  onDeleteCircuit,
  onTriggerSync,
}) => {
  const [tab, setTab] = useState(0); // 0: Entrenos, 1: Mapa y boyas, 2: Ajustes
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

  const fileInputRef = useRef<HTMLInputElement>(null);

  const routeColorOptions = [
    { label: 'Naranja', hex: 'FFFF8500', color: '#FF8500' },
    { label: 'Cian', hex: 'FF42D9FF', color: '#42D9FF' },
    { label: 'Rosa', hex: 'FFFF4FA3', color: '#FF4FA3' },
  ];

  const handleSync = () => {
    setStatusMessage('Buscando el reloj y solicitando todos los entrenamientos guardados…');
    onTriggerSync();
    setTimeout(() => {
      setStatusMessage('Sincronización completada con éxito.');
    }, 700);
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

  const handleOpenGoogleMaps = () => {
    const url = `https://www.google.com/maps/search/?api=1&query=${pickedCoord.latitude},${pickedCoord.longitude}`;
    window.open(url, '_blank');
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
      });
      setStatusMessage('Ajustes guardados y transmitidos al reloj Wear OS.');
    } else {
      alert('Por favor verifica los números introducidos');
    }
  };

  const formatSec = (ms: number): string => {
    const s = Math.floor(ms / 1000);
    const m = Math.floor(s / 60);
    const sec = s % 60;
    return `${m}:${sec.toString().padStart(2, '0')}`;
  };

  return (
    <div className="w-full max-w-4xl mx-auto bg-[#07111C] border border-[#142333] rounded-3xl overflow-hidden shadow-2xl flex flex-col min-h-[640px]">
      {/* Top App Bar */}
      <div className="bg-[#0c1926] p-4 sm:p-5 border-b border-[#142333] flex items-center justify-between">
        <div>
          <div className="text-white font-extrabold text-base tracking-widest flex items-center gap-2">
            <span>OPENKAYAK</span>
            <span className="text-[10px] font-bold px-2 py-0.5 rounded bg-[#142333] text-[#70E5C5] border border-[#70E5C5]/30">
              V2.2.1
            </span>
          </div>
          <div className="text-[#A5B7C5] text-[10px] tracking-wider font-semibold uppercase">
            CENTRO DE CONTROL PIRAGÜISMO
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
          Wear Data Layer: OK
        </span>
      </div>

      {/* Main Body */}
      <div className="flex-1 p-4 sm:p-6 overflow-y-auto">
        {tab === 0 && (
          /* TAB 0: WORKOUTS */
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
                No hay entrenamientos sincronizados. Termina una sesión en el reloj para verla
                aquí.
              </div>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {workouts.map((w) => (
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

                      <div className="grid grid-cols-3 gap-2 bg-[#0a1827] rounded-xl p-2 mb-3 text-center text-[10px]">
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
                    </div>

                    <div className="flex gap-2 pt-2 border-t border-[#203144]">
                      <button
                        onClick={() => setDetailWorkout(w)}
                        className="flex-1 py-1.5 bg-[#70E5C5] hover:bg-[#5cd4b4] text-[#07111C] font-bold text-xs rounded-xl shadow-sm transition-all"
                      >
                        Ver análisis
                      </button>
                      <button
                        onClick={() => {
                          setSelectedWorkout(w);
                          setTab(1);
                        }}
                        className="px-3 py-1.5 bg-[#07111C] hover:bg-[#1a2c3f] text-[#70C5F0] font-bold text-xs rounded-xl border border-[#203144] transition-all"
                      >
                        Ver en mapa
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {tab === 1 && (
          /* TAB 1: MAP & BUOYS */
          <div className="space-y-4">
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
              {/* Map Left (2 cols) */}
              <div className="lg:col-span-2 space-y-2">
                <div className="flex justify-between items-center text-xs">
                  <span className="text-white font-bold">
                    Haz clic en el agua para ubicar una boya
                  </span>
                  <button
                    onClick={handleOpenGoogleMaps}
                    className="text-[#70C5F0] hover:underline flex items-center gap-1 font-semibold"
                  >
                    <span>Abrir en Google Maps ↗</span>
                  </button>
                </div>

                <div className="w-full h-[320px] sm:h-[400px] rounded-2xl overflow-hidden border border-[#203144] bg-[#0c1926]">
                  <MapView
                    points={
                      selectedWorkout?.gpxContent
                        ? GpxExporter.parseGpx(selectedWorkout.gpxContent)
                        : currentCircuit?.points || []
                    }
                    buoys={buoys}
                    pickedCoordinate={pickedCoord}
                    onPickCoordinate={(coords) => setPickedCoord(coords)}
                    routeColor={`#${routeColorHex.replace(/^FF/, '')}`}
                    className="w-full h-full"
                  />
                </div>

                <div className="text-[11px] text-[#A5B7C5] flex items-center justify-between">
                  <span>
                    Coordenadas seleccionadas:{' '}
                    <b className="text-white font-mono">
                      {pickedCoord.latitude.toFixed(6)}, {pickedCoord.longitude.toFixed(6)}
                    </b>
                  </span>
                  {selectedWorkout && (
                    <button
                      onClick={() => setSelectedWorkout(null)}
                      className="text-[#FF829B] hover:underline"
                    >
                      Quitar filtro de entrenamiento
                    </button>
                  )}
                </div>
              </div>

              {/* Controls Right (1 col) */}
              <div className="space-y-4">
                {/* Buoy Dropper */}
                <div className="bg-[#142333] border border-[#203144] rounded-2xl p-4 space-y-3">
                  <h4 className="text-white font-bold text-xs uppercase tracking-wider">
                    NUEVA BOYA / PUNTO
                  </h4>
                  <div className="flex gap-1">
                    {(['BUOY', 'UTURN', 'PUNTO DE RUTA'] as const).map((type) => (
                      <button
                        key={type}
                        onClick={() => setBuoyType(type)}
                        className={`flex-1 py-1 text-[10px] font-bold rounded-lg border transition-all ${
                          buoyType === type
                            ? 'bg-[#70E5C5] text-[#07111C] border-[#70E5C5]'
                            : 'bg-[#07111C] text-[#A5B7C5] border-[#203144]'
                        }`}
                      >
                        {type === 'BUOY'
                          ? 'Boya'
                          : type === 'UTURN'
                          ? 'Giro U'
                          : 'Punto'}
                      </button>
                    ))}
                  </div>

                  <button
                    onClick={handleSaveBuoy}
                    className="w-full py-2 bg-[#70E5C5] hover:bg-[#5cd4b4] text-[#07111C] font-black text-xs rounded-xl shadow-md transition-all active:scale-95"
                  >
                    GUARDAR Y ENVIAR AL RELOJ
                  </button>
                </div>

                {/* Circuit GPX Controls */}
                <div className="bg-[#142333] border border-[#203144] rounded-2xl p-4 space-y-2">
                  <h4 className="text-white font-bold text-xs uppercase tracking-wider">
                    CIRCUITO DE ENTRENAMIENTO
                  </h4>
                  <p className="text-[#A5B7C5] text-[11px] leading-relaxed">
                    {currentCircuit
                      ? `Circuito activo: ${currentCircuit.label} (${currentCircuit.points.length} puntos)`
                      : 'No hay circuito guardado en el reloj.'}
                  </p>

                  <div className="flex gap-2 pt-1">
                    <button
                      onClick={() => fileInputRef.current?.click()}
                      className="flex-1 py-1.5 bg-[#07111C] hover:bg-[#1a2c3f] text-[#70C5F0] font-bold text-xs rounded-xl border border-[#203144] transition-all"
                    >
                      Importar GPX
                    </button>
                    <input
                      type="file"
                      ref={fileInputRef}
                      onChange={handleCircuitFile}
                      accept=".gpx,.xml"
                      className="hidden"
                    />

                    {currentCircuit && (
                      <button
                        onClick={onDeleteCircuit}
                        className="py-1.5 px-3 bg-[#3d181e] hover:bg-[#4f2028] text-[#FF829B] font-bold text-xs rounded-xl border border-[#FF829B]/30"
                      >
                        Borrar
                      </button>
                    )}
                  </div>
                </div>

                {/* Saved Buoys List */}
                <div className="bg-[#142333] border border-[#203144] rounded-2xl p-4 space-y-2">
                  <div className="flex justify-between items-center">
                    <h4 className="text-white font-bold text-xs uppercase tracking-wider">
                      BOYAS ACTIVAS ({buoys.length})
                    </h4>
                  </div>
                  <div className="max-h-[160px] overflow-y-auto space-y-1.5">
                    {buoys.length === 0 ? (
                      <div className="text-[#A5B7C5] text-[11px] py-2">
                        Sin boyas guardadas.
                      </div>
                    ) : (
                      buoys.map((b) => (
                        <div
                          key={b.id}
                          className="flex items-center justify-between p-2 rounded-xl bg-[#07111C] text-xs border border-[#203144]"
                        >
                          <div>
                            <span
                              className={`font-black text-[10px] ${
                                b.turnType === 'UTURN'
                                  ? 'text-[#FFB54A]'
                                  : b.isTurn
                                  ? 'text-[#FF8FA3]'
                                  : 'text-[#70E5C5]'
                              }`}
                            >
                              {b.turnType === 'UTURN'
                                ? 'GIRO EN U'
                                : b.turnType === 'BUOY'
                                ? 'BOYA'
                                : 'PUNTO RUTA'}
                            </span>
                            <div className="text-[#A5B7C5] text-[9px] font-mono">
                              {b.latitude.toFixed(5)}, {b.longitude.toFixed(5)}
                            </div>
                          </div>
                          <button
                            onClick={() => onDeleteBuoy(b.id)}
                            className="text-[#FF829B] hover:text-red-400 font-bold px-2"
                          >
                            ×
                          </button>
                        </div>
                      ))
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}

        {tab === 2 && (
          /* TAB 2: SETTINGS */
          <div className="max-w-xl mx-auto space-y-5">
            <h3 className="text-white font-bold text-sm tracking-wide">
              AJUSTES Y PERFIL DEL DEPORTISTA
            </h3>

            {/* Profile Fields */}
            <div className="bg-[#142333] border border-[#203144] rounded-2xl p-5 space-y-4">
              <h4 className="text-[#70E5C5] text-xs font-bold uppercase tracking-wider">
                DATOS BIOMÉTRICOS (CÁLCULO DE CALORÍAS MET)
              </h4>

              <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
                <div>
                  <label className="text-[#A5B7C5] text-xs block mb-1">Peso (kg)</label>
                  <input
                    type="number"
                    value={weight}
                    onChange={(e) => setWeight(e.target.value)}
                    className="w-full bg-[#07111C] border border-[#203144] rounded-xl p-2.5 text-white font-bold text-sm focus:outline-none focus:border-[#70E5C5]"
                  />
                </div>
                <div>
                  <label className="text-[#A5B7C5] text-xs block mb-1">Edad</label>
                  <input
                    type="number"
                    value={age}
                    onChange={(e) => setAge(e.target.value)}
                    className="w-full bg-[#07111C] border border-[#203144] rounded-xl p-2.5 text-white font-bold text-sm focus:outline-none focus:border-[#70E5C5]"
                  />
                </div>
                <div className="col-span-2 sm:col-span-1">
                  <label className="text-[#A5B7C5] text-xs block mb-1">Altura (cm)</label>
                  <input
                    type="number"
                    value={height}
                    onChange={(e) => setHeight(e.target.value)}
                    className="w-full bg-[#07111C] border border-[#203144] rounded-xl p-2.5 text-white font-bold text-sm focus:outline-none focus:border-[#70E5C5]"
                  />
                </div>
              </div>

              <div>
                <label className="text-[#A5B7C5] text-xs block mb-1">Sexo biológico</label>
                <div className="flex gap-2">
                  {[
                    { id: 'MALE', label: 'Hombre' },
                    { id: 'FEMALE', label: 'Mujer' },
                    { id: 'OTHER', label: 'Sin especificar' },
                  ].map((item) => (
                    <button
                      key={item.id}
                      onClick={() => setSex(item.id)}
                      className={`flex-1 py-2 text-xs font-bold rounded-xl border transition-all ${
                        sex === item.id
                          ? 'bg-[#70E5C5] text-[#07111C] border-[#70E5C5]'
                          : 'bg-[#07111C] text-[#A5B7C5] border-[#203144]'
                      }`}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* GPS & Map Preferences */}
            <div className="bg-[#142333] border border-[#203144] rounded-2xl p-5 space-y-4">
              <h4 className="text-[#70C5F0] text-xs font-bold uppercase tracking-wider">
                MAPA Y FILTRO GPS WEAR OS
              </h4>

              <div>
                <div className="flex justify-between text-xs mb-1">
                  <span className="text-[#A5B7C5]">Límite de precisión GPS descartado</span>
                  <span className="text-white font-bold">±{accuracy} m</span>
                </div>
                <input
                  type="range"
                  min="10"
                  max="65"
                  step="5"
                  value={accuracy}
                  onChange={(e) => setAccuracy(parseFloat(e.target.value))}
                  className="w-full accent-[#70C5F0]"
                />
                <span className="text-[10px] text-[#A5B7C5]">
                  Muestras con error mayor a este valor se descartan para evitar saltos.
                </span>
              </div>

              <div>
                <div className="flex justify-between text-xs mb-1">
                  <span className="text-[#A5B7C5]">Zoom del mapa en el reloj</span>
                  <span className="text-white font-bold">Nivel {zoom}</span>
                </div>
                <div className="flex gap-2">
                  {[14, 15, 16, 17].map((z) => (
                    <button
                      key={z}
                      onClick={() => setZoom(z)}
                      className={`flex-1 py-1.5 text-xs font-bold rounded-xl border ${
                        zoom === z
                          ? 'bg-[#70C5F0] text-[#07111C] border-[#70C5F0]'
                          : 'bg-[#07111C] text-[#A5B7C5] border-[#203144]'
                      }`}
                    >
                      {z}
                    </button>
                  ))}
                </div>
              </div>

              <div>
                <label className="text-[#A5B7C5] text-xs block mb-2">
                  Color de trazado de ruta en el reloj
                </label>
                <div className="flex gap-3">
                  {routeColorOptions.map((opt) => (
                    <button
                      key={opt.hex}
                      onClick={() => setRouteColorHex(opt.hex)}
                      className={`flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-xl border transition-all text-xs font-bold ${
                        routeColorHex === opt.hex
                          ? 'border-white bg-[#07111C]'
                          : 'border-[#203144] bg-[#07111C] opacity-70 hover:opacity-100'
                      }`}
                    >
                      <span
                        className="w-3.5 h-3.5 rounded-full"
                        style={{ backgroundColor: opt.color }}
                      />
                      <span className="text-white">{opt.label}</span>
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <button
              onClick={handleSaveSettings}
              className="w-full py-3 bg-[#70E5C5] hover:bg-[#5cd4b4] text-[#07111C] font-black text-sm rounded-2xl shadow-lg transition-all active:scale-95"
            >
              GUARDAR Y ENVIAR AL RELOJ
            </button>
          </div>
        )}
      </div>

      {/* Bottom Nav Bar */}
      <div className="bg-[#0c1926] border-t border-[#142333] flex items-center justify-around p-2">
        {[
          { label: 'Entrenos', icon: '↻' },
          { label: 'Mapa y boyas', icon: '⌖' },
          { label: 'Ajustes', icon: '⚙' },
        ].map((item, idx) => (
          <button
            key={item.label}
            onClick={() => setTab(idx)}
            className={`flex flex-col items-center py-1.5 px-4 rounded-xl transition-all ${
              tab === idx
                ? 'text-[#70E5C5] font-black bg-[#142333]'
                : 'text-[#A5B7C5] hover:text-white'
            }`}
          >
            <span className="text-base leading-none mb-1">{item.icon}</span>
            <span className="text-[11px] font-bold">{item.label}</span>
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
