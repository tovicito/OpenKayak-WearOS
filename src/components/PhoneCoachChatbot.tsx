import React, { useState, useRef, useEffect } from 'react';
import { SessionSummary, AthleteProfile } from '../types/openkayak';

interface ChatMessage {
  id: string;
  sender: 'user' | 'coach';
  text: string;
  time: string;
  source?: string;
}

interface PhoneCoachChatbotProps {
  workouts: SessionSummary[];
  athleteProfile?: AthleteProfile;
}

const DEFAULT_SUGGESTIONS = [
  '🏆 Dime mis mejores resultados en los últimos 10 días',
  '⚡ ¿Cuál ha sido mi vuelta más rápida y mejor ritmo?',
  '📈 Análisis de mi cadencia de palada y deslizamiento',
  '📋 Proponme un plan de series para hoy',
];

export const PhoneCoachChatbot: React.FC<PhoneCoachChatbotProps> = ({
  workouts,
  athleteProfile,
}) => {
  const [messages, setMessages] = useState<ChatMessage[]>(() => [
    {
      id: 'welcome',
      sender: 'coach',
      text: '¡Hola palista! 🚣 Soy tu entrenador de piragüismo impulsado por Gemini (Google AI Studio). Tengo acceso a todos tus entrenamientos, vueltas registradas, cadencia y ritmo en el agua. ¿De qué quieres hablar hoy? Puedes preguntarme por tus mejores resultados en los últimos 10 días o pedirme análisis táctico.',
      time: new Date().toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' }),
      source: 'gemini-3.8-flash',
    },
  ]);
  const [inputText, setInputText] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isListeningMic, setIsListeningMic] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement | null>(null);

  // Auto-scroll to bottom of messages
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, isLoading]);

  const sendMessage = async (textToSend: string) => {
    const trimmed = textToSend.trim();
    if (!trimmed || isLoading) return;

    const userMsg: ChatMessage = {
      id: `u-${Date.now()}`,
      sender: 'user',
      text: trimmed,
      time: new Date().toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' }),
    };

    setMessages((prev) => [...prev, userMsg]);
    setInputText('');
    setIsLoading(true);

    try {
      const response = await fetch('/api/gemini/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: trimmed,
          history: messages.slice(-6).map((m) => ({
            role: m.sender === 'user' ? 'user' : 'model',
            text: m.text,
          })),
          workouts,
          athleteProfile,
        }),
      });

      if (!response.ok) {
        throw new Error(`HTTP error ${response.status}`);
      }

      const data = await response.json();
      const coachMsg: ChatMessage = {
        id: `c-${Date.now()}`,
        sender: 'coach',
        text: data.reply || 'He recibido tus datos. ¡Sigue con ese nivel de entrega en el kayak!',
        time: new Date().toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' }),
        source: data.source,
      };

      setMessages((prev) => [...prev, coachMsg]);
    } catch (err: any) {
      const errorMsg: ChatMessage = {
        id: `err-${Date.now()}`,
        sender: 'coach',
        text: 'No he podido contactar con el servicio en este momento. Inténtalo de nuevo en unos segundos.',
        time: new Date().toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' }),
      };
      setMessages((prev) => [...prev, errorMsg]);
    } finally {
      setIsLoading(false);
    }
  };

  const handleMicClick = () => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;

    if (!SpeechRecognition) {
      alert('Tu navegador no soporta entrada de voz por SpeechRecognition.');
      return;
    }

    if (isListeningMic) {
      setIsListeningMic(false);
      return;
    }

    try {
      const recognition = new SpeechRecognition();
      recognition.lang = 'es-ES';
      recognition.continuous = false;
      recognition.interimResults = false;

      recognition.onstart = () => {
        setIsListeningMic(true);
      };

      recognition.onresult = (e: any) => {
        const spoken = e.results[0][0].transcript;
        if (spoken) {
          setInputText(spoken);
          sendMessage(spoken);
        }
      };

      recognition.onerror = () => {
        setIsListeningMic(false);
      };

      recognition.onend = () => {
        setIsListeningMic(false);
      };

      recognition.start();
    } catch (_) {
      setIsListeningMic(false);
    }
  };

  // Compute 10-day summary stats for top badge
  const tenDaysAgo = Date.now() - 10 * 86400 * 1000;
  const recent10 = workouts.filter((w) => (w.startAt || 0) >= tenDaysAgo);
  const totalRecentDistKm = (
    recent10.reduce((acc, w) => acc + (w.distanceM || 0), 0) / 1000
  ).toFixed(1);

  return (
    <div className="flex flex-col h-[650px] bg-[#0A121C] rounded-2xl border border-[#1C2C3D] overflow-hidden shadow-2xl">
      {/* Top Header */}
      <div className="bg-[#0f1b29] border-b border-[#1f3044] px-4 py-3 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-2xl bg-gradient-to-tr from-[#76F4D0] via-[#5FB8FF] to-[#BD7BFF] p-0.5 flex items-center justify-center shadow-lg shadow-[#76F4D0]/10">
            <div className="w-full h-full bg-[#0c1825] rounded-[14px] flex items-center justify-center text-lg">
              🚣
            </div>
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-white font-extrabold text-sm sm:text-base flex items-center gap-1.5">
                Coach IA OpenKayak
                <span className="text-[10px] font-bold px-1.5 py-0.5 rounded-full bg-gradient-to-r from-[#76F4D0]/20 to-[#5FB8FF]/20 text-[#76F4D0] border border-[#76F4D0]/40">
                  ✨ Gemini
                </span>
              </h3>
            </div>
            <p className="text-[#8D9DA8] text-[11px] flex items-center gap-2">
              <span>{recent10.length} entrenos en los últimos 10 días ({totalRecentDistKm} km)</span>
              <span className="w-1.5 h-1.5 rounded-full bg-[#76F4D0] animate-pulse"></span>
            </p>
          </div>
        </div>

        <button
          onClick={() => {
            setMessages([
              {
                id: 'welcome-reset',
                sender: 'coach',
                text: 'Conversación reiniciada. ¿Qué consulta o análisis necesitas sobre tus sesiones de piragüismo?',
                time: new Date().toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' }),
              },
            ]);
          }}
          className="text-[#8D9DA8] hover:text-white text-xs px-2.5 py-1 rounded-lg hover:bg-[#1a2839] transition-all"
          title="Reiniciar chat"
        >
          Limpiar
        </button>
      </div>

      {/* Suggestion Chips */}
      <div className="bg-[#0c1724] px-3 py-2 border-b border-[#182635] flex items-center gap-1.5 overflow-x-auto no-scrollbar">
        <span className="text-[10px] uppercase font-bold text-[#5e778f] shrink-0 mr-1">
          Sugerencias:
        </span>
        {DEFAULT_SUGGESTIONS.map((sug, i) => (
          <button
            key={i}
            onClick={() => sendMessage(sug.replace(/^[^\w¿]+/, ''))}
            className="text-[11px] font-medium px-2.5 py-1 rounded-full bg-[#152332] text-[#9FD9FF] hover:bg-[#1E3348] hover:text-white border border-[#23374c] shrink-0 transition-all active:scale-95"
          >
            {sug}
          </button>
        ))}
      </div>

      {/* Message List */}
      <div className="flex-1 p-4 overflow-y-auto space-y-3.5 bg-gradient-to-b from-[#0a121c] to-[#080d14]">
        {messages.map((m) => (
          <div
            key={m.id}
            className={`flex flex-col ${
              m.sender === 'user' ? 'items-end' : 'items-start'
            }`}
          >
            <div className="flex items-end gap-2 max-w-[85%] sm:max-w-[75%]">
              {m.sender === 'coach' && (
                <div className="w-6 h-6 rounded-full bg-[#152435] border border-[#76F4D0]/30 flex items-center justify-center text-xs shrink-0 mb-1">
                  🚣
                </div>
              )}

              <div
                className={`p-3 rounded-2xl text-xs sm:text-sm leading-relaxed ${
                  m.sender === 'user'
                    ? 'bg-[#194060] text-white rounded-br-none border border-[#28608f] shadow-md'
                    : 'bg-[#111e2c] text-[#DCE6EE] rounded-bl-none border border-[#1f3348] shadow-md whitespace-pre-line'
                }`}
              >
                {m.text}

                {m.source && (
                  <div className="mt-1 pt-1 border-t border-[#1a2d40] text-[9px] text-[#76F4D0]/80 font-mono flex items-center gap-1">
                    <span>✨ {m.source}</span>
                  </div>
                )}
              </div>
            </div>

            <span className="text-[10px] text-[#5e778f] px-1 mt-1 font-mono">
              {m.time}
            </span>
          </div>
        ))}

        {isLoading && (
          <div className="flex items-end gap-2 max-w-[75%]">
            <div className="w-6 h-6 rounded-full bg-[#152435] border border-[#76F4D0]/30 flex items-center justify-center text-xs shrink-0">
              🚣
            </div>
            <div className="p-3 rounded-2xl rounded-bl-none bg-[#111e2c] border border-[#1f3348] text-xs text-[#76F4D0] flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-[#76F4D0] animate-bounce"></span>
              <span
                className="w-2 h-2 rounded-full bg-[#76F4D0] animate-bounce"
                style={{ animationDelay: '0.2s' }}
              ></span>
              <span
                className="w-2 h-2 rounded-full bg-[#76F4D0] animate-bounce"
                style={{ animationDelay: '0.4s' }}
              ></span>
              <span className="text-[11px] text-[#8D9DA8] ml-1">
                Consultando a Gemini con tus entrenos...
              </span>
            </div>
          </div>
        )}

        <div ref={messagesEndRef} />
      </div>

      {/* Bottom Input Area */}
      <div className="bg-[#0e1926] p-3 border-t border-[#1c2c3d]">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            sendMessage(inputText);
          }}
          className="flex items-center gap-2"
        >
          <button
            type="button"
            onClick={handleMicClick}
            className={`p-2.5 rounded-xl border transition-all ${
              isListeningMic
                ? 'bg-red-500/20 text-red-400 border-red-500 animate-pulse'
                : 'bg-[#152332] text-[#76F4D0] border-[#22364b] hover:bg-[#1d3044]'
            }`}
            title="Hablar por voz"
          >
            🎤
          </button>

          <input
            type="text"
            value={inputText}
            onChange={(e) => setInputText(e.target.value)}
            placeholder="Pregunta a tu entrenador: 'Dime mis mejores resultados en los últimos 10 días'…"
            disabled={isLoading}
            className="flex-1 bg-[#09111a] border border-[#203247] rounded-xl px-3.5 py-2.5 text-xs sm:text-sm text-white placeholder-[#5e778f] focus:outline-none focus:border-[#76F4D0]"
          />

          <button
            type="submit"
            disabled={!inputText.trim() || isLoading}
            className="px-4 py-2.5 rounded-xl bg-gradient-to-r from-[#76F4D0] to-[#5FB8FF] text-[#05090D] font-extrabold text-xs sm:text-sm hover:opacity-90 transition-all disabled:opacity-40 disabled:cursor-not-allowed shadow-md"
          >
            Enviar
          </button>
        </form>
      </div>
    </div>
  );
};
