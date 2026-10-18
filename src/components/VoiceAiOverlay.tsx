import React, { useState, useEffect, useRef } from 'react';
import { TrackSnapshot, BleHrState } from '../types/openkayak';
import { OfflineVoiceAi, VoiceAiResult } from '../services/offlineVoiceAi';

interface VoiceAiOverlayProps {
  snapshot: TrackSnapshot;
  ble: BleHrState;
  onStartSession: () => void;
  onPauseResumeSession: () => void;
  onFinishSession: () => void;
  onSaveBuoy: (isTurn: boolean) => void;
  onWakeActiveChange?: (active: boolean) => void;
}

export const VoiceAiOverlay: React.FC<VoiceAiOverlayProps> = ({
  snapshot,
  ble,
  onStartSession,
  onPauseResumeSession,
  onFinishSession,
  onSaveBuoy,
  onWakeActiveChange,
}) => {
  // Voice listening states
  const [isMicEnabled, setIsMicEnabled] = useState(true);
  const [isWakeActive, setIsWakeActive] = useState(false); // Rainbow border active
  const [bubbleText, setBubbleText] = useState('Di lo que quieras');
  const [isAnswering, setIsAnswering] = useState(false);
  const [aiAnswer, setAiAnswer] = useState<string | null>(null);
  const [micStatus, setMicStatus] = useState<string>('Escuchando "hola reloj"…');

  const recognitionRef = useRef<any>(null);
  const wakeTimeoutRef = useRef<any>(null);
  const silenceTimerRef = useRef<any>(null);

  // Initialize Speech Recognition
  useEffect(() => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      setMicStatus('Reconocimiento por voz no soportado por este navegador. Use el simulador.');
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognition.continuous = true;
      recognition.interimResults = true;
      recognition.lang = 'es-ES';

      recognition.onstart = () => {
        setMicStatus('Escuchando "hola reloj"…');
      };

      recognition.onerror = (event: any) => {
        if (event.error === 'not-allowed') {
          setMicStatus('Permiso de micrófono denegado. Use los botones de simulación.');
          setIsMicEnabled(false);
        } else if (event.error !== 'no-speech') {
          setMicStatus(`Micrófono: ${event.error}`);
        }
      };

      recognition.onend = () => {
        // Auto-restart if microphone is enabled
        if (isMicEnabled) {
          try {
            recognition.start();
          } catch (_) {}
        }
      };

      recognition.onresult = (event: any) => {
        let interimTranscript = '';
        let finalTranscript = '';

        for (let i = event.resultIndex; i < event.results.length; ++i) {
          const transcript = event.results[i][0].transcript;
          if (event.results[i].isFinal) {
            finalTranscript += transcript;
          } else {
            interimTranscript += transcript;
          }
        }

        const currentSpeech = (finalTranscript || interimTranscript).trim();

        // Check for wake word "hola reloj"
        if (!isWakeActive) {
          if (OfflineVoiceAi.containsWakeWord(currentSpeech)) {
            triggerWakeState();
            const remainder = OfflineVoiceAi.extractCommand(currentSpeech);
            if (remainder.length > 2) {
              setBubbleText(remainder);
              scheduleCommandExecution(remainder);
            }
          }
        } else {
          // Already listening to command: fill the bubble dynamically
          const cmd = OfflineVoiceAi.extractCommand(currentSpeech);
          if (cmd.length > 0) {
            setBubbleText(cmd);
            scheduleCommandExecution(cmd);
          }
        }
      };

      recognitionRef.current = recognition;

      if (isMicEnabled) {
        try {
          recognition.start();
        } catch (_) {}
      }
    } catch (e: any) {
      setMicStatus(`Error inicializando voz: ${e.message}`);
    }

    return () => {
      if (recognitionRef.current) {
        try {
          recognitionRef.current.stop();
        } catch (_) {}
      }
      if (wakeTimeoutRef.current) clearTimeout(wakeTimeoutRef.current);
      if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
    };
  }, [isMicEnabled, isWakeActive, snapshot, ble]);

  const triggerWakeState = () => {
    setIsWakeActive(true);
    onWakeActiveChange?.(true);
    setBubbleText('Di lo que quieras');
    setAiAnswer(null);
    setIsAnswering(false);

    // Audio cue / haptic
    if (wakeTimeoutRef.current) clearTimeout(wakeTimeoutRef.current);
    wakeTimeoutRef.current = setTimeout(() => {
      // Auto close after 10s if no command spoken
      closeWakeState();
    }, 10000);
  };

  const closeWakeState = () => {
    setIsWakeActive(false);
    onWakeActiveChange?.(false);
    setBubbleText('Di lo que quieras');
    setAiAnswer(null);
    setIsAnswering(false);
  };

  const scheduleCommandExecution = (command: string) => {
    if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
    silenceTimerRef.current = setTimeout(() => {
      executeCommand(command);
    }, 1400); // 1.4s of silence after speaking executes command
  };

  const executeCommand = (command: string) => {
    if (!command.trim()) return;

    setIsAnswering(true);
    const result: VoiceAiResult = OfflineVoiceAi.processQuery(command, snapshot, ble);

    setAiAnswer(result.responseSpanish);

    // Speak response out loud in offline Spanish
    OfflineVoiceAi.speak(result.responseSpanish);

    // Execute workout action if required
    if (result.actionRequired === 'start') {
      onStartSession();
    } else if (result.actionRequired === 'pause') {
      if (!snapshot.paused) onPauseResumeSession();
    } else if (result.actionRequired === 'resume') {
      if (snapshot.paused) onPauseResumeSession();
    } else if (result.actionRequired === 'finish') {
      onFinishSession();
    } else if (result.actionRequired === 'mark_buoy') {
      onSaveBuoy(true);
    }

    // Keep visible for 5.5s so user reads/hears response, then return to standby
    if (wakeTimeoutRef.current) clearTimeout(wakeTimeoutRef.current);
    wakeTimeoutRef.current = setTimeout(() => {
      closeWakeState();
    }, 5500);
  };

  // Simulation handler for easy testing of any command
  const simulateCommand = (cmdText: string) => {
    triggerWakeState();
    setBubbleText('');

    // Animate typing into the bubble to simulate voice streaming
    let i = 0;
    const interval = setInterval(() => {
      if (i <= cmdText.length) {
        setBubbleText(cmdText.slice(0, i));
        i++;
      } else {
        clearInterval(interval);
        setTimeout(() => {
          executeCommand(cmdText);
        }, 500);
      }
    }, 35);
  };

  return (
    <>
      {/* Floating Voice Bubble at the bottom of the watch */}
      {isWakeActive && (
        <div className="absolute bottom-6 left-1/2 -translate-x-1/2 w-[85%] max-w-[280px] z-30 transition-all duration-300">
          <div className="bg-[#05090D]/95 border-2 border-[#76F4D0] rounded-2xl p-2.5 shadow-2xl backdrop-blur-md text-center flex flex-col items-center">
            {/* Wear OS 5 Audio level visualizer in pure athletic green */}
            <div className="flex items-center gap-1 mb-1.5 h-3">
              <span className="w-1 bg-[#76F4D0] rounded-full animate-bounce h-2" />
              <span className="w-1 bg-[#00FF88] rounded-full animate-bounce h-3 delay-75" />
              <span className="w-1 bg-[#76F4D0] rounded-full animate-bounce h-3.5 delay-150" />
              <span className="w-1 bg-[#00FF88] rounded-full animate-bounce h-2 delay-100" />
              <span className="w-1 bg-[#76F4D0] rounded-full animate-bounce h-2.5 delay-200" />
            </div>

            {/* Bubble text: "Di lo que quieras" filling in dynamically with user speech */}
            <div className="text-white font-extrabold text-[10px] tracking-wide leading-tight min-h-[16px] px-1">
              {bubbleText || 'Di lo que quieras'}
            </div>

            {/* AI Answer bubble if processed */}
            {aiAnswer && (
              <div className="mt-2 pt-1.5 border-t border-[#24313C] text-[#76F4D0] font-semibold text-[9px] leading-tight">
                {aiAnswer}
              </div>
            )}
          </div>
        </div>
      )}

      {/* 3. Voice Bar at the top of the watch screen with status and trigger buttons */}
      <div className="absolute top-1 left-1/2 -translate-x-1/2 flex items-center gap-1.5 z-20">
        <button
          onClick={() => {
            if (isWakeActive) closeWakeState();
            else triggerWakeState();
          }}
          className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-[8px] font-bold border transition-all ${
            isWakeActive
              ? 'bg-[#FF42D9] text-black border-white shadow-lg animate-pulse'
              : 'bg-[#101820]/90 text-[#76F4D0] border-[#24313C] hover:bg-[#17232D]'
          }`}
          title="Escuchar 'Hola reloj' o pulsar para hablar"
        >
          <span className="text-[10px]">🎙️</span>
          <span>{isWakeActive ? '¡Te escucho!' : 'IA Voz'}</span>
        </button>
      </div>

      {/* 4. Quick Test Chips (simulates saying "hola reloj" + commands in 1 tap) */}
      <div className="absolute bottom-1.5 left-1/2 -translate-x-1/2 z-20 flex gap-1 opacity-70 hover:opacity-100 transition-opacity">
        <button
          onClick={() => simulateCommand('hola reloj ¿cuánta distancia llevo?')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#9FD9FF] hover:text-white"
        >
          "Distancia"
        </button>
        <button
          onClick={() => simulateCommand('hola reloj ¿qué velocidad llevo?')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#76F4D0] hover:text-white"
        >
          "Velocidad"
        </button>
        <button
          onClick={() => simulateCommand('hola reloj ¿cuántas paladas?')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#FFCA7A] hover:text-white"
        >
          "Paladas"
        </button>
        <button
          onClick={() => simulateCommand('hola reloj dime el pulso')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#FF8FA3] hover:text-white"
        >
          "Pulso"
        </button>
      </div>
    </>
  );
};
