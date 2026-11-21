import { useEffect, useState, type ComponentType } from 'react';
import { useAppState } from './lib/state';
import { Overview } from './pages/Overview';
import { Integrity } from './pages/Integrity';
import { Explorer } from './pages/Explorer';
import { FindRoute } from './pages/FindRoute';
import { CriticalLinks } from './pages/CriticalLinks';
import { Connectivity } from './pages/Health';
import { Planner } from './pages/Planner';
import { AlgorithmLab } from './pages/AlgorithmLab';
import { Button } from './components/ui';

interface Route { path: string; label: string; page: ComponentType }

const ROUTES: Route[] = [
  { path: 'overview', label: 'Live Workspace', page: Overview },
  { path: 'integrity', label: 'Integrity Check', page: Integrity },
  { path: 'explore', label: 'Explore Roads', page: Explorer },
  { path: 'route', label: 'Find Route', page: FindRoute },
  { path: 'connectivity', label: 'Network Connectivity', page: Connectivity },
  { path: 'critical-links', label: 'Critical Links', page: CriticalLinks },
  { path: 'planner', label: 'Multi-Stop Planner', page: Planner },
  { path: 'lab', label: 'Algorithm Lab', page: AlgorithmLab },
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
  const { loading, error, reload, provenance } = useAppState();
  const [navOpen, setNavOpen] = useState(false);
  const route = ROUTES.find((r) => r.path === path) ?? ROUTES[0];
  const Page = route.page;

  useEffect(() => { document.title = `${route.label} · Nexus Resilience`; setNavOpen(false); }, [route]);

  const roads = provenance?.sources.find((s) => s.id === 'roads');
  return (
    <div className="shell">
      <a className="skip" href="#main">Skip to content</a>
      <aside className={`sidebar ${navOpen ? 'open' : ''}`}>
        <div className="brand">
          <div className="logo" aria-hidden>S</div>
          <div><div className="brand-name">NEXUS</div><div className="brand-sub">Resilience · Mumbai</div></div>
        </div>
        <nav aria-label="Main">
          {ROUTES.map((r) => (
            <a key={r.path} href={`#/${r.path}`} className={`nav-link ${r.path === route.path ? 'active' : ''}`}
              aria-current={r.path === route.path ? 'page' : undefined}>{r.label}</a>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div>Map data © OpenStreetMap contributors (ODbL)</div>
          {roads?.retrievedAt && <div className="muted">Road snapshot {roads.retrievedAt.slice(0, 10)}</div>}
          <div className="muted">Live sources + grounded AI · no live traffic</div>
        </div>
      </aside>
      <div className="main-col">
        <header className="topbar">
          <button className="menu-btn" aria-label="Open navigation" aria-expanded={navOpen} onClick={() => setNavOpen(!navOpen)}>☰</button>
          <div className="topbar-title">{route.label}</div>
        </header>
        <main id="main" className="content">
          {loading && <div className="loading"><div className="skeleton page-skel" /><p className="muted">Loading the Mumbai road network… (free hosting can take up to a minute to wake)</p></div>}
          {!loading && error && (
            <div className="alert error"><strong>{error}</strong><div className="row"><Button onClick={() => void reload()}>Retry</Button></div></div>
          )}
          {!loading && !error && <Page />}
        </main>
        <footer className="footer">Nexus Resilience: Silent Shield · Advanced Algorithms CIA 3 · algorithms implemented in Java · OpenStreetMap data (ODbL) · not an official or production system</footer>
      </div>
      {navOpen && <div className="scrim" onClick={() => setNavOpen(false)} aria-hidden />}
    </div>
  );
}
