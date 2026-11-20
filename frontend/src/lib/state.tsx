import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, ApiError } from './api';
import type { Area, NetworkResponse, ProvenanceResponse, Segment } from './types';

/** Named analysis areas inside the study area (bounding boxes in degrees). */
export const PRESET_AREAS: { id: string; label: string; area: Area }[] = [
  { id: 'dadar', label: 'Dadar - Mahim - Bandra', area: { south: 19.01, west: 72.83, north: 19.06, east: 72.87 } },
  { id: 'kurla', label: 'Kurla - Ghatkopar - Chembur', area: { south: 19.05, west: 72.87, north: 19.1, east: 72.92 } },
  { id: 'andheri', label: 'Andheri - Jogeshwari', area: { south: 19.1, west: 72.83, north: 19.15, east: 72.88 } },
  { id: 'fort', label: 'Colaba - Fort - Byculla', area: { south: 18.9, west: 72.81, north: 18.98, east: 72.85 } },
  { id: 'borivali', label: 'Malad - Kandivali - Borivali', area: { south: 19.18, west: 72.83, north: 19.25, east: 72.87 } },
];

interface AppState {
  provenance: ProvenanceResponse | null;
  network: NetworkResponse | null;
  segmentById: Map<number, Segment>;
  loading: boolean;
  error: string | null;
  reload: () => Promise<void>;
}

const Ctx = createContext<AppState | null>(null);

/** Loads provenance and the full study-area network once; every analysis itself runs on the server. */
export function AppStateProvider({ children }: { children: ReactNode }) {
  const [provenance, setProvenance] = useState<ProvenanceResponse | null>(null);
  const [network, setNetwork] = useState<NetworkResponse | null>(null);
  const [segmentById, setSegmentById] = useState<Map<number, Segment>>(new Map());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [p, n] = await Promise.all([api.provenance(), api.network()]);
      setProvenance(p);
      setNetwork(n);
      setSegmentById(new Map(n.items.map((s) => [s.id, s])));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not load the Mumbai network.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  return <Ctx.Provider value={{ provenance, network, segmentById, loading, error, reload }}>{children}</Ctx.Provider>;
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
