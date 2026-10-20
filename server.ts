import express from 'express';
import cors from 'cors';
import path from 'path';
import { fileURLToPath } from 'url';
import { GoogleGenAI } from '@google/genai';
import { parseWorkoutOffline } from './src/services/naturalLanguageWorkoutParser.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = Number(process.env.PORT) || 3000;

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use('/downloads', express.static(path.join(__dirname, 'public', 'downloads')));
app.use(express.static(path.join(__dirname, 'public')));

// Initialize Gemini client according to the gemini-api skill instructions
const apiKey = process.env.GEMINI_API_KEY || '';
const ai = apiKey
  ? new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          'User-Agent': 'aistudio-build',
        },
      },
    })
  : null;

interface WorkoutAnalysisRequest {
  elapsedMs: number;
  distanceM: number;
  strokes: number;
  averageBpm?: number;
  maxBpm?: number;
  minBpm?: number;
  calories?: number;
  cadenceSpm?: number;
  speedKmhAvg?: number;
  speedKmhMax?: number;
  turnsCount?: number;
  athleteWeightKg?: number;
  speedUnit?: string;
}

// Endpoint to analyze kayaking workout with Gemini (gemini-3.8-flash)
app.post('/api/gemini/analyze-workout', async (req, res) => {
  const workout: WorkoutAnalysisRequest = req.body;
  const elapsedMin = Math.max(0.1, (workout.elapsedMs || 0) / 60000);
  const distKm = (workout.distanceM || 0) / 1000;
  const avgSpeedKmh = workout.speedKmhAvg || (distKm / (elapsedMin / 60));
  const strokes = workout.strokes || 0;
  const spm = workout.cadenceSpm || (strokes > 0 && elapsedMin > 0 ? Math.round(strokes / elapsedMin) : 0);
  const avgBpm = workout.averageBpm || 0;
  const maxBpm = workout.maxBpm || 0;
  const turns = workout.turnsCount || 0;
  const cals = workout.calories || 0;

  const prompt = `Actúa como un entrenador olímpico y analista técnico de élite de piragüismo en aguas tranquilas y kayak de travesía.
Analiza la siguiente sesión completada por el palista en OpenKayak:
- Tiempo total: ${elapsedMin.toFixed(1)} minutos (${Math.floor(elapsedMin)}m ${Math.round((elapsedMin % 1) * 60)}s)
- Distancia recorrida: ${distKm.toFixed(2)} km (${workout.distanceM} m)
- Velocidad media: ${avgSpeedKmh.toFixed(2)} km/h
- Paladas totales: ${strokes}
- Cadencia media: ${spm} paladas por minuto (spm)
- Frecuencia cardíaca media: ${avgBpm > 0 ? `${avgBpm} ppm (máxima: ${maxBpm} ppm)` : 'No registrada'}
- Calorías estimadas: ${cals} kcal
- Boyas / Virajes completados: ${turns}

Responde estrictamente con un objeto JSON con los siguientes campos:
1. "summaryPhrase": UNA SOLA FRASE impactante, técnica y motivadora que resuma el entreno (exactamente 1 frase clara en español para mostrar en grande en el reloj y móvil).
2. "headline": Un titular breve de 3 a 6 palabras que defina el entrenamiento (ej. "Gran sesión de resistencia aeróbica").
3. "technicalFeedback": Párrafo con análisis biomecánico de la cadencia de palada, deslizamiento del kayak y economía de esfuerzo.
4. "cardioFeedback": Párrafo evaluando el esfuerzo cardiovascular y zonas de pulsaciones si aplica.
5. "strengths": Array de 2 o 3 puntos fuertes destacados de la sesión.
6. "nextSteps": Array de 2 o 3 recomendaciones para la próxima sesión en el agua.
7. "score": Puntuación global del 1 al 10.`;

  try {
    if (ai) {
      const response = await ai.models.generateContent({
        model: 'gemini-3.8-flash',
        contents: prompt,
        config: {
          responseMimeType: 'application/json',
          systemInstruction: 'Eres un entrenador profesional de piragüismo. Tu misión es motivar al palista y darle consejos técnicos basados en datos reales de su reloj en cubierta.',
        },
      });

      const text = response.text || '{}';
      try {
        const parsed = JSON.parse(text);
        return res.json({
          source: 'gemini-3.8-flash',
          analysis: parsed,
        });
      } catch (parseErr) {
        return res.json({
          source: 'gemini-3.8-flash',
          analysis: {
            summaryPhrase: text.slice(0, 150),
            headline: 'Entrenamiento Analizado con IA',
            technicalFeedback: text,
            strengths: ['Sesión registrada con éxito'],
            nextSteps: ['Mantener la regularidad de palada'],
            score: 8.5,
          },
        });
      }
    } else {
      // Fallback coach evaluation if no GEMINI_API_KEY is configured in development
      const pacePerKm = avgSpeedKmh > 0 ? (60 / avgSpeedKmh) : 0;
      const paceMin = Math.floor(pacePerKm);
      const paceSec = Math.round((pacePerKm - paceMin) * 60);

      let summary = '';
      if (avgSpeedKmh > 11) {
        summary = '¡Ritmo demoledor de competición con excelente velocidad sostenida en el agua!';
      } else if (avgSpeedKmh > 8.5) {
        summary = `Sesión sólida y constante a ${avgSpeedKmh.toFixed(1)} km/h con magnífico deslizamiento por palada.`;
      } else {
        summary = `Entrenamiento de fondo completado con éxito, sumando ${distKm.toFixed(2)} km de volumen aeróbico clave.`;
      }

      return res.json({
        source: 'local-coach-rule',
        analysis: {
          summaryPhrase: summary,
          headline: `Entreno de ${distKm.toFixed(2)} km a ${avgSpeedKmh.toFixed(1)} km/h`,
          technicalFeedback: `Con una cadencia de ${spm} spm y ritmo promedio de ${paceMin}:${paceSec.toString().padStart(2, '0')} /km, has mantenido una relación equilibrada entre empuje y recuperación. La propulsión se muestra estable.`,
          cardioFeedback: avgBpm > 0
            ? `Tu frecuencia media de ${avgBpm} ppm indica un trabajo aeróbico bien controlado con pico en ${maxBpm} ppm.`
            : 'Conecta tu banda de pulso BLE en la próxima sesión para evaluar zonas de lactato y eficiencia cardiovascular.',
          strengths: [
            `Distancia acumulada de ${distKm.toFixed(2)} km`,
            `${strokes} paladas registradas con detección continua`,
            turns > 0 ? `${turns} giros técnicos en boyas` : 'Trayectoria limpia',
          ],
          nextSteps: [
            'Probar series de 500m aumentando 4 spm la cadencia',
            'Enfocar en la torsión del tronco en la fase de ataque',
          ],
          score: 8.8,
        },
      });
    }
  } catch (err: any) {
    console.error('Error invoking Gemini:', err);
    return res.status(500).json({
      error: 'Error analizando entrenamiento con Gemini',
      message: err.message,
    });
  }
});

// Endpoint for conversational kayak coach chatbot (Gemini 2.5/Flash / Google AI Studio)
app.post('/api/gemini/chat', async (req, res) => {
  const { message, history = [], workouts = [], athleteProfile = {} } = req.body;

  if (!message || typeof message !== 'string') {
    return res.status(400).json({ error: 'Mensaje requerido' });
  }

  // Filter workouts in last 10 days
  const now = Date.now();
  const tenDaysAgo = now - 10 * 86400 * 1000;
  const recent10Days = workouts.filter((w: any) => (w.startAt || 0) >= tenDaysAgo);
  const relevantWorkouts = recent10Days.length > 0 ? recent10Days : workouts.slice(0, 10);

  // Compute summary stats for the last 10 days
  let totalDistM = 0;
  let totalElapsedMs = 0;
  let totalStrokes = 0;
  let maxSpeedKmh = 0;
  let bestAvgSpeedKmh = 0;
  let bestCadenceSpm = 0;
  let allLaps: any[] = [];

  const workoutSummaries = relevantWorkouts.map((w: any, idx: number) => {
    const distKm = ((w.distanceM || 0) / 1000).toFixed(2);
    const durMin = Math.round((w.elapsedMs || 0) / 60000);
    const speed = (w.speedKmhAvg || (w.distanceM && w.elapsedMs ? (w.distanceM / 1000) / (w.elapsedMs / 3600000) : 0)).toFixed(1);
    const strokes = w.strokes || 0;
    const spm = durMin > 0 ? Math.round(strokes / durMin) : 0;
    const dateStr = new Date(w.startAt || now).toLocaleDateString('es-ES', {
      day: 'numeric',
      month: 'short',
    });

    totalDistM += w.distanceM || 0;
    totalElapsedMs += w.elapsedMs || 0;
    totalStrokes += strokes;
    if (Number(speed) > bestAvgSpeedKmh) bestAvgSpeedKmh = Number(speed);
    if (spm > bestCadenceSpm) bestCadenceSpm = spm;

    if (Array.isArray(w.laps) && w.laps.length > 0) {
      allLaps.push(...w.laps);
    }

    return `• Sesión #${idx + 1} (${dateStr}): ${distKm} km en ${durMin} min, vel media ${speed} km/h, ${strokes} paladas (~${spm} spm), FC media ${w.averageBpm || 'N/A'} ppm.`;
  });

  // Check best lap
  let bestLapDesc = 'No hay vueltas registradas aún';
  if (allLaps.length > 0) {
    const best = allLaps.reduce((b, c) => (c.durationMs < b.durationMs ? c : b), allLaps[0]);
    const min = Math.floor(best.durationMs / 60000);
    const sec = Math.round((best.durationMs % 60000) / 1000);
    bestLapDesc = `Vuelta #${best.lapNumber}: ${min}m ${sec}s (${best.distanceM}m a ${best.avgSpeedKmh} km/h, ${best.avgCadenceSpm} spm)`;
  }

  const contextData = `
DATOS DEL PALISTA Y HISTORIAL DE ENTRENAMIENTOS:
- Peso: ${athleteProfile.weightKg || 72} kg | Edad: ${athleteProfile.ageYears || 28} años
- Total sesiones en últimos 10 días: ${relevantWorkouts.length} sesiones
- Distancia total acumulada (últimos 10 días): ${(totalDistM / 1000).toFixed(2)} km
- Tiempo total en agua: ${Math.round(totalElapsedMs / 60000)} minutos
- Mejor velocidad media en una sesión: ${bestAvgSpeedKmh.toFixed(1)} km/h
- Cadencia máxima sostenida: ${bestCadenceSpm} spm
- Mejor vuelta automática registrada: ${bestLapDesc}
- Detalle de sesiones registradas:
${workoutSummaries.length > 0 ? workoutSummaries.join('\n') : '• No hay sesiones registradas en los últimos 10 días aún.'}
`;

  try {
    if (ai) {
      const systemInstruction = `Eres "Coach OpenKayak", un prestigioso entrenador olímpico de piragüismo (K1, K2, C1, pista y maratón).
Tu función es responder a las preguntas del palista basándote en los datos reales de sus entrenamientos registrados en el reloj y la app.
Responde de manera cercana, motivadora, técnica y concisa (en español de España o neutro).
Si te preguntan por sus mejores resultados en los últimos 10 días, resume claramente su mejor velocidad media, vuelta rápida, distancia total y cadencia con los datos exactos provistos en el contexto.
Da recomendaciones biomecánicas de cadencia, ritmo de carrera y recuperación cuando sea oportuno.`;

      const prompt = `${contextData}\n\nPregunta del deportista: "${message}"`;

      const response = await ai.models.generateContent({
        model: 'gemini-3.8-flash',
        contents: prompt,
        config: {
          systemInstruction,
          temperature: 0.7,
        },
      });

      return res.json({
        reply: response.text || 'Entendido. Sigue manteniendo la regularidad en el agua.',
        source: 'gemini-3.8-flash',
      });
    } else {
      // Local intelligent kayak coach fallback
      const norm = message.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '');

      let reply = '';
      if (
        norm.includes('10 dias') ||
        norm.includes('mejores resultados') ||
        norm.includes('records') ||
        norm.includes('resumen') ||
        norm.includes('marcas')
      ) {
        if (relevantWorkouts.length === 0) {
          reply = `Hola. En los últimos 10 días aún no tienes entrenamientos guardados. ¡Sal al agua a hacer una sesión con el reloj en la cubierta y analizaremos tus marcas!`;
        } else {
          reply = `🚣 **Tus mejores resultados en los últimos 10 días:**\n\n` +
            `• **Sesiones completadas:** ${relevantWorkouts.length} entrenamientos (${(totalDistM / 1000).toFixed(2)} km totales acumulados).\n` +
            `• **Mejor velocidad media:** **${bestAvgSpeedKmh.toFixed(1)} km/h**.\n` +
            `• **Pico de cadencia media:** **${bestCadenceSpm} spm** (paladas por minuto).\n` +
            `• **Mejor vuelta registrada:** ${bestLapDesc}.\n` +
            `• **Tiempo total en agua:** ${Math.round(totalElapsedMs / 60000)} minutos.\n\n` +
            `¡Gran regularidad! Tu volumen aeróbico está en una fase ascendente idónea para consolidar la velocidad de crucero.`;
        }
      } else if (norm.includes('vuelta') || norm.includes('lap') || norm.includes('circuito')) {
        reply = `📍 **Análisis de tus vueltas al circuito:**\n\n` +
          `La mejor vuelta detectada por el GPS ha sido:\n` +
          `👉 **${bestLapDesc}**.\n\n` +
          `Consejo técnico: Trata de anticipar la entrada al giro con una palada circular exterior amplia y mantén la cadencia viva al salir de la boya para acelerar el kayak sin perder inercia.`;
      } else if (norm.includes('cadencia') || norm.includes('palada') || norm.includes('spm')) {
        reply = `⚡ **Análisis de tu paleo:**\n` +
          `Has alcanzado hasta **${bestCadenceSpm} spm**. Para series de velocidad (200m/500m) te recomiendo buscar picos de 95-110 spm en los sprints, mientras que para ritmos de fondo constantes lo ideal es mantenerte entre 68 y 75 spm con un deslizamiento amplio y torsión dorsal.`;
      } else if (norm.includes('plan') || norm.includes('entreno') || norm.includes('series') || norm.includes('proximo')) {
        reply = `📋 **Propuesta de entrenamiento para hoy:**\n\n` +
          `1. **Calentamiento:** 10 min a ritmo suave continuo (65-70 spm).\n` +
          `2. **Bloque de series:** 5 repeticiones de [20 segundos de sprint a máxima potencia + 1 minuto de recuperación activa].\n` +
          `3. **Fondo de control:** 15 min a ritmo de crucero constante (8.5 - 9.5 km/h).\n` +
          `4. **Vuelta a la calma:** 5 min soltando y estirando en el kayak.\n\n` +
          `Puedes activar esta misma estructura directamente desde la pestaña de Entrenamientos del reloj.`;
      } else {
        reply = `¡Hola! Como tu entrenador de piragüismo en OpenKayak, puedo decirte que llevas acumulados ${(totalDistM / 1000).toFixed(2)} km en los últimos 10 días con una velocidad media tope de ${bestAvgSpeedKmh.toFixed(1)} km/h. Pregúntame sobre tus mejores resultados, tiempos por vuelta, cadencia de palada o planes para tu próxima tirada en el agua.`;
      }

      return res.json({
        reply,
        source: 'local-coach-engine',
      });
    }
  } catch (err: any) {
    console.error('Error in coach chat:', err);
    return res.status(500).json({
      error: 'Error en la respuesta del entrenador',
      message: err.message,
    });
  }
// Endpoint to parse natural language workout description into structured intervals
app.post('/api/gemini/parse-workout', async (req, res) => {
  const { promptText, availableCircuits = [] } = req.body;
  if (!promptText || typeof promptText !== 'string') {
    return res.status(400).json({ error: 'Texto descriptivo del entrenamiento requerido' });
  }

  // 1. Try with Gemini 3.8 Flash if API is available
  if (ai) {
    try {
      const systemInstruction = `Eres el compilador de entrenamientos de piragüismo de OpenKayak.
Tu misión es convertir descripciones en lenguaje natural de entrenamientos de kayak (con fases de tiempo, paleo foto/técnica, vueltas a circuitos guardados, pausas con disparador de velocidad, o series por metros/distancia) en un objeto JSON estricto válido para OpenKayak.

Estructura JSON requerida:
{
  "name": "Nombre conciso del entreno",
  "description": "Resumen claro",
  "repeatCycles": 1,
  "phases": [
    {
      "id": "p1",
      "name": "Nombre descriptivo de la fase",
      "type": "WARMUP" | "STEADY" | "SPRINT" | "RECOVERY" | "COOLDOWN" | "TECHNIQUE" | "PAUSE" | "DISTANCE_SERIES" | "LAP_SERIES",
      "triggerType": "TIME" | "LAPS" | "SPEED_TRIGGER" | "DISTANCE",
      "durationSec": 1200,
      "targetLaps": 3,
      "targetCircuitName": "Memorial",
      "triggerSpeedKmh": 8.0,
      "leadTimeSec": 5,
      "targetDistanceM": 100,
      "targetSpm": 72,
      "targetSpeedKmh": 10.5,
      "notes": "indicaciones tecnicas"
    }
  ]
}

Responde ÚNICAMENTE con el objeto JSON, sin formato markdown ni texto adicional.`;

      const prompt = `Circuitos conocidos disponibles: ${availableCircuits.map((c: any) => c.name || c.id).join(', ') || 'Memorial, Trasona'}

Descripción dada por el palista:
"${promptText}"`;

      const response = await ai.models.generateContent({
        model: 'gemini-3.8-flash',
        contents: prompt,
        config: {
          systemInstruction,
          responseMimeType: 'application/json',
          temperature: 0.1,
        },
      });

      if (response.text) {
        const cleaned = response.text.trim().replace(/^```json/, '').replace(/```$/, '').trim();
        const parsed = JSON.parse(cleaned);
        return res.json({ workout: parsed, source: 'gemini-3.8-flash' });
      }
    } catch (err: any) {
      console.warn('Gemini workout parse error, using offline engine:', err?.message);
    }
  }

  // 2. High-precision offline parser fallback
  const parsed = parseWorkoutOffline(promptText, availableCircuits);
  return res.json({ workout: parsed, source: 'offline-nlp-engine' });
});
async function startServer() {
  const isProd = process.env.NODE_ENV === 'production';

  if (!isProd) {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true, hmr: false },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(path.join(__dirname, 'dist')));
    app.get('*', (req, res) => {
      res.sendFile(path.join(__dirname, 'dist', 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`OpenKayak server listening on http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
