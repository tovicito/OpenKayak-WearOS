import { StructuredWorkout, WorkoutPhase } from '../types/openkayak';

/**
 * Offline Natural Language Workout Parser for OpenKayak
 * Parses Spanish voice or text prompts like:
 * "20 minutos a paleo foto (tecnica, lento), 3 vueltas al circuito del memorial y una pausa indefinida hasta que llegue a 8km/h (y se contará a partir de ahí y 5 segundos antes) y entonces empezará la última serie la cual será hacer 100m, luego, el entreno finalizará"
 */
export function parseWorkoutOffline(
  rawText: string,
  availableCircuits: Array<{ id: string; name: string }> = []
): StructuredWorkout {
  const norm = rawText.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');

  const phases: WorkoutPhase[] = [];
  let phaseCounter = 1;

  // Split by logical sentence or transition connectors
  const parts = norm
    .split(/,|\by\b|\bluego\b|\bdespues\b|\bentonces\b|\ba continuacion\b|\bposteriormente\b/)
    .map((p) => p.trim())
    .filter((p) => p.length > 2);

  for (const part of parts) {
    // 1. Check for SPEED TRIGGER / PAUSE
    // e.g. "pausa indefinida hasta que llegue a 8km/h" / "hasta 8 km/h"
    const speedMatch = part.match(/(?:pausa|parada|descanso).*?(?:hasta|llegue a|supere|a)\s*(\d+(?:[.,]\d+)?)\s*(?:km\/h|kmh|kilometros por hora)?/);
    const speedOnlyMatch = part.match(/(?:llegue a|supere|hasta alcanzar)\s*(\d+(?:[.,]\d+)?)\s*(?:km\/h|kmh|kilometros)/);

    if (speedMatch || (speedOnlyMatch && part.includes('pausa'))) {
      const spdVal = parseFloat((speedMatch ? speedMatch[1] : speedOnlyMatch![1]).replace(',', '.'));
      phases.push({
        id: `p-${phaseCounter++}`,
        name: `Pausa hasta superar ${spdVal} km/h`,
        type: 'PAUSE',
        triggerType: 'SPEED_TRIGGER',
        triggerSpeedKmh: spdVal,
        leadTimeSec: 5,
        notes: 'Pausa activa. Al acelerar y superar la velocidad indicada se dispara la siguiente fase',
      });
      continue;
    }

    // 2. Check for LAPS / CIRCUITO
    // e.g. "3 vueltas al circuito del memorial" / "2 vueltas al circuito"
    const lapMatch = part.match(/(\d+)\s*(?:vueltas?|laps?)(?:\s*(?:al|del|en el)\s*circuito(?:\s*(?:del?|de)?)?\s*([a-zA-Z0-9_\s]+))?/);
    if (lapMatch) {
      const lapCount = parseInt(lapMatch[1], 10);
      let circuitName = lapMatch[2] ? lapMatch[2].trim() : 'Memorial';
      if (!circuitName || circuitName.length < 2) {
        circuitName = availableCircuits[0]?.name || 'Memorial';
      }

      phases.push({
        id: `p-${phaseCounter++}`,
        name: `${lapCount} ${lapCount === 1 ? 'vuelta' : 'vueltas'} circuito ${circuitName}`,
        type: 'LAP_SERIES',
        triggerType: 'LAPS',
        targetLaps: lapCount,
        targetCircuitName: circuitName,
        targetSpm: 72,
        targetSpeedKmh: 10.5,
        notes: `Completar ${lapCount} vueltas automáticas al circuito`,
      });
      continue;
    }

    // 3. Check for DISTANCE
    // e.g. "hacer 100m" / "serie de 500 metros" / "1000m" / "1 km"
    const distMatch = part.match(/(?:serie|hacer|recorrer)?\s*(\d+)\s*(?:m|metros|mts)\b/);
    const kmMatch = part.match(/(?:serie|hacer|recorrer)?\s*(\d+(?:[.,]\d+)?)\s*(?:km|kilometros)\b/);

    if (distMatch) {
      const meters = parseInt(distMatch[1], 10);
      phases.push({
        id: `p-${phaseCounter++}`,
        name: `Serie de ${meters} metros`,
        type: meters <= 250 ? 'SPRINT' : 'DISTANCE_SERIES',
        triggerType: 'DISTANCE',
        targetDistanceM: meters,
        targetSpm: meters <= 250 ? 105 : 85,
        targetSpeedKmh: meters <= 250 ? 14.5 : 12.0,
        notes: `Recorrer ${meters}m a ritmo exigente`,
      });
      continue;
    } else if (kmMatch) {
      const kmVal = parseFloat(kmMatch[1].replace(',', '.'));
      const meters = Math.round(kmVal * 1000);
      phases.push({
        id: `p-${phaseCounter++}`,
        name: `Tirada de ${kmVal} km`,
        type: 'DISTANCE_SERIES',
        triggerType: 'DISTANCE',
        targetDistanceM: meters,
        targetSpm: 70,
        targetSpeedKmh: 10.0,
        notes: `Recorrer ${kmVal} km de fondo`,
      });
      continue;
    }

    // 4. Check for DURATION (Minutes / Seconds)
    // e.g. "20 minutos a paleo foto (tecnica, lento)" / "40 min suave" / "20 segundos de sprint" / "1 minuto de recuperacion"
    const minMatch = part.match(/(\d+)\s*(?:minutos?|min)\b/);
    const secMatch = part.match(/(\d+)\s*(?:segundos?|seg|s)\b/);

    if (minMatch || secMatch) {
      const minutes = minMatch ? parseInt(minMatch[1], 10) : 0;
      const seconds = secMatch ? parseInt(secMatch[1], 10) : 0;
      const totalSec = minutes * 60 + seconds;

      let type: WorkoutPhase['type'] = 'STEADY';
      let name = `Fase de ${minutes > 0 ? `${minutes}m` : ''}${seconds > 0 ? `${seconds}s` : ''}`;
      let spm = 65;
      let spd = 9.0;
      let notes = '';

      if (part.includes('paleo foto') || part.includes('tecnica') || part.includes('lento')) {
        type = 'TECHNIQUE';
        name = `Paleo foto (técnica y lento) ${minutes > 0 ? `${minutes}m` : ''}`;
        spm = 48;
        spd = 6.2;
        notes = 'Foco en clavado, torsión dorsal y salida limpia';
      } else if (part.includes('sprint') || part.includes('rapido') || part.includes('tope') || part.includes('fuerte')) {
        type = 'SPRINT';
        name = `Sprint a tope ${totalSec}s`;
        spm = 110;
        spd = 14.5;
        notes = '¡Máxima frecuencia y potencia!';
      } else if (part.includes('recupera') || part.includes('descanso') || part.includes('suave')) {
        type = 'RECOVERY';
        name = `Recuperación activa ${minutes > 0 ? `${minutes}m` : `${seconds}s`}`;
        spm = 55;
        spd = 6.5;
        notes = 'Respiración profunda y soltar brazos';
      } else if (part.includes('calentamiento')) {
        type = 'WARMUP';
        name = `Calentamiento progresivo ${minutes}m`;
        spm = 68;
        spd = 8.8;
      } else if (part.includes('enfriar') || part.includes('vuelta a la calma')) {
        type = 'COOLDOWN';
        name = `Vuelta a la calma ${minutes}m`;
        spm = 55;
        spd = 6.5;
      }

      phases.push({
        id: `p-${phaseCounter++}`,
        name,
        type,
        triggerType: 'TIME',
        durationSec: totalSec,
        targetSpm: spm,
        targetSpeedKmh: spd,
        notes,
      });
      continue;
    }
  }

  // If no phases were successfully extracted, build a sensible structured workout
  if (phases.length === 0) {
    phases.push(
      {
        id: 'p-1',
        name: 'Paleo inicial',
        type: 'STEADY',
        triggerType: 'TIME',
        durationSec: 1200,
        targetSpm: 65,
        targetSpeedKmh: 9.0,
      },
      {
        id: 'p-2',
        name: 'Serie final 100m',
        type: 'DISTANCE_SERIES',
        triggerType: 'DISTANCE',
        targetDistanceM: 100,
        targetSpm: 105,
        targetSpeedKmh: 14.0,
      }
    );
  }

  const workoutName = phases.length > 2
    ? `${phases[0].name} + ${phases[1].name} + ${phases[2].name}`.slice(0, 50)
    : `Entrenamiento IA (${phases.length} fases)`;

  const totalDuration = phases.reduce((acc, p) => acc + (p.durationSec || 120), 0);

  return {
    id: `ai-workout-${Date.now()}`,
    name: workoutName,
    description: `Generado por IA: ${rawText.slice(0, 150)}...`,
    repeatCycles: 1,
    totalEstimatedDurationSec: totalDuration,
    phases,
    createdAt: Date.now(),
  };
}
