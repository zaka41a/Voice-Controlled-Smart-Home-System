export type Action = 'ON' | 'OFF' | 'OPEN' | 'CLOSE';
export type DeviceState = 'ON' | 'OFF' | 'OPEN' | 'CLOSED' | 'UNKNOWN';
export type DeviceType = 'LIGHT' | 'PLUG' | 'DOOR' | 'WINDOW';
export type SimulationMode = 'CONFIRMED' | 'UNCONFIRMED' | 'OFFLINE';
export type Outcome = 'CONFIRMED' | 'REJECTED' | 'FAILED' | 'UNCONFIRMED';

export interface DeviceInput {
  name: string;
  room: string;
  type: DeviceType;
  simulationMode: SimulationMode;
}
export interface Device extends DeviceInput {
  id: string;
  requestedState: DeviceState;
  reportedState: DeviceState;
  lastCommandAt: string | null;
}
export interface CommandDefinition {
  id: string;
  action: Action;
  types: DeviceType[];
}
export interface Mapping {
  commandId: string;
  deviceId: string;
  action: Action;
}
export interface CommandResult {
  id: string;
  timestamp: string;
  commandId: string | null;
  confidence: number | null;
  deviceId: string | null;
  action: Action | null;
  outcome: Outcome;
  requestedState: DeviceState;
  reportedState: DeviceState;
  message: string;
  durationMs: number;
  read: boolean;
}
export interface Dashboard {
  devices: Device[];
  mappings: Mapping[];
  events: CommandResult[];
  settings: {
    simulation: boolean;
    minimumConfidence: number;
    apiVersion: number;
    commandCatalog: CommandDefinition[];
  };
}
export interface ControllerStatus {
  enabled: boolean;
  connected: boolean;
  lastSeen: string | null;
  state: 'OFFLINE' | 'IDLE' | 'LISTENING' | 'PREVIEW' | 'SENDING' | 'RESULT' | 'ERROR';
  modelReady: boolean;
  microphoneLevel: number;
  simulation: boolean;
  transport: {
    type: 'WIFI_TICK' | 'UNKNOWN';
    rssiDbm: number | null;
    ipAddress: string | null;
  };
  display: ControllerDisplay | null;
}
export interface ControllerDisplay {
  screen: Exclude<ControllerStatus['state'], 'OFFLINE'>;
  commandId: string;
  outcome: Outcome | 'READY' | 'OFFLINE';
  connection: 'CONNECTING' | 'ONLINE' | 'OFFLINE' | 'NOT_CONFIGURED' | 'AUTH_ERROR';
  modelReady: boolean;
  microphoneLevel: number;
  simulation: boolean;
  receivedAt: string;
}
export const outcomeLabels: Record<Outcome, string> = {
  CONFIRMED: 'Confirmed',
  REJECTED: 'Rejected',
  FAILED: 'Failed',
  UNCONFIRMED: 'Unconfirmed',
};
export const stateLabels: Record<DeviceState, string> = {
  ON: 'On',
  OFF: 'Off',
  OPEN: 'Open',
  CLOSED: 'Closed',
  UNKNOWN: 'Unknown',
};
export const typeLabels: Record<DeviceType, string> = {
  LIGHT: 'Light',
  PLUG: 'Smart plug',
  DOOR: 'Door',
  WINDOW: 'Window',
};
export const actionLabels: Record<Action, string> = {
  ON: 'Turn on',
  OFF: 'Turn off',
  OPEN: 'Open',
  CLOSE: 'Close',
};
