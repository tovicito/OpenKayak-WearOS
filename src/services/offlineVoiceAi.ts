import { TrackSnapshot, BleHrState, ActiveWorkoutState, Lap } from '../types/openkayak';

export type VoiceAiIntent =
  | 'QUERY_DISTANCE'
  | 'QUERY_TIME'
  | 'QUERY_SPEED'
  | 'QUERY_HEART_RATE'
  | 'QUERY_STROKES'
  | 'QUERY_WORKOUT_PHASE'
  | 'QUERY_LAPS'
  | 'ACTION_START'
  | 'ACTION_PAUSE'
  | 'ACTION_RESUME'
  | 'ACTION_FINISH'
  | 'ACTION_MARK_BUOY'
  | 'ACTION_TRIGGER_LAP'
  | 'GREETING'
  | 'UNKNOWN';

export interface VoiceAiResult {
  intent: VoiceAiIntent;
  responseSpanish: string;
  actionRequired?: 'start' | 'pause' | 'resume' | 'finish' | 'mark_buoy' | 'trigger_lap';
}

export class OfflineVoiceAi {
  /**
   * Normalizes text for Spanish phonetic matching (removes accents, punctuation, lowercase)
   */
  public static normalize(text: string): string {
    return text
      .toLowerCase()
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-z0-9\s]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
  }

  /**
   * Detects if the hotword / wake word "hola reloj" (or common acoustic variations) is present
   */
  public static containsWakeWord(rawText: string): boolean {
    const text = this.normalize(rawText);
    const patterns = [
      'hola reloj',
      'ola reloj',
      'hola relo',
      'ola relo',
      'hola reloi',
      'ola reloi',
      'hola relox',
      'oye reloj',
      'eh reloj',
      'hola relojito',
    ];
    return patterns.some((p) => text.includes(p));
  }

  /**
   * Extracts user command after wake word, or parses direct command
   */
  public static extractCommand(rawText: string): string {
    const norm = this.normalize(rawText);
    const wakeWords = [
      'hola reloj',
      'ola reloj',
      'hola relo',
      'ola relo',
      'oye reloj',
      'eh reloj',
    ];

    for (const w of wakeWords) {
      const idx = norm.indexOf(w);
      if (idx !== -1) {
        return norm.substring(idx + w.length).trim();
      }
    }
    return norm;
  }

  /**
   * Offline natural language comprehension model running on CPU without internet
   */
  public static processQuery(
    commandText: string,
    snapshot: TrackSnapshot,
    ble: BleHrState,
    activeWorkoutState?: ActiveWorkoutState | null,
    laps?: Lap[]
  ): VoiceAiResult {
    const text = this.normalize(commandText);

    // 1. Distance queries
    if (
      text.includes('distancia') ||
      text.includes('kilometros') ||
      text.includes('metros') ||
      text.includes('cuanto llevo') ||
      text.includes('cuanto hemos hecho') ||
      text.includes('que distancia') ||
      text.includes('cuanto recorrimos') ||
      text.includes('cuanto hemos remado')
    ) {
      const km = (snapshot.distanceM / 1000).toFixed(2);
      return {
        intent: 'QUERY_DISTANCE',
        responseSpanish: `Lleva recorridos ${km.replace('.', ',')} kilómetros en la sesión.`,
      };
    }

    // 2. Time / Duration queries
    if (
      text.includes('tiempo') ||
      text.includes('duracion') ||
      text.includes('minutos') ||
      text.includes('cuanto tiempo') ||
      text.includes('hora llevamos') ||
      text.includes('cronometro')
    ) {
      const sec = Math.floor(snapshot.elapsedMs / 1000);
      const min = Math.floor(sec / 60);
      const remSec = sec % 60;
      let timeDesc = '';
      if (min > 0) {
        timeDesc = `${min} minutos y ${remSec} segundos`;
      } else {
        timeDesc = `${remSec} segundos`;
      }
      return {
        intent: 'QUERY_TIME',
        responseSpanish: `El tiempo transcurrido es de ${timeDesc}.`,
      };
    }

    // 3. Speed / Pace queries
    if (
      text.includes('velocidad') ||
      text.includes('rapido') ||
      text.includes('rapidez') ||
      text.includes('ritmo') ||
      text.includes('a cuanto voy') ||
      text.includes('que velocidad')
    ) {
      const spd = snapshot.speedKmh.toFixed(1).replace('.', ',');
      return {
        intent: 'QUERY_SPEED',
        responseSpanish: `Navega a una velocidad de ${spd} kilómetros por hora.`,
      };
    }

    // 4. Heart Rate / Pulse queries
    if (
      text.includes('pulso') ||
      text.includes('frecuencia') ||
      text.includes('cardiaca') ||
      text.includes('corazon') ||
      text.includes('pulsaciones') ||
      text.includes('latidos')
    ) {
      const isFresh =
        ble.phase === 'CONNECTED' &&
        ble.bpm >= 25 &&
        ble.lastSampleAt > 0 &&
        Date.now() - ble.lastSampleAt < 12000;

      if (isFresh || snapshot.pulse > 0) {
        const bpm = isFresh ? ble.bpm : snapshot.pulse;
        return {
          intent: 'QUERY_HEART_RATE',
          responseSpanish: `Su frecuencia cardíaca actual es de ${bpm} pulsaciones por minuto.`,
        };
      } else {
        return {
          intent: 'QUERY_HEART_RATE',
          responseSpanish: 'No hay lectura activa de pulso. Verifique que la banda pectoral BLE esté conectada.',
        };
      }
    }

    // 5. Strokes / Cadence queries
    if (
      text.includes('palada') ||
      text.includes('paladas') ||
      text.includes('remadas') ||
      text.includes('cadencia') ||
      text.includes('paleo')
    ) {
      return {
        intent: 'QUERY_STROKES',
        responseSpanish: `Ha completado ${snapshot.strokes} paladas, con una cadencia actual de ${snapshot.cadenceSpm} paladas por minuto.`,
      };
    }

    // 6. Start Workout Action
    if (
      text.includes('empezar') ||
      text.includes('iniciar') ||
      text.includes('comenzar') ||
      text.includes('arrancar') ||
      text.includes('vamos a remar')
    ) {
      if (snapshot.active) {
        return {
          intent: 'ACTION_START',
          responseSpanish: 'El entrenamiento ya se encuentra en curso.',
        };
      }
      return {
        intent: 'ACTION_START',
        responseSpanish: 'Iniciando entrenamiento de piragüismo. ¡Buen paleo!',
        actionRequired: 'start',
      };
    }

    // 7. Pause Workout Action
    if (
      text.includes('pausar') ||
      text.includes('pausa') ||
      text.includes('para un momento') ||
      text.includes('descanso')
    ) {
      if (!snapshot.active) {
        return {
          intent: 'ACTION_PAUSE',
          responseSpanish: 'No hay ninguna sesión activa para pausar.',
        };
      }
      if (snapshot.paused) {
        return {
          intent: 'ACTION_PAUSE',
          responseSpanish: 'La sesión ya está pausada.',
        };
      }
      return {
        intent: 'ACTION_PAUSE',
        responseSpanish: 'Entrenamiento pausado. Tómese un respiro.',
        actionRequired: 'pause',
      };
    }

    // 8. Resume Workout Action
    if (
      text.includes('reanudar') ||
      text.includes('continua') ||
      text.includes('continuar') ||
      text.includes('seguir') ||
      text.includes('sigue')
    ) {
      if (!snapshot.active || !snapshot.paused) {
        return {
          intent: 'ACTION_RESUME',
          responseSpanish: 'El entrenamiento no se encuentra pausado.',
        };
      }
      return {
        intent: 'ACTION_RESUME',
        responseSpanish: 'Reanudando entrenamiento. Adelante.',
        actionRequired: 'resume',
      };
    }

    // 9. Finish Workout Action
    if (
      text.includes('finalizar') ||
      text.includes('terminar') ||
      text.includes('acabar') ||
      text.includes('fin del entreno') ||
      text.includes('parar sesion')
    ) {
      if (!snapshot.active) {
        return {
          intent: 'ACTION_FINISH',
          responseSpanish: 'No hay ninguna sesión en curso para finalizar.',
        };
      }
      return {
        intent: 'ACTION_FINISH',
        responseSpanish: 'Finalizando entrenamiento y calculando análisis de giros.',
        actionRequired: 'finish',
      };
    }

    // 10. Mark Buoy / Point Action
    if (
      text.includes('boya') ||
      text.includes('marcar punto') ||
      text.includes('guardar punto') ||
      text.includes('marcar giro') ||
      text.includes('nueva boya')
    ) {
      return {
        intent: 'ACTION_MARK_BUOY',
        responseSpanish: 'Punto de boya registrado en sus coordenadas GPS.',
        actionRequired: 'mark_buoy',
      };
    }

    // 11. Structured Workout Phase queries ("¿Qué fase toca?", "¿Fase actual?", "¿Qué viene ahora?")
    if (
      text.includes('fase') ||
      text.includes('intervalo') ||
      text.includes('que toca') ||
      text.includes('que viene') ||
      text.includes('siguiente serie') ||
      text.includes('sprint') ||
      text.includes('recuperacion')
    ) {
      if (activeWorkoutState && !activeWorkoutState.isCompleted) {
        let statusDetail = '';
        if (activeWorkoutState.phaseRemainingSec !== undefined) {
          const rem = activeWorkoutState.phaseRemainingSec;
          const min = Math.floor(rem / 60);
          const sec = rem % 60;
          statusDetail = min > 0 ? `Quedan ${min} minutos y ${sec} segundos` : `Quedan ${sec} segundos`;
        } else {
          statusDetail = activeWorkoutState.phaseMetricDisplay || 'en progreso';
        }

        return {
          intent: 'QUERY_WORKOUT_PHASE',
          responseSpanish: `Fase actual: ${activeWorkoutState.currentPhaseName}, serie ${activeWorkoutState.currentCycle} de ${activeWorkoutState.totalCycles}. ${statusDetail}.`,
        };
      } else {
        return {
          intent: 'QUERY_WORKOUT_PHASE',
          responseSpanish: 'No hay ningún entrenamiento por fases activo en este momento.',
        };
      }
    }

    // 12. Laps / Vueltas queries ("¿Cuántas vueltas llevo?", "¿Mejor vuelta?")
    if (
      text.includes('vuelta') ||
      text.includes('vueltas') ||
      text.includes('lap') ||
      text.includes('circuito')
    ) {
      // Check if user is asking to trigger/mark a lap manually
      if (text.includes('marcar') || text.includes('nueva') || text.includes('cerrar')) {
        return {
          intent: 'ACTION_TRIGGER_LAP',
          responseSpanish: 'Registrando nueva vuelta en el circuito.',
          actionRequired: 'trigger_lap',
        };
      }

      if (laps && laps.length > 0) {
        const best = laps.reduce((b, c) => (c.durationMs < b.durationMs ? c : b), laps[0]);
        const bestMin = Math.floor(best.durationMs / 60000);
        const bestSec = Math.round((best.durationMs % 60000) / 1000);
        const bestTimeStr = bestMin > 0 ? `${bestMin}m ${bestSec}s` : `${bestSec}s`;
        return {
          intent: 'QUERY_LAPS',
          responseSpanish: `Ha completado ${laps.length} ${laps.length === 1 ? 'vuelta' : 'vueltas'}. La mejor vuelta ha sido la número ${best.lapNumber} en ${bestTimeStr} a ${best.avgSpeedKmh.toFixed(1).replace('.', ',')} km/h.`,
        };
      } else {
        return {
          intent: 'QUERY_LAPS',
          responseSpanish: 'Aún no ha completado ninguna vuelta al circuito en esta sesión.',
        };
      }
    }

    // 11. Greeting / Capabilities
    if (
      text.includes('hola') ||
      text.includes('buenos dias') ||
      text.includes('buenas tardes') ||
      text.includes('quien eres') ||
      text.includes('ayuda')
    ) {
      return {
        intent: 'GREETING',
        responseSpanish: 'Hola. Soy la IA de a bordo. Puede preguntarme por su distancia, velocidad, pulso, paladas, tiempo o pedirme iniciar y pausar.',
      };
    }

    // Default fallback
    return {
      intent: 'UNKNOWN',
      responseSpanish: `Entendido: "${commandText}". Puede consultar distancia, velocidad, pulso, tiempo, paladas o dar órdenes de sesión.`,
    };
  }

  /**
   * Speaks the response out loud in offline Spanish via SpeechSynthesis API
   */
  public static speak(text: string): void {
    if (typeof window === 'undefined' || !window.speechSynthesis) return;

    try {
      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'es-ES';
      utterance.rate = 1.05;
      utterance.pitch = 1.0;

      // Select a Spanish voice if available
      const voices = window.speechSynthesis.getVoices();
      const spanishVoice = voices.find(
        (v) => v.lang.startsWith('es') || v.lang.includes('ES')
      );
      if (spanishVoice) {
        utterance.voice = spanishVoice;
      }

      window.speechSynthesis.speak(utterance);
    } catch (_) {}
  }
}
