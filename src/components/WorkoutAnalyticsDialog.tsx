import React, { useState, useMemo, useRef, useEffect } from 'react';
import { SessionSummary, GeoSample, PulseSample } from '../types/openkayak';
import { RouteAnalyzer } from '../services/routeAnalyzer';
import { calculateDistance } from '../services/turnAiModel';

interface WorkoutAnalyticsDialogProps {
  workout: SessionSummary;
  weightKg: number;
  heightCm: number;
  ageYears: number;
  biologicalSex: string;
  onDismiss: () => void;
  onDelete: (startAt: number) => void;
}

interface TimedMetric {
  seconds: number;
  value: number;
}

interface TenSecondRow {
  seconds: number;
  heartRate: number | null;
  speedKmh: number | null;
}

export const WorkoutAnalyticsDialog: React.FC<WorkoutAnalyticsDialogProps> = ({
  workout,
  weightKg,
  heightCm,
  ageYears,
  biologicalSex,
  onDismiss,
  onDelete,
}) => {
  const [tab, setTab] = useState(0); // 0: Resumen, 1: FC, 2: Velocidad, 3: Tabla 10s
  const [confirmDelete, setConfirmDelete] = useState(false);

  const durationSeconds = Math.max(0, Math.floor(workout.elapsedMs / 1000));
  const durationMinutes = workout.elapsedMs / 60000;

  // Extract points from GPX or summary
  const routePoints = useMemo(() => {
    if (workout.gpxContent) {
      return RouteAnalyzer.parseGpxPoints(workout.gpxContent).map((t) => t.point);
    }
    return [];
  }, [workout.gpxContent]);

  // Speed series calculation from successive points
  const speedSeries = useMemo(() => {
    const out: TimedMetric[] = [];
    if (routePoints.length < 2) return out;

    for (let i = 0; i < routePoints.length - 1; i++) {
      const a = routePoints[i];
      const b = routePoints[i + 1];
      const timeA = a.timeMs || workout.startAt + i * 5000;
      const timeB = b.timeMs || workout.startAt + (i + 1) * 5000;
      const deltaSeconds = (timeB - timeA) / 1000;

      if (deltaSeconds > 0 && deltaSeconds <= 120) {
        const meters = calculateDistance(a, b);
        const kmh = (meters / deltaSeconds) * 3.6;
        if (Number.isFinite(kmh) && kmh >= 0 && kmh <= 30) {
          const relSeconds = Math.max(0, timeB - workout.startAt) / 1000;
          out.push({ seconds: relSeconds, value: kmh });
        }
      }
    }
    return out;
  }, [routePoints, workout.startAt]);

  // Heart series calculation
  const heartSeries = useMemo(() => {
    if (workout.heartRateSamples && workout.heartRateSamples.length > 0) {
      return workout.heartRateSamples
        .filter((s) => s.bpm >= 25 && s.bpm <= 240)
        .map((s) => ({
          seconds: Math.max(0, s.timeMs - workout.startAt) / 1000,
          value: s.bpm,
        }))
        .sort((a, b) => a.seconds - b.seconds);
    }

    // Try from route points
    const fromPoints = routePoints
      .filter((p) => p.heartRateBpm && p.heartRateBpm >= 25 && p.heartRateBpm <= 240)
      .map((p) => ({
        seconds: Math.max(0, (p.timeMs || workout.startAt) - workout.startAt) / 1000,
        value: p.heartRateBpm!,
      }))
      .sort((a, b) => a.seconds - b.seconds);

    return fromPoints;
  }, [workout, routePoints]);

  // Group by 10s intervals
  const speedByTenSec = useMemo(() => {
    const map = new Map<number, number[]>();
    speedSeries.forEach((s) => {
      const bucket = Math.floor(s.seconds / 10);
      if (!map.has(bucket)) map.set(bucket, []);
      map.get(bucket)!.push(s.value);
    });
    const avgMap = new Map<number, number>();
    map.forEach((vals, b) => {
      avgMap.set(b, vals.reduce((a, c) => a + c, 0) / vals.length);
    });
    return avgMap;
  }, [speedSeries]);

  const heartByTenSec = useMemo(() => {
    const map = new Map<number, number[]>();
    heartSeries.forEach((h) => {
      const bucket = Math.floor(h.seconds / 10);
      if (!map.has(bucket)) map.set(bucket, []);
      map.get(bucket)!.push(h.value);
    });
    const avgMap = new Map<number, number>();
    map.forEach((vals, b) => {
      avgMap.set(b, vals.reduce((a, c) => a + c, 0) / vals.length);
    });
    return avgMap;
  }, [heartSeries]);

  const tenSecondRows: TenSecondRow[] = useMemo(() => {
    const rows: TenSecondRow[] = [];
    const count = Math.floor(durationSeconds / 10);
    for (let i = 0; i <= count; i++) {
      rows.push({
        seconds: i * 10,
        heartRate: heartByTenSec.get(i) || null,
        speedKmh: speedByTenSec.get(i) || null,
      });
    }
    return rows;
  }, [durationSeconds, heartByTenSec, speedByTenSec]);

  const averageSpeed =
    durationMinutes > 0
      ? workout.distanceM / 1000 / (durationMinutes / 60)
      : 0;

  const peakSpeed =
    speedSeries.length > 0 ? Math.max(...speedSeries.map((s) => s.value)) : 0;

  const cadence =
    durationMinutes > 0 ? workout.strokes / durationMinutes : 0;

  const met = averageSpeed < 3.0 ? 3.5 : averageSpeed < 5.5 ? 5.5 : 7.0;
  const mobileKcalEstimate = Math.round(
    (met * 3.5 * Math.max(25, Math.min(250, weightKg))) /
      200.0 *
      durationMinutes
  );

  const formatSec = (seconds: number): string => {
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-3 sm:p-6 select-text">
      <div className="bg-[#07111C] border border-[#203144] rounded-3xl w-full max-w-2xl max-h-[90vh] flex flex-col overflow-hidden shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between p-4 sm:p-5 border-b border-[#142333]">
          <div>
            <div className="text-[#70E5C5] text-xs font-black tracking-widest uppercase">
              ANÁLISIS DEL ENTRENO
            </div>
            <div className="text-[#A5B7C5] text-xs">
              {new Date(workout.startAt).toLocaleString('es-ES', {
                dateStyle: 'medium',
                timeStyle: 'short',
              })}
            </div>
          </div>
          <button
            onClick={onDismiss}
            className="px-3 py-1 bg-[#142333] hover:bg-[#1f344a] text-white text-xs font-bold rounded-full transition-all"
          >
            Cerrar
          </button>
        </div>

        {/* 3 Summary Tiles */}
        <div className="grid grid-cols-3 gap-2 p-4 bg-[#0a1827]">
          <div className="bg-[#142333] rounded-2xl p-2.5">
            <span className="text-[#A5B7C5] text-[10px] font-bold block">DISTANCIA</span>
            <span className="text-[#70E5C5] text-lg font-black block">
              {(workout.distanceM / 1000).toFixed(2)} km
            </span>
          </div>
          <div className="bg-[#142333] rounded-2xl p-2.5">
            <span className="text-[#A5B7C5] text-[10px] font-bold block">TIEMPO</span>
            <span className="text-[#70C5F0] text-lg font-black block">
              {formatSec(durationSeconds)}
            </span>
          </div>
          <div className="bg-[#142333] rounded-2xl p-2.5">
            <span className="text-[#A5B7C5] text-[10px] font-bold block">RITMO</span>
            <span className="text-[#FF829B] text-lg font-black block">
              {averageSpeed.toFixed(1)} km/h
            </span>
          </div>
        </div>

        {/* Tab Filters */}
        <div className="flex border-b border-[#142333] px-4 bg-[#0a1827]">
          {['Resumen', 'FC', 'Velocidad', 'Tabla 10 s'].map((tLabel, idx) => (
            <button
              key={tLabel}
              onClick={() => setTab(idx)}
              className={`py-2 px-3 text-xs font-bold transition-all border-b-2 ${
                tab === idx
                  ? 'border-[#70E5C5] text-[#70E5C5]'
                  : 'border-transparent text-[#A5B7C5] hover:text-white'
              }`}
            >
              {tLabel}
            </button>
          ))}
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto p-4 sm:p-5">
          {tab === 0 && (
            /* TAB 0: RESUMEN */
            <div className="space-y-4">
              <div>
                <h5 className="text-[#70E5C5] text-xs font-bold tracking-wider uppercase mb-2">
                  ESFUERZO
                </h5>
                <div className="divide-y divide-[#1b2b3a] text-xs">
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Paladas registradas</span>
                    <span className="text-white font-bold">{workout.strokes}</span>
                  </div>
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Cadencia media calculada</span>
                    <span className="text-white font-bold">
                      {cadence.toFixed(1)} paladas/min
                    </span>
                  </div>
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Velocidad máxima registrada</span>
                    <span className="text-white font-bold">
                      {peakSpeed > 0
                        ? `${peakSpeed.toFixed(1)} km/h`
                        : 'Sin datos GPX suficientes'}
                    </span>
                  </div>
                </div>
              </div>

              <div>
                <h5 className="text-[#70E5C5] text-xs font-bold tracking-wider uppercase mb-2">
                  FRECUENCIA CARDÍACA
                </h5>
                <div className="divide-y divide-[#1b2b3a] text-xs">
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Media</span>
                    <span className="text-[#FF829B] font-bold">
                      {workout.averageBpm > 0 ? `${workout.averageBpm} lpm` : 'Sin datos'}
                    </span>
                  </div>
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Máxima / mínima</span>
                    <span className="text-white font-bold">
                      {workout.maxBpm > 0 && workout.minBpm > 0
                        ? `${workout.maxBpm} / ${workout.minBpm} lpm`
                        : 'Sin datos'}
                    </span>
                  </div>
                </div>
                {heartSeries.length === 0 && (
                  <p className="text-[#A5B7C5] text-[11px] mt-2">
                    Este entrenamiento contiene el resumen de FC sin curva temporal de alta
                    frecuencia.
                  </p>
                )}
              </div>

              <div>
                <h5 className="text-[#70E5C5] text-xs font-bold tracking-wider uppercase mb-2">
                  CALORÍAS
                </h5>
                <div className="divide-y divide-[#1b2b3a] text-xs">
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Calorías guardadas por el reloj</span>
                    <span className="text-white font-bold">
                      {workout.calories > 0 ? `${workout.calories} kcal` : 'No disponibles'}
                    </span>
                  </div>
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Estimación alternativa móvil</span>
                    <span className="text-[#70C5F0] font-bold">
                      {mobileKcalEstimate} kcal
                    </span>
                  </div>
                </div>
                <p className="text-[#A5B7C5] text-[10px] leading-relaxed mt-2 bg-[#142333] p-2.5 rounded-xl">
                  La estimación alternativa usa peso ({weightKg} kg), duración y fórmula MET
                  para piragüismo según el ritmo medio.
                </p>
              </div>
            </div>
          )}

          {tab === 1 && (
            /* TAB 1: FC CHART */
            <div className="space-y-3">
              <div className="flex justify-between text-xs text-[#A5B7C5]">
                <span className="font-bold text-[#FF829B]">
                  FRECUENCIA CARDÍACA · LPM
                </span>
                <span>Muestras recibidas: {heartSeries.length}</span>
              </div>

              {heartSeries.length === 0 ? (
                <div className="py-12 text-center text-[#A5B7C5] text-xs">
                  No hay serie temporal de FC en los datos sincronizados. La media y máxima
                  sí constan en el resumen.
                </div>
              ) : (
                <MetricChartCanvas
                  points={heartSeries}
                  color="#FF829B"
                  unit="lpm"
                />
              )}
            </div>
          )}

          {tab === 2 && (
            /* TAB 2: SPEED CHART */
            <div className="space-y-3">
              <div className="flex justify-between text-xs text-[#A5B7C5]">
                <span className="font-bold text-[#70C5F0]">VELOCIDAD · KM/H</span>
                <span>Puntos calculados: {speedSeries.length}</span>
              </div>

              {speedSeries.length === 0 ? (
                <div className="py-12 text-center text-[#A5B7C5] text-xs">
                  El GPX no contiene suficientes puntos GPS para calcular la curva de
                  velocidad.
                </div>
              ) : (
                <MetricChartCanvas
                  points={speedSeries}
                  color="#70C5F0"
                  unit="km/h"
                />
              )}
            </div>
          )}

          {tab === 3 && (
            /* TAB 3: 10S TABLE */
            <div>
              <div className="text-xs font-bold text-[#70E5C5] mb-2">
                MUESTRAS AGRUPADAS CADA 10 SEGUNDOS
              </div>
              <div className="bg-[#142333] rounded-xl overflow-hidden border border-[#203144]">
                <div className="grid grid-cols-3 bg-[#0d1c2b] p-2 text-xs font-bold border-b border-[#203144]">
                  <span className="text-[#70E5C5]">Tiempo</span>
                  <span className="text-[#FF829B]">FC (lpm)</span>
                  <span className="text-[#70C5F0]">Vel. (km/h)</span>
                </div>
                <div className="max-h-[300px] overflow-y-auto divide-y divide-[#172738] text-xs">
                  {tenSecondRows.map((row) => (
                    <div
                      key={row.seconds}
                      className="grid grid-cols-3 p-2 hover:bg-[#1a2c3f]"
                    >
                      <span className="text-white font-mono">
                        {formatSec(row.seconds)}
                      </span>
                      <span className="text-[#FF829B] font-mono">
                        {row.heartRate !== null ? row.heartRate.toFixed(0) : '—'}
                      </span>
                      <span className="text-[#70C5F0] font-mono">
                        {row.speedKmh !== null ? row.speedKmh.toFixed(2) : '—'}
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Footer Actions */}
        <div className="p-4 bg-[#0a1827] border-t border-[#142333] flex items-center justify-between">
          <button
            onClick={() => setConfirmDelete(true)}
            className="text-xs font-bold text-[#FF829B] hover:text-red-400 py-1 px-3 rounded-lg hover:bg-red-950/30 transition-all"
          >
            Borrar entrenamiento
          </button>
          <span className="text-[10px] text-[#A5B7C5]">
            Solo afecta al historial del móvil
          </span>
        </div>

        {/* Confirm Delete Sub-dialog */}
        {confirmDelete && (
          <div className="absolute inset-0 bg-black/80 flex items-center justify-center p-4 z-50">
            <div className="bg-[#142333] border border-[#203144] rounded-2xl p-5 max-w-sm text-center shadow-2xl">
              <h4 className="text-white font-bold text-sm mb-2">
                ¿Borrar este entrenamiento?
              </h4>
              <p className="text-[#A5B7C5] text-xs mb-4">
                Se quitará del historial del móvil y se eliminará su GPX local.
              </p>
              <div className="flex gap-2">
                <button
                  onClick={() => setConfirmDelete(false)}
                  className="flex-1 py-2 bg-[#07111C] text-white text-xs font-bold rounded-xl"
                >
                  Cancelar
                </button>
                <button
                  onClick={() => {
                    setConfirmDelete(false);
                    onDelete(workout.startAt);
                    onDismiss();
                  }}
                  className="flex-1 py-2 bg-[#FF829B] text-black text-xs font-bold rounded-xl"
                >
                  Borrar
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

// Canvas metric chart rendering SVG-like smooth line
const MetricChartCanvas: React.FC<{
  points: TimedMetric[];
  color: string;
  unit: string;
}> = ({ points, color, unit }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const minVal = points.length > 0 ? Math.min(...points.map((p) => p.value)) : 0;
  const maxVal = points.length > 0 ? Math.max(...points.map((p) => p.value)) : 1;
  const maxTime =
    points.length > 0 ? Math.max(...points.map((p) => p.seconds), 10) : 10;

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.scale(dpr, dpr);

    ctx.clearRect(0, 0, width, height);

    const padLeft = 10;
    const padRight = 10;
    const padTop = 15;
    const padBottom = 20;

    // Grid lines
    ctx.strokeStyle = '#203144';
    ctx.lineWidth = 1;
    for (let i = 0; i < 4; i++) {
      const y = padTop + ((height - padTop - padBottom) * i) / 3;
      ctx.beginPath();
      ctx.moveTo(padLeft, y);
      ctx.lineTo(width - padRight, y);
      ctx.stroke();
    }

    if (points.length < 2) return;

    const span = Math.max(0.001, maxVal - minVal);
    const plotW = width - padLeft - padRight;
    const plotH = height - padTop - padBottom;

    // Path
    ctx.strokeStyle = color;
    ctx.lineWidth = 2.5;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();

    points.forEach((p, index) => {
      const x = padLeft + (p.seconds / maxTime) * plotW;
      const y = padTop + plotH - ((p.value - minVal) / span) * plotH;
      if (index === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    });
    ctx.stroke();
  }, [points, color, minVal, maxVal, maxTime]);

  return (
    <div>
      <div className="flex justify-between text-[11px] text-[#A5B7C5] mb-1">
        <span>Mín. {minVal.toFixed(1)}</span>
        <span>
          Máx. {maxVal.toFixed(1)} {unit}
        </span>
      </div>
      <div className="w-full h-[180px] bg-[#07111C] border border-[#1b2b3a] rounded-xl overflow-hidden p-1">
        <canvas ref={canvasRef} className="w-full h-full" />
      </div>
      <div className="flex justify-between text-[10px] text-[#A5B7C5] mt-1">
        <span>Inicio 00:00</span>
        <span>Final {Math.floor(maxTime / 60)}:{(Math.floor(maxTime % 60)).toString().padStart(2, '0')}</span>
      </div>
    </div>
  );
};
