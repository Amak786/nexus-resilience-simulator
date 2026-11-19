import { useEffect, useState, type ComponentType } from 'react';
import { useAppState } from './lib/state';
import { api } from './lib/api';
import { Overview } from './pages/Overview';
import { DataPage } from './pages/DataPage';
import { FailureLab } from './pages/FailureLab';
import { FlowOptimizer } from './pages/FlowOptimizer';
import { Resilience } from './pages/Resilience';
import { Incidents } from './pages/Incidents';
import { Screening } from './pages/Screening';
import { Routes } from './pages/Routes';
import { AlgorithmLab } from './pages/AlgorithmLab';
import { Evidence } from './pages/Evidence';
import { Help, Methodology } from './pages/Methodology';
import { Button } from './components/ui';

interface Route { path: string; label: string; group: string; page: ComponentType }

const ROUTES: Route[] = [
  { path: 'overview', label: 'Overview', group: 'Operations', page: Overview },
  { path: 'failure-lab', label: 'Failure Lab', group: 'Operations', page: FailureLab },
  { path: 'flow', label: 'Flow Optimizer', group: 'Operations', page: FlowOptimizer },
  { path: 'resilience', label: 'Infrastructure Resilience', group: 'Operations', page: Resilience },
  { path: 'incidents', label: 'Incident Priority', group: 'Operations', page: Incidents },
  { path: 'screening', label: 'Duplicate Screening', group: 'Operations', page: Screening },
  { path: 'routes', label: 'Delivery Routes', group: 'Operations', page: Routes },
  { path: 'data', label: 'Network & Data', group: 'Data', page: DataPage },
  { path: 'lab', label: 'Algorithm Lab', group: 'Evidence', page: AlgorithmLab },
  { path: 'evidence', label: 'Benchmarks & Tests', group: 'Evidence', page: Evidence },
  { path: 'methodology', label: 'Methodology', group: 'About', page: Methodology },
  { path: 'help', label: 'Help', group: 'About', page: Help },
];

function useHashPath(): string {
  const read = () => window.location.hash.replace(/^#\/?/, '').split('?')[0] || 'overview';
  const [path, setPath] = useState(read);
  useEffect(() => {
    const on = () => { setPath(read()); window.scrollTo(0, 0); };
    window.addEventListener('hashchange', on);
    return () => window.removeEventListener('hashchange', on);
  }, []);
  return path;
}

export function App() {
  const path = useHashPath();
  const { dataset, loading, loadError, resetDemo } = useAppState();
  const [navOpen, setNavOpen] = useState(false);
  const [apiUp, setApiUp] = useState<boolean | null>(null);
  const route = ROUTES.find((r) => r.path === path) ?? ROUTES[0];
  const Page = route.page;

  useEffect(() => { api.health().then(() => setApiUp(true)).catch(() => setApiUp(false)); }, []);
  useEffect(() => { document.title = `${route.label} · NEXUS`; setNavOpen(false); }, [route]);

  const groups = [...new Set(ROUTES.map((r) => r.group))];
  return (
    <div className="shell">
      <a className="skip" href="#main">Skip to content</a>
      <aside className={`sidebar ${navOpen ? 'open' : ''}`}>
        <div className="brand">
          <div className="logo" aria-hidden>N</div>
          <div><div className="brand-name">NEXUS</div><div className="brand-sub">Failure &amp; Resilience Simulator</div></div>
        </div>
        <nav aria-label="Main">
          {groups.map((g) => (
            <div key={g} className="nav-group">
              <div className="nav-title">{g}</div>
              {ROUTES.filter((r) => r.group === g).map((r) => (
                <a key={r.path} href={`#/${r.path}`} className={`nav-link ${r.path === route.path ? 'active' : ''}`}
                  aria-current={r.path === route.path ? 'page' : undefined}>{r.label}</a>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          <span className={`dot ${apiUp ? 'up' : apiUp === false ? 'down' : ''}`} /> API {apiUp ? 'online' : apiUp === false ? 'unreachable' : 'checking…'}
          <div className="muted">Synthetic data · academic project</div>
        </div>
      </aside>
      <div className="main-col">
        <header className="topbar">
          <button className="menu-btn" aria-label="Open navigation" aria-expanded={navOpen} onClick={() => setNavOpen(!navOpen)}>☰</button>
          <div className="topbar-title">{route.label}</div>
          <div className="topbar-meta">{dataset?.meta?.name ?? ''}</div>
        </header>
        <main id="main" className="content">
          {loading && <div className="skeleton page-skel" aria-label="Loading dataset" />}
          {!loading && loadError && (
            <div className="alert error"><strong>{loadError}</strong>
              <div className="row"><Button onClick={() => void resetDemo()}>Retry</Button></div></div>
          )}
          {!loading && dataset && <Page />}
        </main>
        <footer className="footer">NEXUS · CIA 3 Advanced Algorithms project · all algorithms implemented in Java · data is synthetic and for decision support only</footer>
      </div>
      {navOpen && <div className="scrim" onClick={() => setNavOpen(false)} aria-hidden />}
    </div>
  );
}
