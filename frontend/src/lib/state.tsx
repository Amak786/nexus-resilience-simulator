import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, ApiError, auth, openStream } from './api';
import type { SourcesResponse } from './types';

interface AppState {
  sources: SourcesResponse | null;
  sourcesError: string | null;
  viewer: boolean;
  stream: 'off' | 'connected' | 'disconnected';
  lastIngest: { sensorId: string; accepted: number; newFlows: number; ingestedAt: string } | null;
  tick: number; // increments on each pushed ingest event; pages refetch when it changes
  refreshSources: () => Promise<void>;
}

const Ctx = createContext<AppState | null>(null);

/** Source status (public) plus the live event stream when a viewer token is present. */
export function AppStateProvider({ children }: { children: ReactNode }) {
  const [sources, setSources] = useState<SourcesResponse | null>(null);
  const [sourcesError, setSourcesError] = useState<string | null>(null);
  const [token, setToken] = useState(auth.get());
  const [stream, setStream] = useState<'off' | 'connected' | 'disconnected'>('off');
  const [lastIngest, setLastIngest] = useState<AppState['lastIngest']>(null);
  const [tick, setTick] = useState(0);

  const refreshSources = useCallback(async () => {
    try {
      setSources(await api.sources());
      setSourcesError(null);
    } catch (e) {
      setSourcesError(e instanceof ApiError ? e.message : 'Cannot load source status');
    }
  }, []);

  useEffect(() => {
    const on = () => setToken(auth.get());
    window.addEventListener('trace-auth', on);
    return () => window.removeEventListener('trace-auth', on);
  }, []);

  useEffect(() => {
    void refreshSources();
    const t = setInterval(() => void refreshSources(), 30_000);
    return () => clearInterval(t);
  }, [refreshSources, token]);

  const viewer = !!sources?.viewer;
  useEffect(() => {
    if (!viewer) { setStream('off'); return; }
    let close: (() => void) | null = null;
    let cancelled = false;
    openStream((d) => { setLastIngest(d as AppState['lastIngest']); setTick((x) => x + 1); }, setStream)
      .then((c) => { if (cancelled) c(); else close = c; })
      .catch(() => setStream('disconnected'));
    return () => { cancelled = true; close?.(); };
  }, [viewer]);

  return <Ctx.Provider value={{ sources, sourcesError, viewer, stream, lastIngest, tick, refreshSources }}>{children}</Ctx.Provider>;
}

export function useAppState(): AppState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useAppState outside provider');
  return v;
}

/** Runs an async API call with loading and error state. */
export function useRequest<T>() {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  const [busy, setBusy] = useState(false);
  const run = useCallback(async (fn: () => Promise<T>) => {
    setBusy(true);
    setError(null);
    try {
      const r = await fn();
      setData(r);
      return r;
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(String(e), 0));
      return null;
    } finally {
      setBusy(false);
    }
  }, []);
  return { data, error, busy, run, setData };
}
