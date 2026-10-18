import { BleCandidate, BleHrState } from '../types/openkayak';

export class HeartRateParser {
  public static parse(dataView: DataView | null): number | null {
    if (!dataView || dataView.byteLength < 2) return null;
    const flags = dataView.getUint8(0);
    const is16Bit = (flags & 0x01) !== 0;

    if (is16Bit && dataView.byteLength < 3) return null;

    const bpm = is16Bit ? dataView.getUint16(1, true) : dataView.getUint8(1);
    if (bpm >= 25 && bpm <= 240) {
      return bpm;
    }
    return null;
  }
}

export type BleStateListener = (state: BleHrState) => void;

export class BleHeartRateManager {
  private state: BleHrState = {
    phase: 'IDLE',
    candidates: [],
    name: '',
    address: '',
    bpm: 0,
    lastSampleAt: 0,
    notificationsEnabled: false,
    detail: 'Banda desconectada',
  };

  private listeners: BleStateListener[] = [];
  private device: any = null;
  private server: any = null;
  private characteristic: any = null;
  private watchdogInterval: any = null;
  private simulationInterval: any = null;
  private simulated = false;

  constructor() {
    this.startWatchdog();
  }

  public subscribe(listener: BleStateListener): () => void {
    this.listeners.push(listener);
    listener(this.state);
    return () => {
      this.listeners = this.listeners.filter((l) => l !== listener);
    };
  }

  public getState(): BleHrState {
    return { ...this.state };
  }

  private updateState(partial: Partial<BleHrState>): void {
    this.state = { ...this.state, ...partial };
    this.listeners.forEach((l) => l(this.state));
  }

  public async scanAndConnect(): Promise<void> {
    const navAny = navigator as any;
    if (!navAny.bluetooth) {
      this.updateState({
        phase: 'ERROR',
        detail: 'Web Bluetooth no está disponible en este navegador. Usando simulación de banda BLE.',
      });
      this.startSimulatedConnection('Banda Polar H10 (Simulada)');
      return;
    }

    try {
      this.updateState({
        phase: 'SCANNING',
        detail: 'Buscando bandas de pulso Bluetooth…',
      });

      const device = await navAny.bluetooth.requestDevice({
        filters: [{ services: ['heart_rate'] }],
        optionalServices: ['battery_service'],
      });

      this.device = device;
      this.device.addEventListener('gattserverdisconnected', () => {
        this.onDisconnected();
      });

      const candidate: BleCandidate = {
        name: device.name || 'Banda de pulso BLE',
        address: device.id,
      };

      this.updateState({
        candidates: [candidate],
        name: candidate.name,
        address: candidate.address,
        phase: 'CONNECTING',
        detail: 'Conectando con la banda…',
      });

      await this.connectGatt();
    } catch (err: any) {
      if (err.name === 'NotFoundError') {
        this.updateState({
          phase: 'IDLE',
          detail: 'Búsqueda cancelada por el usuario',
        });
      } else {
        this.updateState({
          phase: 'ERROR',
          detail: `Error Bluetooth: ${err.message || 'desconocido'}`,
        });
      }
    }
  }

  private async connectGatt(): Promise<void> {
    try {
      this.server = await this.device.gatt.connect();
      const service = await this.server.getPrimaryService('heart_rate');
      this.characteristic = await service.getCharacteristic('heart_rate_measurement');

      await this.characteristic.startNotifications();
      this.characteristic.addEventListener(
        'characteristicvaluechanged',
        (event: any) => {
          this.handleCharacteristicChanged(event.target.value);
        }
      );

      this.updateState({
        phase: 'CONNECTED',
        notificationsEnabled: true,
        detail: 'Banda conectada · esperando pulso',
      });
    } catch (err: any) {
      this.updateState({
        phase: 'ERROR',
        detail: `Error al leer servicio de pulso: ${err.message}`,
      });
    }
  }

  private handleCharacteristicChanged(dataView: DataView): void {
    const bpm = HeartRateParser.parse(dataView);
    if (bpm !== null) {
      const now = Date.now();
      this.updateState({
        phase: 'CONNECTED',
        bpm,
        lastSampleAt: now,
        detail: 'Pulso actualizado',
      });
    }
  }

  public startSimulatedConnection(name = 'Banda Polar H10'): void {
    this.disconnect();
    this.simulated = true;
    this.updateState({
      phase: 'CONNECTING',
      name,
      address: 'SIM-BLE-HR-01',
      detail: 'Conectando con banda BLE…',
    });

    setTimeout(() => {
      let currentBpm = 142;
      this.updateState({
        phase: 'CONNECTED',
        notificationsEnabled: true,
        bpm: currentBpm,
        lastSampleAt: Date.now(),
        detail: 'Banda conectada · pulso en tiempo real',
      });

      this.simulationInterval = setInterval(() => {
        // Natural physiological variance
        const delta = Math.floor(Math.random() * 5) - 2;
        currentBpm = Math.max(128, Math.min(178, currentBpm + delta));
        this.updateState({
          bpm: currentBpm,
          lastSampleAt: Date.now(),
          detail: 'Pulso actualizado',
        });
      }, 1000);
    }, 900);
  }

  public disconnect(): void {
    if (this.simulationInterval) {
      clearInterval(this.simulationInterval);
      this.simulationInterval = null;
    }
    this.simulated = false;

    if (this.characteristic) {
      try {
        this.characteristic.stopNotifications();
      } catch (_) {}
      this.characteristic = null;
    }

    if (this.server && this.server.connected) {
      try {
        this.server.disconnect();
      } catch (_) {}
      this.server = null;
    }

    this.onDisconnected();
  }

  private onDisconnected(): void {
    this.updateState({
      phase: 'IDLE',
      bpm: 0,
      lastSampleAt: 0,
      notificationsEnabled: false,
      detail: 'Banda desconectada',
    });
  }

  private startWatchdog(): void {
    this.watchdogInterval = setInterval(() => {
      if (
        this.state.phase === 'CONNECTED' &&
        this.state.lastSampleAt > 0 &&
        Date.now() - this.state.lastSampleAt > 12000
      ) {
        // Stale samples: display -- instead of frozen heart rate
        this.updateState({
          bpm: 0,
          detail: 'Sin pulso reciente (>12s)',
        });
      }
    }, 2000);
  }

  public destroy(): void {
    this.disconnect();
    if (this.watchdogInterval) clearInterval(this.watchdogInterval);
    this.listeners = [];
  }
}
