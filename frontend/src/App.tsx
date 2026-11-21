import { useEffect, useState, type ComponentType } from 'react';
import { useAppState } from './lib/state';
import { Overview } from './pages/Overview';
import { Network } from './pages/Network';
import { Investigations } from './pages/Investigations';
import { AlgorithmLab } from './pages/AlgorithmLab';
import { Analyst } from './pages/Analyst';
import { DataSources } from './pages/DataSources';

interface Route { path: string; label: string; page: ComponentType }

const ROUTES: Route[] = [
  { path: 'overview', label: 'Overview', page: Overview },
  { path: 'network', label: 'Network', page: Network },
  { path: 'investigations', label: 'Investigations', page: Investigations },
  { path: 'lab', label: 'Algorithm Lab', page: AlgorithmLab },
  { path: 'analyst', label: 'AI Analyst', page: Analyst },
  { path: 'sources', label: 'Data Sources', page: DataSources },
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
  const { sources, viewer, stream, lastIngest } = useAppState();
  const [navOpen, setNavOpen] = useState(false);
  const route = ROUTES.find((r) => r.path === path) ?? ROUTES[0];
  const Page = route.page;

  useEffect(() => { document.title = `${route.label} · TRACE`; setNavOpen(false); }, [route]);

  const mode = sources?.mode ?? 'NO_SENSOR';
  return (
    <div className="shell">
      <a className="skip" href="#main">Skip to content</a>
      <aside className={`sidebar ${navOpen ? 'open' : ''}`}>
        <div className="brand">
          <svg className="logo-mark" viewBox="0 0 32 32" aria-hidden>
            <circle cx="8" cy="9" r="3" /><circle cx="24" cy="8" r="3" /><circle cx="16" cy="23" r="3.5" />
            <path d="M8 9 L16 23 L24 8 M8 9 L24 8" fill="none" strokeWidth="1.2" />
          </svg>
          <div><div className="brand-name">TRACE</div><div className="brand-sub">Network Trust, Reputation &amp; Connectivity Explorer</div></div>
        </div>
        <nav aria-label="Main">
          {ROUTES.map((r) => (
            <a key={r.path} href={`#/${r.path}`} className={`nav-link ${r.path === route.path ? 'active' : ''}`}
              aria-current={r.path === route.path ? 'page' : undefined}>{r.label}</a>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div><span className={`dot ${mode === 'LIVE' ? 'good' : mode === 'HISTORICAL' ? 'warn' : 'neutral'}`} aria-hidden />
            {mode === 'LIVE' ? 'Live: sensor connected' : mode === 'HISTORICAL' ? 'Historical: no live sensor' : viewer ? 'No sensor connected' : 'Public mode'}</div>
          {viewer && <div className="muted">Event stream: {stream}{lastIngest ? ` · last batch ${lastIngest.ingestedAt.slice(11, 19)} UTC` : ''}</div>}
          <div className="muted">Read-only investigation · TRACE never blocks traffic</div>
        </div>
      </aside>
      <div className="main-col">
        <header className="topbar">
          <button className="menu-btn" aria-label="Open navigation" aria-expanded={navOpen} onClick={() => setNavOpen(!navOpen)}>☰</button>
          <div className="topbar-title">TRACE · {route.label}</div>
        </header>
        <main id="main" className="content"><Page /></main>
        <footer className="footer">TRACE · student project (Advanced Algorithms) · threat data: abuse.ch Feodo Tracker (CC0), Spamhaus DROP, RIPEstat · not an official or production security system</footer>
      </div>
      {navOpen && <div className="scrim" onClick={() => setNavOpen(false)} aria-hidden />}
    </div>
  );
}
