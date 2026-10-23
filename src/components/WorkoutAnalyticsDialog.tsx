import React, { useState, useMemo, useRef, useEffect } from 'react';
import { SessionSummary, SpeedUnit } from '../types/openkayak';
import { RouteAnalyzer } from '../services/routeAnalyzer';
import { calculateDistance } from '../services/turnAiModel';
import { formatSpeed, SPEED_UNITS, getNextSpeedUnit } from '../services/speedUnits';

interface WorkoutAnalyticsDialogProps {
  workout: SessionSummary;
  weightKg: number;
  heightCm: number;
  ageYears: number;
  biologicalSex: string;
  speedUnit?: SpeedUnit;
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

interface GeminiCoachAnalysis {
  summaryPhrase: string;
  headline?: string;
  technicalFeedback?: string;
  cardioFeedback?: string;
  strengths?: string[];
  nextSteps?: string[];
  score?: number;
}

export const WorkoutAnalyticsDialog: React.FC<WorkoutAnalyticsDialogProps> = ({
  workout,
  weightKg,
  ageYears,
  speedUnit = 'kmh',
  onDismiss,
  onDelete,
}) => {
  const [tab, setTab] = useState(0); // 0: Resumen, 1: Coach IA Gemini, 2: FC, 3: Velocidad, 4: Tabla 10s
  const [activeSpeedUnit, setActiveSpeedUnit] = useState<SpeedUnit>(speedUnit);
  const [confirmDelete, setConfirmDelete] = useState(false);

  // Gemini AI Analysis State
  const [isAiLoading, setIsAiLoading] = useState(false);
  const [aiAnalysis, setAiAnalysis] = useState<GeminiCoachAnalysis | null>(null);
  const [aiError, setAiError] = useState<string | null>(null);

  const durationSeconds = Math.max(0, Math.floor(workout.elapsedMs / 1000));
  const durationMinutes = Math.max(0.01, workout.elapsedMs / 60000);

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

  const averageSpeedKmh =
    durationMinutes > 0 ? (workout.distanceM / 1000) / (durationMinutes / 60) : 0;

  const peakSpeedKmh =
    speedSeries.length > 0 ? Math.max(...speedSeries.map((s) => s.value)) : 0;

  const cadence =
    durationMinutes > 0 ? workout.strokes / durationMinutes : 0;

  const met = averageSpeedKmh < 3.0 ? 3.5 : averageSpeedKmh < 5.5 ? 5.5 : 7.0;
  const mobileKcalEstimate = Math.round(
    (met * 3.5 * Math.max(25, Math.min(250, weightKg))) / 200.0 * durationMinutes
  );

  const formatSec = (seconds: number): string => {
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const formattedAvgSpeed = formatSpeed(averageSpeedKmh, activeSpeedUnit);
  const formattedPeakSpeed = formatSpeed(peakSpeedKmh, activeSpeedUnit);

  // Call Gemini Workout Analysis API on server
  const handleRequestGeminiAnalysis = async () => {
    setIsAiLoading(true);
    setAiError(null);
    try {
      const payload = {
        elapsedMs: workout.elapsedMs,
        distanceM: workout.distanceM,
        strokes: workout.strokes,
        averageBpm: workout.averageBpm,
        maxBpm: workout.maxBpm,
        minBpm: workout.minBpm,
        calories: workout.calories || mobileKcalEstimate,
        cadenceSpm: Math.round(cadence),
        speedKmhAvg: averageSpeedKmh,
        speedKmhMax: peakSpeedKmh,
        athleteWeightKg: weightKg,
        speedUnit: activeSpeedUnit,
      };

      const res = await fetch('/api/gemini/analyze-workout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        throw new Error(`Servidor devolvió código HTTP ${res.status}`);
      }

      const data = await res.json();
      if (data.analysis) {
        setAiAnalysis(data.analysis);
      } else {
        throw new Error('Respuesta de análisis vacía');
      }
    } catch (err: any) {
      console.error('Error analizando con Gemini:', err);
      setAiError(err.message || 'Error al conectar con la API de Gemini');
    } finally {
      setIsAiLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/85 backdrop-blur-md z-50 flex items-center justify-center p-3 sm:p-6 select-text">
      <div className="bg-[#07111C] border border-[#203144] rounded-3xl w-full max-w-2xl max-h-[92vh] flex flex-col overflow-hidden shadow-2xl">
        {/* Header with Title and Speed Unit Switcher */}
        <div className="flex flex-wrap items-center justify-between p-4 sm:p-5 border-b border-[#142333] gap-2">
          <div>
            <div className="text-[#70E5C5] text-xs font-black tracking-widest uppercase flex items-center gap-2">
              <span>ANÁLISIS DEL ENTRENO</span>
              <span className="text-[10px] bg-[#142333] text-[#70C5F0] border border-[#70C5F0]/30 px-2 py-0.5 rounded-full">
                Wear OS 5
              </span>
            </div>
            <div className="text-[#A5B7C5] text-xs">
              {new Date(workout.startAt).toLocaleString('es-ES', {
                dateStyle: 'medium',
                timeStyle: 'short',
              })}
            </div>
          </div>

          <div className="flex items-center gap-2">
            {/* Speed Unit Quick Selector */}
            <div className="flex items-center gap-1 bg-[#101b28] border border-[#203144] p-1 rounded-xl">
              {SPEED_UNITS.map((u) => (
                <button
                  key={u.id}
                  onClick={() => setActiveSpeedUnit(u.id)}
                  className={`px-2 py-0.5 rounded-lg text-[10px] font-bold transition-all cursor-pointer ${
                    activeSpeedUnit === u.id
                      ? 'bg-[#70E5C5] text-[#07111C] font-black shadow-sm'
                      : 'text-[#A5B7C5] hover:text-white'
                  }`}
                  title={u.name}
                >
                  {u.symbol}
                </button>
              ))}
            </div>

            <button
              onClick={onDismiss}
              className="px-3 py-1 bg-[#142333] hover:bg-[#1f344a] text-white text-xs font-bold rounded-xl transition-all cursor-pointer border border-[#203144]"
            >
              Cerrar
            </button>
          </div>
        </div>

        {/* 3 Summary Glanceable Tiles */}
        <div className="grid grid-cols-3 gap-2 p-3 sm:p-4 bg-[#0a1827]">
          <div className="bg-[#142333] rounded-2xl p-2.5">
            <span className="text-[#A5B7C5] text-[10px] font-bold block">DISTANCIA</span>
            <span className="text-[#70E5C5] text-lg sm:text-xl font-black block tabular-nums">
              {(workout.distanceM / 1000).toFixed(2)} km
            </span>
          </div>
          <div className="bg-[#142333] rounded-2xl p-2.5">
            <span className="text-[#A5B7C5] text-[10px] font-bold block">TIEMPO</span>
            <span className="text-[#70C5F0] text-lg sm:text-xl font-black block tabular-nums">
              {formatSec(durationSeconds)}
            </span>
          </div>
          <div className="bg-[#142333] rounded-2xl p-2.5">
            <span className="text-[#A5B7C5] text-[10px] font-bold block uppercase">
              RITMO / VEL ({formattedAvgSpeed.shortLabel})
            </span>
            <span className="text-[#FF829B] text-lg sm:text-xl font-black block tabular-nums">
              {formattedAvgSpeed.value} {formattedAvgSpeed.shortLabel}
            </span>
          </div>
        </div>

        {/* Tab Filters */}
        <div className="flex overflow-x-auto no-scrollbar border-b border-[#142333] px-3 sm:px-4 bg-[#0a1827]">
          {[
            { id: 0, label: 'Resumen' },
            { id: 1, label: '✨ Coach IA Gemini' },
            { id: 2, label: 'FC' },
            { id: 3, label: 'Velocidad' },
            { id: 4, label: 'Tabla 10 s' },
          ].map((item) => (
            <button
              key={item.id}
              onClick={() => setTab(item.id)}
              className={`py-2 px-3 text-xs font-bold transition-all border-b-2 whitespace-nowrap cursor-pointer ${
                tab === item.id
                  ? 'border-[#70E5C5] text-[#70E5C5]'
                  : 'border-transparent text-[#A5B7C5] hover:text-white'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        {/* Content Body */}
        <div className="flex-1 overflow-y-auto p-4 sm:p-5">
          {tab === 0 && (
            /* TAB 0: RESUMEN WITH HERO AI COACH TEASER */
            <div className="space-y-4">
              {/* Gemini AI Coach Callout Banner */}
              <div className="bg-gradient-to-r from-[#102235] via-[#14283d] to-[#122b27] border border-[#70E5C5]/40 rounded-2xl p-4 shadow-lg">
                <div className="flex flex-wrap items-center justify-between gap-2 mb-2">
                  <div className="flex items-center gap-2">
                    <span className="text-lg">✨</span>
                    <span className="text-[#70E5C5] font-black text-xs uppercase tracking-wider">
                      ANÁLISIS DEL ENTRENADOR IA (GOOGLE AI STUDIO · GEMINI 3.8 FLASH)
                    </span>
                  </div>
                  {!aiAnalysis && !isAiLoading && (
                    <button
                      onClick={handleRequestGeminiAnalysis}
                      className="px-3 py-1 bg-gradient-to-r from-[#70E5C5] to-[#70C5F0] hover:from-[#5cd4b4] hover:to-[#5bb3db] text-[#07111C] font-black text-xs rounded-xl shadow-md cursor-pointer active:scale-95 transition-all"
                    >
                      Generar frase & análisis
                    </button>
                  )}
                </div>

                {isAiLoading && (
                  <div className="py-4 flex flex-col items-center justify-center text-[#70E5C5] gap-2">
                    <div className="w-6 h-6 border-2 border-[#70E5C5] border-t-transparent rounded-full animate-spin" />
                    <span className="text-xs font-bold animate-pulse">
                      Gemini está analizando tus paladas, ritmo cardíaco y velocidad…
                    </span>
                  </div>
                )}

                {aiAnalysis && (
                  <div className="space-y-2 mt-2">
                    {/* The requested 1-sentence workout statement! */}
                    <div className="bg-[#07111C]/90 border-l-4 border-[#70E5C5] p-3 rounded-r-xl">
                      <div className="text-[#A5B7C5] text-[10px] font-bold uppercase mb-0.5">
                        EVALUACIÓN DEL ENTRENADOR:
                      </div>
                      <blockquote className="text-white font-extrabold text-sm sm:text-base italic leading-snug">
                        “{aiAnalysis.summaryPhrase}”
                      </blockquote>
                    </div>

                    {aiAnalysis.headline && (
                      <div className="flex items-center justify-between text-xs pt-1">
                        <span className="text-[#70C5F0] font-bold">{aiAnalysis.headline}</span>
                        {aiAnalysis.score && (
                          <span className="px-2 py-0.5 rounded-full bg-[#70E5C5]/20 text-[#70E5C5] font-black">
                            Nota: {aiAnalysis.score}/10
                          </span>
                        )}
                      </div>
                    )}
                  </div>
                )}

                {aiError && (
                  <div className="text-red-400 text-xs mt-2 bg-red-950/40 p-2 rounded-xl border border-red-500/30">
                    {aiError}
                  </div>
                )}
              </div>

              {/* Workout Effort Metrics */}
              <div>
                <h5 className="text-[#70E5C5] text-xs font-bold tracking-wider uppercase mb-2">
                  ESFUERZO Y PALADAS
                </h5>
                <div className="divide-y divide-[#1b2b3a] text-xs">
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Paladas registradas (sin periodo refractario)</span>
                    <span className="text-white font-bold">{workout.strokes}</span>
                  </div>
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Cadencia media calculada</span>
                    <span className="text-white font-bold">
                      {cadence.toFixed(1)} paladas/min (spm)
                    </span>
                  </div>
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Velocidad máxima registrada</span>
                    <span className="text-white font-bold">
                      {peakSpeedKmh > 0
                        ? `${formattedPeakSpeed.value} ${formattedPeakSpeed.shortLabel}`
                        : 'Sin datos GPX suficientes'}
                    </span>
                  </div>
                  <div className="flex justify-between py-1.5">
                    <span className="text-[#A5B7C5]">Velocidad media</span>
                    <span className="text-[#70E5C5] font-bold">
                      {formattedAvgSpeed.value} {formattedAvgSpeed.unitLabel}
                    </span>
                  </div>
                </div>
              </div>

              {/* Heart Rate Breakdown */}
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
              </div>

              {/* Calories */}
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
                    <span className="text-[#A5B7C5]">Estimación alternativa móvil MET</span>
                    <span className="text-[#70C5F0] font-bold">
                      {mobileKcalEstimate} kcal
                    </span>
                  </div>
                </div>
              </div>

              {/* Laps / Vueltas automáticas if recorded */}
              {workout.laps && workout.laps.length > 0 && (
                <div className="bg-[#0f1d2c] border border-[#203448] rounded-2xl p-3.5 space-y-2">
                  <div className="flex items-center justify-between">
                    <h5 className="text-[#FFCA7A] text-xs font-bold tracking-wider uppercase flex items-center gap-1.5">
                      <span>🏁</span>
                      <span>VUELTAS DEL CIRCUITO ({workout.laps.length})</span>
                    </h5>
                    {workout.structuredWorkoutName && (
                      <span className="text-[10px] text-[#70E5C5] font-semibold bg-[#142637] px-2 py-0.5 rounded-full border border-[#70E5C5]/30">
                        {workout.structuredWorkoutName}
                      </span>
                    )}
                  </div>

                  <div className="space-y-1.5 max-h-[160px] overflow-y-auto pr-1">
                    {workout.laps.map((lap) => {
                      const durSec = Math.round(lap.durationMs / 1000);
                      const min = Math.floor(durSec / 60);
                      const sec = durSec % 60;
                      const timeStr = `${min}:${sec.toString().padStart(2, '0')}`;
                      const lapSpeed = formatSpeed(lap.avgSpeedKmh, activeSpeedUnit);

                      return (
                        <div
                          key={lap.lapNumber}
                          className={`flex items-center justify-between p-2 rounded-xl text-xs border ${
                            lap.isBestLap
                              ? 'bg-amber-950/30 border-amber-500/50 text-white'
                              : 'bg-[#07111c] border-[#1d2d3e] text-[#DCE6EE]'
                          }`}
                        >
                          <div className="flex items-center gap-2">
                            <span
                              className={`w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-black ${
                                lap.isBestLap
                                  ? 'bg-amber-400 text-black'
                                  : 'bg-[#18293a] text-[#8D9DA8]'
                              }`}
                            >
                              {lap.lapNumber}
                            </span>
                            <div>
                              <div className="font-bold flex items-center gap-1.5">
                                <span>Vuelta {lap.lapNumber}</span>
                                {lap.isBestLap && (
                                  <span className="text-[9px] text-amber-300 font-extrabold px-1.5 py-0.2 rounded bg-amber-400/20 border border-amber-400/40">
                                    🏆 Mejor Vuelta
                                  </span>
                                )}
                              </div>
                              <div className="text-[10px] text-[#8D9DA8]">
                                {lap.distanceM} m · {lap.avgCadenceSpm} spm
                              </div>
                            </div>
                          </div>

                          <div className="text-right">
                            <div className="font-mono font-black text-[#70E5C5]">
                              {timeStr}
                            </div>
                            <div className="text-[10px] text-[#A5B7C5]">
                              {lapSpeed.value} {lapSpeed.unitLabel}
                            </div>
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          )}

          {tab === 1 && (
            /* TAB 1: DEDICATED GEMINI COACH ANALYSIS */
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <h4 className="text-white font-black text-sm flex items-center gap-1.5">
                    <span>✨</span>
                    <span>COACH DE PIRAGÜISMO IA · GEMINI</span>
                  </h4>
                  <p className="text-[#A5B7C5] text-xs">
                    Análisis biomecánico y de rendimiento impulsado por la API de Google AI Studio.
                  </p>
                </div>

                <button
                  onClick={handleRequestGeminiAnalysis}
                  disabled={isAiLoading}
                  className="px-4 py-2 bg-gradient-to-r from-[#70E5C5] to-[#70C5F0] hover:from-[#5cd4b4] hover:to-[#5bb3db] text-[#07111C] font-black text-xs rounded-xl shadow-md cursor-pointer transition-all active:scale-95 disabled:opacity-50"
                >
                  {isAiLoading ? 'Analizando…' : aiAnalysis ? 'Reanalizar' : 'Analizar entreno'}
                </button>
              </div>

              {isAiLoading && (
                <div className="py-16 flex flex-col items-center justify-center text-center gap-3">
                  <div className="w-10 h-10 border-3 border-[#70E5C5] border-t-transparent rounded-full animate-spin" />
                  <span className="text-white font-extrabold text-sm">
                    Consultando modelo Gemini 3.8 Flash…
                  </span>
                  <p className="text-[#A5B7C5] text-xs max-w-sm">
                    Evaluando deslizamiento, paladas registradas ({workout.strokes}), cadencia media ({cadence.toFixed(0)} spm) y zonas de esfuerzo.
                  </p>
                </div>
              )}

              {aiError && (
                <div className="bg-red-950/40 border border-red-500/40 rounded-2xl p-4 text-red-300 text-xs">
                  {aiError}
                </div>
              )}

              {!isAiLoading && !aiAnalysis && !aiError && (
                <div className="py-12 text-center bg-[#142333]/50 border border-[#203144] rounded-2xl p-6 space-y-3">
                  <span className="text-3xl">🛶</span>
                  <h5 className="text-white font-bold text-sm">
                    Obtén feedback técnico personalizado de tu sesión
                  </h5>
                  <p className="text-[#A5B7C5] text-xs max-w-md mx-auto">
                    Pulsa el botón superior para conectar con la API de Gemini (Google AI Studio) y recibir una frase resumen del entrenamiento junto con consejos técnicos de palada y resistencia.
                  </p>
                  <button
                    onClick={handleRequestGeminiAnalysis}
                    className="px-5 py-2.5 bg-[#70E5C5] hover:bg-[#5cd4b4] text-[#07111C] font-black text-xs rounded-xl shadow-lg cursor-pointer"
                  >
                    ✨ Analizar entrenamiento con Gemini
                  </button>
                </div>
              )}

              {aiAnalysis && !isAiLoading && (
                <div className="space-y-4">
                  {/* Big Quote / Frase sobre el entreno */}
                  <div className="bg-gradient-to-br from-[#102435] to-[#0a1827] border-2 border-[#70E5C5]/50 rounded-2xl p-5 shadow-xl relative overflow-hidden">
                    <span className="text-[#70E5C5] text-4xl font-serif absolute top-2 left-3 opacity-30">“</span>
                    <div className="relative z-10 pl-4">
                      <div className="text-[#70E5C5] text-[10px] font-black tracking-widest uppercase mb-1">
                        FRASE CLAVE DEL ENTRENADOR
                      </div>
                      <p className="text-white font-black text-base sm:text-lg leading-snug">
                        {aiAnalysis.summaryPhrase}
                      </p>
                    </div>
                  </div>

                  {/* Technical & Cardio Feedback */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {aiAnalysis.technicalFeedback && (
                      <div className="bg-[#142333] border border-[#203144] rounded-2xl p-4 space-y-1">
                        <div className="text-[#70E5C5] text-xs font-bold uppercase tracking-wider flex items-center gap-1.5">
                          <span>⚙️</span>
                          <span>Análisis Técnico & Paladas</span>
                        </div>
                        <p className="text-[#C8D6E5] text-xs leading-relaxed">
                          {aiAnalysis.technicalFeedback}
                        </p>
                      </div>
                    )}

                    {aiAnalysis.cardioFeedback && (
                      <div className="bg-[#142333] border border-[#203144] rounded-2xl p-4 space-y-1">
                        <div className="text-[#FF829B] text-xs font-bold uppercase tracking-wider flex items-center gap-1.5">
                          <span>❤️</span>
                          <span>Esfuerzo Cardiovascular</span>
                        </div>
                        <p className="text-[#C8D6E5] text-xs leading-relaxed">
                          {aiAnalysis.cardioFeedback}
                        </p>
                      </div>
                    )}
                  </div>

                  {/* Strengths & Next steps */}
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {aiAnalysis.strengths && aiAnalysis.strengths.length > 0 && (
                      <div className="bg-[#0f231e] border border-[#70E5C5]/30 rounded-2xl p-4">
                        <div className="text-[#70E5C5] text-xs font-black uppercase mb-2">
                          ✓ PUNTOS FUERTES DE LA SESIÓN
                        </div>
                        <ul className="space-y-1 text-xs text-[#C8D6E5]">
                          {aiAnalysis.strengths.map((item, i) => (
                            <li key={i} className="flex items-start gap-1.5">
                              <span className="text-[#70E5C5]">•</span>
                              <span>{item}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}

                    {aiAnalysis.nextSteps && aiAnalysis.nextSteps.length > 0 && (
                      <div className="bg-[#122334] border border-[#70C5F0]/30 rounded-2xl p-4">
                        <div className="text-[#70C5F0] text-xs font-black uppercase mb-2">
                          🎯 RECOMENDACIONES PRÓXIMA SESIÓN
                        </div>
                        <ul className="space-y-1 text-xs text-[#C8D6E5]">
                          {aiAnalysis.nextSteps.map((item, i) => (
                            <li key={i} className="flex items-start gap-1.5">
                              <span className="text-[#70C5F0]">•</span>
                              <span>{item}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}

          {tab === 2 && (
            /* TAB 2: FC CHART */
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

          {tab === 3 && (
            /* TAB 3: SPEED CHART */
            <div className="space-y-3">
              <div className="flex justify-between text-xs text-[#A5B7C5]">
                <span className="font-bold text-[#70C5F0] uppercase">
                  VELOCIDAD · {formattedAvgSpeed.unitLabel}
                </span>
                <span>Puntos calculados: {speedSeries.length}</span>
              </div>

              {speedSeries.length === 0 ? (
                <div className="py-12 text-center text-[#A5B7C5] text-xs">
                  El GPX no contiene suficientes puntos GPS para calcular la curva de velocidad.
                </div>
              ) : (
                <MetricChartCanvas
                  points={speedSeries}
                  color="#70C5F0"
                  unit={formattedAvgSpeed.shortLabel}
                />
              )}
            </div>
          )}

          {tab === 4 && (
            /* TAB 4: 10S TABLE */
            <div>
              <div className="text-xs font-bold text-[#70E5C5] mb-2 flex items-center justify-between">
                <span>MUESTRAS AGRUPADAS CADA 10 SEGUNDOS</span>
                <span className="text-[#A5B7C5] font-normal">
                  Unidad de velocidad: <b className="text-white">{formattedAvgSpeed.unitLabel}</b>
                </span>
              </div>
              <div className="bg-[#142333] rounded-xl overflow-hidden border border-[#203144]">
                <div className="grid grid-cols-3 bg-[#0d1c2b] p-2 text-xs font-bold border-b border-[#203144]">
                  <span className="text-[#70E5C5]">Tiempo</span>
                  <span className="text-[#FF829B]">FC (lpm)</span>
                  <span className="text-[#70C5F0]">Velocidad ({formattedAvgSpeed.shortLabel})</span>
                </div>
                <div className="max-h-[300px] overflow-y-auto divide-y divide-[#172738] text-xs">
                  {tenSecondRows.map((row) => {
                    const rowSpeedFormatted =
                      row.speedKmh !== null
                        ? formatSpeed(row.speedKmh, activeSpeedUnit).value
                        : '—';
                    return (
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
                          {rowSpeedFormatted}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          )}
        </div>

        {/* Footer Actions */}
        <div className="p-4 bg-[#0a1827] border-t border-[#142333] flex items-center justify-between">
          <button
            onClick={() => setConfirmDelete(true)}
            className="text-xs font-bold text-[#FF829B] hover:text-red-400 py-1.5 px-3 rounded-lg hover:bg-red-950/30 transition-all cursor-pointer"
          >
            Borrar entrenamiento
          </button>
          <span className="text-[10px] text-[#A5B7C5]">
            OpenKayak 2.2.2 · Sincronizado con Wear OS 5
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
                  className="flex-1 py-2 bg-[#07111C] text-white text-xs font-bold rounded-xl cursor-pointer"
                >
                  Cancelar
                </button>
                <button
                  onClick={() => {
                    setConfirmDelete(false);
                    onDelete(workout.startAt);
                    onDismiss();
                  }}
                  className="flex-1 py-2 bg-[#FF829B] text-black text-xs font-bold rounded-xl cursor-pointer"
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
