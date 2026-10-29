import type {
  CommandResult,
  ControllerStatus,
  Dashboard,
  Device,
  DeviceInput,
  Mapping,
} from './types';

const API_VERSION = 3;
const REQUEST_TIMEOUT_MS = 10000;

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public field?: string,
  ) {
    super(message);
  }
}

export class CompatibilityError extends Error {
  constructor() {
    super(
      'The running server is not compatible with this interface. Restart the application with the latest build, then retry.',
    );
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  init.signal?.addEventListener('abort', abort, { once: true });
  if (init.signal?.aborted) controller.abort();
  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`/api${path}`, {
      ...init,
      signal: controller.signal,
      cache: 'no-store',
      headers: {
        Accept: 'application/json',
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...init.headers,
      },
    });
    if (response.status === 204) return undefined as T;
    const body = await response.json();
    if (!response.ok)
      throw new ApiError(
        body.message || 'The request could not be completed.',
        response.status,
        body.field,
      );
    return body as T;
  } catch (error) {
    if (timedOut)
      throw new Error(
        path === '/commands'
          ? 'The server did not respond in time. Check Activity and the device state before retrying.'
          : 'The server did not respond in time. Refresh the workspace before retrying.',
        { cause: error },
      );
    if (error instanceof ApiError) throw error;
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    if (error instanceof SyntaxError)
      throw new Error('The server returned an unexpected response.', { cause: error });
    throw new Error(
      path === '/commands'
        ? 'The connection was lost. Check Activity and the device state before retrying.'
        : 'Cannot reach the server. Check the connection and refresh the workspace before retrying.',
      { cause: error },
    );
  } finally {
    window.clearTimeout(timer);
    init.signal?.removeEventListener('abort', abort);
  }
}

async function dashboard(signal?: AbortSignal): Promise<Dashboard> {
  const result = await request<Dashboard>('/dashboard', { signal });
  if (
    result?.settings?.apiVersion !== API_VERSION ||
    !Array.isArray(result.settings.commandCatalog)
  ) {
    throw new CompatibilityError();
  }
  if (
    !Array.isArray(result.devices) ||
    !Array.isArray(result.mappings) ||
    !Array.isArray(result.events)
  ) {
    throw new Error('The server returned an incomplete dashboard.');
  }
  return result;
}

export const api = {
  dashboard,
  controller: (signal?: AbortSignal) => request<ControllerStatus>('/controller/status', { signal }),
  command: (commandId: string, confidence: number) =>
    request<CommandResult>('/commands', {
      method: 'POST',
      body: JSON.stringify({ commandId, confidence }),
    }),
  saveDevice: (id: string, device: DeviceInput) =>
    request<Device>(`/devices/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify(device),
    }),
  removeDevice: (id: string) =>
    request<void>(`/devices/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  saveMapping: ({ commandId, ...mapping }: Mapping) =>
    request<Mapping>(`/mappings/${encodeURIComponent(commandId)}`, {
      method: 'PUT',
      body: JSON.stringify(mapping),
    }),
  removeMapping: (id: string) =>
    request<void>(`/mappings/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  markRead: (ids: string[]) =>
    request<void>('/notifications/read', {
      method: 'POST',
      body: JSON.stringify({ ids }),
    }),
};
