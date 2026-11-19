import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, ApiError } from './api';
import type { CityDataset, FlowResponse } from './types';

interface AppState {
  dataset: CityDataset | null;
  loading: boolean;
  loadError: string | null;
  setDataset: (d: CityDataset) => void;
  resetDemo: () => Promise<void>;
  lastFlow: FlowResponse | null;
  setLastFlow: (f: FlowResponse | null) => void;
}

const Ctx = createContext<AppState | null>(null);
const KEY = 'nexus.dataset.v1';

/** Holds the current dataset in the browser (the API is stateless) and remembers it per viewer. */
export function AppStateProvider({ children }: { children: ReactNode }) {
  const [dataset, setDatasetState] = useState<CityDataset | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [lastFlow, setLastFlow] = useState<FlowResponse | null>(null);

  const setDataset = useCallback((d: CityDataset) => {
    setDatasetState(d);
    setLastFlow(null);
    try {
      localStorage.setItem(KEY, JSON.stringify(d));
    } catch {
      /* storage unavailable: keep in memory only */
    }
  }, []);

  const resetDemo = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const d = await api.demo();
      setDataset(d);
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : 'Could not load the demo dataset.');
    } finally {
      setLoading(false);
    }
  }, [setDataset]);

  useEffect(() => {
    let saved: CityDataset | null = null;
    try {
      const raw = localStorage.getItem(KEY);
      saved = raw ? (JSON.parse(raw) as CityDataset) : null;
    } catch {
      saved = null;
    }
    if (saved && Array.isArray(saved.nodes)) {
      setDatasetState(saved);
      setLoading(false);
    } else {
      void resetDemo();
    }
  }, [resetDemo]);

  return (
    <Ctx.Provider value={{ dataset, loading, loadError, setDataset, resetDemo, lastFlow, setLastFlow }}>
      {children}
    </Ctx.Provider>
  );
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
