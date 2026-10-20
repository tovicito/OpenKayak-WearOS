import {
  StructuredWorkout,
  WorkoutPhase,
  ActiveWorkoutState,
  AlertMode,
  Lap,
} from '../types/openkayak';
import { OfflineVoiceAi } from './offlineVoiceAi';
import { soundEffects } from './soundEffects';

export interface IntervalTransitionEvent {
  previousPhase?: WorkoutPhase;
  currentPhase: WorkoutPhase;
  phaseIndex: number;
  currentCycle: number;
  totalCycles: number;
  speechText: string;
}

export class WorkoutIntervalEngine {
  private workout: StructuredWorkout | null = null;
  private currentPhaseIndex: number = 0;
  private phaseElapsedSec: number = 0;
  private currentCycle: number = 1;
  private isRunning: boolean = false;
  private isCompleted: boolean = false;
  private alertMode: AlertMode = 'VOICE_AND_VIBRATION';

  // Dynamic trigger state tracking
  private phaseStartDistanceM: number = 0;
  private currentDistanceM: number = 0;
  private lapsCompletedInCurrentPhase: number = 0;
  private speedHistory5s: number[] = [];

  private onTransitionCallback?: (event: IntervalTransitionEvent) => void;

  constructor(alertMode: AlertMode = 'VOICE_AND_VIBRATION') {
    this.alertMode = alertMode;
  }

  public setAlertMode(mode: AlertMode): void {
    this.alertMode = mode;
  }

  public onTransition(callback: (event: IntervalTransitionEvent) => void): void {
    this.onTransitionCallback = callback;
  }

  public start(workout: StructuredWorkout, initialDistanceM: number = 0): void {
    this.workout = workout;
    this.currentPhaseIndex = 0;
    this.phaseElapsedSec = 0;
    this.currentCycle = 1;
    this.isRunning = true;
    this.isCompleted = false;
    this.phaseStartDistanceM = initialDistanceM;
    this.currentDistanceM = initialDistanceM;
    this.lapsCompletedInCurrentPhase = 0;
    this.speedHistory5s = [];

    const firstPhase = workout.phases[0];
    if (firstPhase) {
      const speech = this.formatPhaseStartAnnouncement(firstPhase, 1, workout.repeatCycles);
      this.announce(speech, true);
    }
  }

  public stop(): void {
    this.isRunning = false;
    this.workout = null;
    this.currentPhaseIndex = 0;
    this.phaseElapsedSec = 0;
    this.lapsCompletedInCurrentPhase = 0;
  }

  public pause(): void {
    this.isRunning = false;
  }

  public resume(): void {
    if (this.workout && !this.isCompleted) {
      this.isRunning = true;
    }
  }

  public isActive(): boolean {
    return this.isRunning && !this.isCompleted && this.workout !== null;
  }

  /**
   * Called every 1 second by the master session ticker in App.tsx
   */
  public tick1s(currentSpeedKmh: number = 0, totalDistanceM: number = 0): void {
    if (!this.isRunning || this.isCompleted || !this.workout) return;

    this.phaseElapsedSec += 1;
    this.currentDistanceM = totalDistanceM;

    // Maintain 5s sliding speed window
    this.speedHistory5s.push(currentSpeedKmh);
    if (this.speedHistory5s.length > 5) {
      this.speedHistory5s.shift();
    }

    const currentPhase = this.workout.phases[this.currentPhaseIndex];
    if (!currentPhase) return;

    // Trigger Type 1: SPEED TRIGGER
    // (e.g. pausa indefinida hasta que llegue a 8km/h, retroactivando los 5s de aceleración)
    if (currentPhase.triggerType === 'SPEED_TRIGGER') {
      const threshold = currentPhase.triggerSpeedKmh || 8.0;
      if (currentSpeedKmh >= threshold) {
        const speech = `¡Objetivo de velocidad alcanzado a ${currentSpeedKmh.toFixed(1)} km/h! Arrancando siguiente fase.`;
        this.announce(speech, true);
        this.advancePhase();
        return;
      }
    }

    // Trigger Type 2: DISTANCE
    // (e.g. serie final de 100m)
    if (currentPhase.triggerType === 'DISTANCE') {
      const targetDist = currentPhase.targetDistanceM || 100;
      const coveredInPhase = Math.max(0, this.currentDistanceM - this.phaseStartDistanceM);
      if (coveredInPhase >= targetDist) {
        const speech = `¡Objetivo de ${targetDist} metros completado!`;
        this.announce(speech, true);
        this.advancePhase();
        return;
      }
    }

    // Trigger Type 3: LAPS (handled directly via notifyLapCompleted, but checked here as safety)
    if (currentPhase.triggerType === 'LAPS') {
      const targetLaps = currentPhase.targetLaps || 1;
      if (this.lapsCompletedInCurrentPhase >= targetLaps) {
        const speech = `¡${targetLaps} vueltas completadas!`;
        this.announce(speech, true);
        this.advancePhase();
        return;
      }
    }

    // Trigger Type 4: TIME (standard default duration)
    if (currentPhase.triggerType === 'TIME' || !currentPhase.triggerType) {
      const targetDuration = currentPhase.durationSec || 60;
      const remaining = targetDuration - this.phaseElapsedSec;

      // Countdown announcements for intense intervals (3, 2, 1)
      if (currentPhase.type === 'SPRINT' && remaining <= 3 && remaining > 0) {
        if (this.alertMode === 'VOICE_AND_VIBRATION' || this.alertMode === 'VOICE_ONLY') {
          OfflineVoiceAi.speak(`${remaining}`);
        }
      }

      if (this.phaseElapsedSec >= targetDuration) {
        this.advancePhase();
      }
    }
  }

  /**
   * Called when a lap is completed in the session
   */
  public notifyLapCompleted(lap: Lap, circuitName?: string): void {
    if (!this.isRunning || this.isCompleted || !this.workout) return;

    const currentPhase = this.workout.phases[this.currentPhaseIndex];
    if (!currentPhase) return;

    if (currentPhase.triggerType === 'LAPS') {
      this.lapsCompletedInCurrentPhase += 1;
      const targetLaps = currentPhase.targetLaps || 1;
      const remainingLaps = targetLaps - this.lapsCompletedInCurrentPhase;

      if (remainingLaps > 0) {
        const speech = `Vuelta ${this.lapsCompletedInCurrentPhase} de ${targetLaps} completada. Quedan ${remainingLaps} vueltas.`;
        this.announce(speech, false);
      } else {
        const speech = `¡Fase de ${targetLaps} vueltas completada!`;
        this.announce(speech, true);
        this.advancePhase();
      }
    }
  }

  private advancePhase(): void {
    if (!this.workout) return;

    const prevPhase = this.workout.phases[this.currentPhaseIndex];
    const nextIndex = this.currentPhaseIndex + 1;

    // Reset per-phase trackers
    this.phaseStartDistanceM = this.currentDistanceM;
    this.lapsCompletedInCurrentPhase = 0;

    // Check if we reached end of phase array
    if (nextIndex < this.workout.phases.length) {
      this.currentPhaseIndex = nextIndex;
      this.phaseElapsedSec = 0;
      const nextPhase = this.workout.phases[nextIndex];
      const speech = this.formatPhaseStartAnnouncement(
        nextPhase,
        this.currentCycle,
        this.workout.repeatCycles
      );
      this.announce(speech, nextPhase.type === 'SPRINT' || nextPhase.type === 'DISTANCE_SERIES');

      if (this.onTransitionCallback) {
        this.onTransitionCallback({
          previousPhase: prevPhase,
          currentPhase: nextPhase,
          phaseIndex: this.currentPhaseIndex,
          currentCycle: this.currentCycle,
          totalCycles: this.workout.repeatCycles,
          speechText: speech,
        });
      }
    } else {
      // Completed all phases in current cycle: check if repetitions remain
      if (this.currentCycle < this.workout.repeatCycles) {
        this.currentCycle += 1;
        this.currentPhaseIndex = 0;
        this.phaseElapsedSec = 0;

        const nextPhase = this.workout.phases[this.currentPhaseIndex];
        const speech =
          `Ciclo ${this.currentCycle} de ${this.workout.repeatCycles}. ` +
          this.formatPhaseStartAnnouncement(nextPhase, this.currentCycle, this.workout.repeatCycles);
        this.announce(speech, true);

        if (this.onTransitionCallback) {
          this.onTransitionCallback({
            previousPhase: prevPhase,
            currentPhase: nextPhase,
            phaseIndex: this.currentPhaseIndex,
            currentCycle: this.currentCycle,
            totalCycles: this.workout.repeatCycles,
            speechText: speech,
          });
        }
      } else {
        // Whole structured workout completed!
        this.isCompleted = true;
        this.isRunning = false;
        const completionSpeech =
          '¡Entrenamiento completado! Todas las series, vueltas y fases finalizadas con éxito.';
        this.announce(completionSpeech, true);

        if (typeof navigator !== 'undefined' && navigator.vibrate) {
          try {
            navigator.vibrate([300, 150, 300, 150, 400]);
          } catch (_) {}
        }
      }
    }
  }

  private formatPhaseStartAnnouncement(
    phase: WorkoutPhase,
    cycle: number,
    totalCycles: number
  ): string {
    // 1. Lap trigger
    if (phase.triggerType === 'LAPS') {
      const laps = phase.targetLaps || 1;
      const circ = phase.targetCircuitName ? `al circuito ${phase.targetCircuitName}` : '';
      return `¡Atención! Fase de ${laps} ${laps === 1 ? 'vuelta' : 'vueltas'} ${circ}. Cadencia recomendada: ${phase.targetSpm || 70} spm.`;
    }

    // 2. Speed trigger
    if (phase.triggerType === 'SPEED_TRIGGER') {
      const spd = phase.triggerSpeedKmh || 8.0;
      return `Pausa indefinida. Descansa o paleo suave hasta que aceleres y superes ${spd} kilómetros por hora para arrancar la siguiente serie.`;
    }

    // 3. Distance trigger
    if (phase.triggerType === 'DISTANCE') {
      const dist = phase.targetDistanceM || 100;
      return `¡Serie de ${dist} metros a máxima intensidad! ¡Sprint a tope!`;
    }

    // 4. Time trigger
    const duration = phase.durationSec || 60;
    const min = Math.floor(duration / 60);
    const sec = duration % 60;
    let durText = '';
    if (min > 0 && sec > 0) durText = `${min} minutos y ${sec} segundos`;
    else if (min > 0) durText = `${min} minutos`;
    else durText = `${sec} segundos`;

    switch (phase.type) {
      case 'TECHNIQUE':
        return `Paleo foto y técnica: ${durText}. Concentración en torsión y entrada de hoja limpia.`;
      case 'SPRINT':
        return `¡Sprint! ${durText} a máxima intensidad. Cadencia objetivo: ${phase.targetSpm || 105} paladas.`;
      case 'RECOVERY':
        return `Recuperación activa. ${durText} a ritmo suave para bajar pulsaciones.`;
      case 'STEADY':
        return `Ritmo de fondo. ${durText} constante y deslizamiento limpio.`;
      case 'WARMUP':
        return `Inicio del calentamiento: ${durText} progresivo.`;
      case 'COOLDOWN':
        return `Vuelta a la calma: ${durText} suave de recuperación final.`;
      default:
        return `Fase: ${phase.name}, duración ${durText}.`;
    }
  }

  private announce(speechText: string, isUrgent: boolean): void {
    // 1. Vibration alert
    if (
      this.alertMode === 'VOICE_AND_VIBRATION' ||
      this.alertMode === 'VIBRATION_ONLY'
    ) {
      if (typeof navigator !== 'undefined' && navigator.vibrate) {
        try {
          if (isUrgent) {
            navigator.vibrate([200, 100, 200, 100, 300]);
          } else {
            navigator.vibrate([200, 150, 200]);
          }
        } catch (_) {}
      }
    }

    // 2. Audio Chime
    try {
      if (isUrgent) {
        soundEffects.playWorkoutStartTone();
      } else {
        soundEffects.playGpsConnectedTone();
      }
    } catch (_) {}

    // 3. Spoken voice announcement
    if (
      this.alertMode === 'VOICE_AND_VIBRATION' ||
      this.alertMode === 'VOICE_ONLY'
    ) {
      OfflineVoiceAi.speak(speechText);
    }
  }

  public getState(): ActiveWorkoutState | null {
    if (!this.workout) return null;
    const currentPhase = this.workout.phases[this.currentPhaseIndex] || this.workout.phases[0];

    let progressPercent = 0;
    let metricDisplay = '';
    let remainingSec: number | undefined = undefined;

    if (currentPhase.triggerType === 'TIME' || !currentPhase.triggerType) {
      const dur = currentPhase.durationSec || 60;
      remainingSec = Math.max(0, dur - this.phaseElapsedSec);
      progressPercent = Math.min(100, (this.phaseElapsedSec / dur) * 100);
      const min = Math.floor(remainingSec / 60);
      const sec = remainingSec % 60;
      metricDisplay = `${min}m ${sec < 10 ? '0' : ''}${sec}s restantes`;
    } else if (currentPhase.triggerType === 'LAPS') {
      const target = currentPhase.targetLaps || 1;
      progressPercent = Math.min(100, (this.lapsCompletedInCurrentPhase / target) * 100);
      metricDisplay = `Vuelta ${this.lapsCompletedInCurrentPhase} / ${target}`;
    } else if (currentPhase.triggerType === 'DISTANCE') {
      const targetDist = currentPhase.targetDistanceM || 100;
      const covered = Math.max(0, this.currentDistanceM - this.phaseStartDistanceM);
      progressPercent = Math.min(100, (covered / targetDist) * 100);
      metricDisplay = `${Math.round(covered)}m / ${targetDist}m`;
    } else if (currentPhase.triggerType === 'SPEED_TRIGGER') {
      const threshold = currentPhase.triggerSpeedKmh || 8.0;
      progressPercent = 50;
      metricDisplay = `Esperando > ${threshold} km/h`;
    }

    return {
      workoutId: this.workout.id,
      workoutName: this.workout.name,
      currentPhaseIndex: this.currentPhaseIndex,
      currentPhaseName: currentPhase.name,
      currentPhaseType: currentPhase.type,
      triggerType: currentPhase.triggerType || 'TIME',
      phaseElapsedSec: this.phaseElapsedSec,
      phaseDurationSec: currentPhase.durationSec,
      phaseRemainingSec: remainingSec,
      phaseProgressPercent: progressPercent,
      phaseMetricDisplay: metricDisplay,
      lapsCompletedInPhase: this.lapsCompletedInCurrentPhase,
      targetLapsInPhase: currentPhase.targetLaps,
      distanceCoveredInPhaseM: Math.max(0, this.currentDistanceM - this.phaseStartDistanceM),
      targetDistanceInPhaseM: currentPhase.targetDistanceM,
      waitingForSpeedKmh: currentPhase.triggerSpeedKmh,
      currentCycle: this.currentCycle,
      totalCycles: this.workout.repeatCycles,
      isCompleted: this.isCompleted,
    };
  }

  public getStatusDescription(): string {
    if (!this.workout) return 'No hay ningún entrenamiento por fases activo.';
    if (this.isCompleted) return 'El entrenamiento por fases ya ha finalizado.';

    const state = this.getState();
    if (!state) return '';

    return `Fase actual: ${state.currentPhaseName} (${state.phaseMetricDisplay}). Serie ${state.currentCycle} de ${state.totalCycles}.`;
  }
}
