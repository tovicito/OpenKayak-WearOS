import React, { useState, useEffect, useRef } from 'react';
import { TrackSnapshot, BleHrState, ActiveWorkoutState, Lap } from '../types/openkayak';
import { OfflineVoiceAi, VoiceAiResult } from '../services/offlineVoiceAi';

interface VoiceAiOverlayProps {
  snapshot: TrackSnapshot;
  ble: BleHrState;
  activeWorkoutState?: ActiveWorkoutState | null;
  laps?: Lap[];
  onStartSession: () => void;
  onPauseResumeSession: () => void;
  onFinishSession: () => void;
  onSaveBuoy: (isTurn: boolean) => void;
  onTriggerLap?: () => void;
  onWakeActiveChange?: (active: boolean) => void;
}

export const VoiceAiOverlay: React.FC<VoiceAiOverlayProps> = ({
  snapshot,
  ble,
  activeWorkoutState,
  laps,
  onStartSession,
  onPauseResumeSession,
  onFinishSession,
  onSaveBuoy,
  onTriggerLap,
  onWakeActiveChange,
}) => {
  // Voice listening states
  const [isMicEnabled, setIsMicEnabled] = useState(true);
  const [isWakeActive, setIsWakeActive] = useState(false);
  const [bubbleText, setBubbleText] = useState('Di lo que quieras');
  const [isAnswering, setIsAnswering] = useState(false);
  const [aiAnswer, setAiAnswer] = useState<string | null>(null);
  const [micStatus, setMicStatus] = useState<string>('Escuchando "hola reloj"…');
  const [liveVolume, setLiveVolume] = useState<number>(0);

  const snapshotRef = useRef(snapshot);
  snapshotRef.current = snapshot;
  const bleRef = useRef(ble);
  bleRef.current = ble;
  const activeWorkoutStateRef = useRef(activeWorkoutState);
  activeWorkoutStateRef.current = activeWorkoutState;
  const lapsRef = useRef(laps);
  lapsRef.current = laps;
  const isWakeActiveRef = useRef(isWakeActive);
  isWakeActiveRef.current = isWakeActive;

  const recognitionRef = useRef<any>(null);
  const wakeTimeoutRef = useRef<any>(null);
  const silenceTimerRef = useRef<any>(null);

  // Web Audio Context for Real-Time Microphone Waveform
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);
  const micStreamRef = useRef<MediaStream | null>(null);
  const animationFrameRef = useRef<number | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  // Initialize Speech Recognition (Permamente y continuo)
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
        setMicStatus('Escuchando "hola reloj" en segundo plano…');
      };

      recognition.onerror = (event: any) => {
        if (event.error === 'not-allowed') {
          setMicStatus('Permiso de micrófono denegado. Use los botones de simulación.');
          setIsMicEnabled(false);
        } else if (event.error !== 'no-speech') {
          // Restart on transient errors
          try {
            recognition.start();
          } catch (_) {}
        }
      };

      recognition.onend = () => {
        // Auto-restart immediately to keep hotword detection always listening
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
        if (!isWakeActiveRef.current) {
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
  }, [isMicEnabled]);

  // Connect Real Microphone to AnalyserNode when wake state is active
  useEffect(() => {
    if (!isWakeActive) {
      stopAudioVisualizer();
      return;
    }

    startAudioVisualizer();

    return () => {
      stopAudioVisualizer();
    };
  }, [isWakeActive]);

  const startAudioVisualizer = async () => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true },
      });
      micStreamRef.current = stream;

      const AudioContextClass = window.AudioContext || (window as any).webkitAudioContext;
      const audioCtx = new AudioContextClass();
      audioContextRef.current = audioCtx;

      const analyser = audioCtx.createAnalyser();
      analyser.fftSize = 128;
      analyser.smoothingTimeConstant = 0.8;
      analyserRef.current = analyser;

      const source = audioCtx.createMediaStreamSource(stream);
      source.connect(analyser);

      drawWaveform();
    } catch (_) {
      // If hardware mic permission not granted or in iframe, run synthetic wave
      drawSyntheticWaveform();
    }
  };

  const stopAudioVisualizer = () => {
    if (animationFrameRef.current) {
      cancelAnimationFrame(animationFrameRef.current);
      animationFrameRef.current = null;
    }

    if (micStreamRef.current) {
      micStreamRef.current.getTracks().forEach((track) => track.stop());
      micStreamRef.current = null;
    }

    if (audioContextRef.current && audioContextRef.current.state !== 'closed') {
      audioContextRef.current.close().catch(() => {});
      audioContextRef.current = null;
    }
  };

  // Real-time canvas wave drawing from microphone input
  const drawWaveform = () => {
    const canvas = canvasRef.current;
    const analyser = analyserRef.current;
    if (!canvas || !analyser) {
      animationFrameRef.current = requestAnimationFrame(drawWaveform);
      return;
    }

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const bufferLength = analyser.frequencyBinCount;
    const timeData = new Uint8Array(bufferLength);
    const freqData = new Uint8Array(bufferLength);

    const render = () => {
      animationFrameRef.current = requestAnimationFrame(render);

      analyser.getByteTimeDomainData(timeData);
      analyser.getByteFrequencyData(freqData);

      // Calculate average volume level
      let sum = 0;
      for (let i = 0; i < bufferLength; i++) {
        sum += freqData[i];
      }
      const avgVolume = sum / bufferLength;
      setLiveVolume(avgVolume);

      const width = canvas.width;
      const height = canvas.height;

      ctx.clearRect(0, 0, width, height);

      // Base central guide line
      ctx.strokeStyle = 'rgba(118, 244, 208, 0.15)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, height / 2);
      ctx.lineTo(width, height / 2);
      ctx.stroke();

      // 1. Primary Smooth Wave (Pure Wear OS Green #76F4D0 / #00FF88)
      ctx.lineWidth = 2.5;
      ctx.strokeStyle = '#76F4D0';
      ctx.shadowColor = '#00FF88';
      ctx.shadowBlur = 8;
      ctx.beginPath();

      const sliceWidth = width / (bufferLength - 1);
      let x = 0;

      for (let i = 0; i < bufferLength; i++) {
        const v = timeData[i] / 128.0;
        // Amplify wave height based on live audio intensity
        const y = (v * height) / 2;

        if (i === 0) {
          ctx.moveTo(x, y);
        } else {
          const prevX = x - sliceWidth;
          const prevV = timeData[i - 1] / 128.0;
          const prevY = (prevV * height) / 2;
          const midX = (prevX + x) / 2;
          const midY = (prevY + y) / 2;
          ctx.quadraticCurveTo(prevX, prevY, midX, midY);
        }

        x += sliceWidth;
      }
      ctx.lineTo(width, height / 2);
      ctx.stroke();

      // 2. Secondary Harmonic Wave (mirrored harmonic for depth)
      ctx.lineWidth = 1.2;
      ctx.strokeStyle = 'rgba(0, 255, 136, 0.65)';
      ctx.shadowBlur = 4;
      ctx.beginPath();
      x = 0;

      for (let i = 0; i < bufferLength; i += 2) {
        const freqAmp = (freqData[i] / 255.0) * (height * 0.4);
        const yOffset = Math.sin((i / bufferLength) * Math.PI * 4 + Date.now() * 0.006) * freqAmp;
        const y = height / 2 + yOffset;

        if (i === 0) {
          ctx.moveTo(x, y);
        } else {
          ctx.lineTo(x, y);
        }
        x += sliceWidth * 2;
      }
      ctx.stroke();
    };

    render();
  };

  // Synthetic responsive wave when direct mic permissions aren't accessible
  const drawSyntheticWaveform = () => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let phase = 0;

    const render = () => {
      animationFrameRef.current = requestAnimationFrame(render);
      phase += 0.08;

      const width = canvas.width;
      const height = canvas.height;
      ctx.clearRect(0, 0, width, height);

      // Live volume estimation while words are appearing
      const simulatedVol = bubbleText !== 'Di lo que quieras' ? 38 : 12;
      setLiveVolume(simulatedVol);

      ctx.lineWidth = 2.5;
      ctx.strokeStyle = '#76F4D0';
      ctx.shadowColor = '#00FF88';
      ctx.shadowBlur = 8;
      ctx.beginPath();

      const points = 40;
      const step = width / points;

      for (let i = 0; i <= points; i++) {
        const x = i * step;
        const envelope = Math.sin((i / points) * Math.PI); // Pinches ends to zero
        const y =
          height / 2 +
          Math.sin(i * 0.4 + phase) *
            envelope *
            (simulatedVol > 20 ? 12 : 5) *
            Math.cos(phase * 0.5);

        if (i === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      ctx.stroke();
    };

    render();
  };

  const triggerWakeState = () => {
    setIsWakeActive(true);
    onWakeActiveChange?.(true);
    setBubbleText('Di lo que quieras');
    setAiAnswer(null);
    setIsAnswering(false);

    if (wakeTimeoutRef.current) clearTimeout(wakeTimeoutRef.current);
    wakeTimeoutRef.current = setTimeout(() => {
      closeWakeState();
    }, 10000);
  };

  const closeWakeState = () => {
    setIsWakeActive(false);
    onWakeActiveChange?.(false);
    setBubbleText('Di lo que quieras');
    setAiAnswer(null);
    setIsAnswering(false);
    stopAudioVisualizer();
  };

  const scheduleCommandExecution = (command: string) => {
    if (silenceTimerRef.current) clearTimeout(silenceTimerRef.current);
    silenceTimerRef.current = setTimeout(() => {
      executeCommand(command);
    }, 1400);
  };

  const executeCommand = (command: string) => {
    if (!command.trim()) return;

    setIsAnswering(true);
    const currentSnap = snapshotRef.current;
    const currentBle = bleRef.current;
    const currentWorkout = activeWorkoutStateRef.current;
    const currentLaps = lapsRef.current;

    const result: VoiceAiResult = OfflineVoiceAi.processQuery(
      command,
      currentSnap,
      currentBle,
      currentWorkout,
      currentLaps
    );

    setAiAnswer(result.responseSpanish);

    // Speak response out loud in offline Spanish
    OfflineVoiceAi.speak(result.responseSpanish);

    if (result.actionRequired === 'start') {
      onStartSession();
    } else if (result.actionRequired === 'pause') {
      if (!currentSnap.paused) onPauseResumeSession();
    } else if (result.actionRequired === 'resume') {
      if (currentSnap.paused) onPauseResumeSession();
    } else if (result.actionRequired === 'finish') {
      onFinishSession();
    } else if (result.actionRequired === 'mark_buoy') {
      onSaveBuoy(true);
    } else if (result.actionRequired === 'trigger_lap') {
      onTriggerLap?.();
    }

    if (wakeTimeoutRef.current) clearTimeout(wakeTimeoutRef.current);
    wakeTimeoutRef.current = setTimeout(() => {
      closeWakeState();
    }, 5500);
  };

  const simulateCommand = (cmdText: string) => {
    triggerWakeState();
    setBubbleText('');

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

  // Frequency wave bars calculated from live volume
  const barHeights = [
    Math.min(18, Math.max(3, liveVolume * 0.15)),
    Math.min(22, Math.max(4, liveVolume * 0.3)),
    Math.min(26, Math.max(5, liveVolume * 0.5)),
    Math.min(28, Math.max(6, liveVolume * 0.7)),
    Math.min(26, Math.max(5, liveVolume * 0.5)),
    Math.min(22, Math.max(4, liveVolume * 0.3)),
    Math.min(18, Math.max(3, liveVolume * 0.15)),
  ];

  return (
    <>
      {/* Floating Voice Bubble at the bottom of the watch */}
      {isWakeActive && (
        <div className="absolute bottom-5 left-1/2 -translate-x-1/2 w-[88%] max-w-[280px] z-30 transition-all duration-300">
          <div className="bg-[#05090D]/95 border-2 border-[#76F4D0] rounded-2xl p-2.5 shadow-2xl backdrop-blur-md text-center flex flex-col items-center">
            {/* Live Real-Time Microphone Sound Wave Visualizer */}
            <div className="w-full flex flex-col items-center mb-1">
              {/* Canvas continuous live sinusoidal wave */}
              <div className="w-full h-7 relative flex items-center justify-center overflow-hidden rounded-lg bg-[#07111a]/80 border border-[#76F4D0]/20 mb-1.5">
                <canvas
                  ref={canvasRef}
                  width={220}
                  height={28}
                  className="w-full h-full block"
                />
              </div>

              {/* Symmetrical reactive Wear OS green sound bars */}
              <div className="flex items-center justify-center gap-1.5 h-4 mb-1">
                {barHeights.map((h, index) => (
                  <span
                    key={index}
                    className="w-1 rounded-full bg-gradient-to-t from-[#76F4D0] to-[#00FF88] transition-all duration-75"
                    style={{
                      height: `${h}px`,
                      boxShadow: '0 0 6px rgba(118, 244, 208, 0.7)',
                    }}
                  />
                ))}
              </div>

              {/* Real-time listening indicator */}
              <div className="flex items-center gap-1 text-[8px] font-bold text-[#76F4D0]">
                <span className="w-1.5 h-1.5 rounded-full bg-[#00FF88] animate-ping" />
                <span className="tracking-wider uppercase">
                  {bubbleText === 'Di lo que quieras' ? 'ESCUCHANDO EN VIVO' : 'TRANSCRIBIENDO VOZ'}
                </span>
              </div>
            </div>

            {/* Bubble text: "Di lo que quieras" filling dynamically as user speaks */}
            <div className="text-white font-extrabold text-[11px] tracking-wide leading-tight min-h-[16px] px-1 max-h-[48px] overflow-y-auto">
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

      {/* Voice Bar at the top of the watch screen with status and trigger buttons */}
      <div className="absolute top-1 left-1/2 -translate-x-1/2 flex items-center gap-1.5 z-20">
        <button
          onClick={() => {
            if (isWakeActive) closeWakeState();
            else triggerWakeState();
          }}
          className={`flex items-center gap-1 px-2 py-0.5 rounded-full text-[8px] font-bold border transition-all ${
            isWakeActive
              ? 'bg-[#76F4D0] text-[#05090D] border-white shadow-lg animate-pulse'
              : 'bg-[#101820]/90 text-[#76F4D0] border-[#24313C] hover:bg-[#17232D]'
          }`}
          title="Escuchar 'Hola reloj' o pulsar para hablar"
        >
          <span className="text-[10px]">🎙️</span>
          <span>{isWakeActive ? '¡Te escucho!' : 'IA Voz'}</span>
        </button>
      </div>

      {/* Quick Test Chips (simulates saying "hola reloj" + commands in 1 tap) */}
      <div className="absolute bottom-1.5 left-1/2 -translate-x-1/2 z-20 flex gap-1 opacity-70 hover:opacity-100 transition-opacity flex-wrap justify-center max-w-[280px]">
        <button
          onClick={() => simulateCommand('hola reloj ¿qué fase toca?')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#FF8FA3] hover:text-white"
        >
          "Fase"
        </button>
        <button
          onClick={() => simulateCommand('hola reloj ¿cuántas vueltas llevo?')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#FFCA7A] hover:text-white"
        >
          "Vueltas"
        </button>
        <button
          onClick={() => simulateCommand('hola reloj ¿qué velocidad llevo?')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#76F4D0] hover:text-white"
        >
          "Velocidad"
        </button>
        <button
          onClick={() => simulateCommand('hola reloj ¿cuántas paladas?')}
          className="px-1.5 py-0.5 rounded-full bg-[#101820]/80 border border-[#24313C] text-[7px] text-[#9FD9FF] hover:text-white"
        >
          "Paladas"
        </button>
      </div>
    </>
  );
};
