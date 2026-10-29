import { useCallback, useEffect, useRef, useState } from 'react';
import { api, CompatibilityError } from './api';
import type { Dashboard } from './types';

export function useDashboard() {
  const [data, setData] = useState<Dashboard>();
  const [connection, setConnection] = useState<'loading' | 'online' | 'offline' | 'incompatible'>(
    'loading',
  );
  const [error, setError] = useState('');
  const controller = useRef<AbortController | null>(null);
  const refresh = useCallback(async () => {
    controller.current?.abort();
    const current = new AbortController();
    controller.current = current;
    try {
      const next = await api.dashboard(current.signal);
      if (current.signal.aborted) return;
      setData(next);
      setConnection('online');
      setError('');
    } catch (failure) {
      if (current.signal.aborted) return;
      setConnection(failure instanceof CompatibilityError ? 'incompatible' : 'offline');
      setError(failure instanceof Error ? failure.message : 'Unable to load the dashboard.');
    } finally {
      if (controller.current === current) controller.current = null;
    }
  }, []);
  useEffect(() => {
    void refresh();
    const poll = window.setInterval(() => {
      if (!document.hidden && !controller.current) void refresh();
    }, 5000);
    const onVisible = () => {
      if (!document.hidden && !controller.current) void refresh();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      controller.current?.abort();
      window.clearInterval(poll);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [refresh]);
  return { data, connection, error, refresh };
}
