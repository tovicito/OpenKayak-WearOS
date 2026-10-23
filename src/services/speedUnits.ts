import { SpeedUnit } from '../types/openkayak';

export interface FormattedSpeed {
  value: string;
  unitLabel: string;
  shortLabel: string;
}

export const SPEED_UNITS: { id: SpeedUnit; name: string; symbol: string; description: string }[] = [
  { id: 'kmh', name: 'Kilómetros por hora', symbol: 'km/h', description: 'Métrica estándar (12.5 km/h)' },
  { id: 'ms', name: 'Metros por segundo', symbol: 'm/s', description: 'Velocidad científica (3.5 m/s)' },
  { id: 'knots', name: 'Nudos náuticos', symbol: 'kt', description: 'Navegación marítima (6.8 kt)' },
  { id: 'pace_500m', name: 'Ritmo /500m (Sprint)', symbol: '/500m', description: 'Referencia olímpica de kayak (2:24 /500m)' },
  { id: 'pace_km', name: 'Ritmo /1000m (Fondo)', symbol: '/km', description: 'Pace por kilómetro (4:48 /km)' },
];

/**
 * Format speed in km/h to the desired speed unit
 */
export function formatSpeed(speedKmh: number, unit: SpeedUnit = 'kmh'): FormattedSpeed {
  const safeKmh = Math.max(0, Number.isFinite(speedKmh) ? speedKmh : 0);

  switch (unit) {
    case 'ms': {
      const ms = safeKmh / 3.6;
      return {
        value: ms.toFixed(1),
        unitLabel: 'm/s',
        shortLabel: 'm/s',
      };
    }
    case 'knots': {
      const knots = safeKmh / 1.852;
      return {
        value: knots.toFixed(1),
        unitLabel: 'nudos',
        shortLabel: 'kt',
      };
    }
    case 'pace_500m': {
      // 500m pace: time in minutes to cover 0.5km = (0.5 / safeKmh) * 60 = 30 / safeKmh
      if (safeKmh < 0.6) {
        return { value: '--:--', unitLabel: 'min/500m', shortLabel: '/500m' };
      }
      const totalSeconds = Math.round((30 / safeKmh) * 60);
      const minutes = Math.floor(totalSeconds / 60);
      const seconds = totalSeconds % 60;
      return {
        value: `${minutes}:${seconds.toString().padStart(2, '0')}`,
        unitLabel: 'min/500m',
        shortLabel: '/500m',
      };
    }
    case 'pace_km': {
      // 1000m pace: time in minutes to cover 1km = 60 / safeKmh
      if (safeKmh < 0.6) {
        return { value: '--:--', unitLabel: 'min/km', shortLabel: '/km' };
      }
      const totalSeconds = Math.round((60 / safeKmh) * 60);
      const minutes = Math.floor(totalSeconds / 60);
      const seconds = totalSeconds % 60;
      return {
        value: `${minutes}:${seconds.toString().padStart(2, '0')}`,
        unitLabel: 'min/km',
        shortLabel: '/km',
      };
    }
    case 'kmh':
    default:
      return {
        value: safeKmh.toFixed(1),
        unitLabel: 'km/h',
        shortLabel: 'km/h',
      };
  }
}

/**
 * Cycle to next unit when tapping the speed metric on the watch
 */
export function getNextSpeedUnit(current: SpeedUnit): SpeedUnit {
  const order: SpeedUnit[] = ['kmh', 'pace_500m', 'pace_km', 'knots', 'ms'];
  const idx = order.indexOf(current);
  return order[(idx + 1) % order.length];
}
