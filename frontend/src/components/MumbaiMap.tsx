import { useEffect, useRef } from 'react';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import type { Area, Segment } from '../lib/types';

export interface SegStyle { color: string; weight: number; opacity?: number; dash?: string }
export interface Marker { lat: number; lon: number; label: string; color: string }
export interface Overlay { coords: number[][]; color: string; weight: number; dash?: string }

interface Props {
  segments: Segment[];
  styleOf?: (s: Segment) => SegStyle | null;
  markers?: Marker[];
  overlays?: Overlay[];
  area?: Area | null;
  onMapClick?: (lat: number, lon: number) => void;
  onSegmentClick?: (s: Segment) => void;
  onViewChange?: (a: Area) => void;
  height?: number;
  fitArea?: Area | null;
}

const CLASS_STYLE: Record<string, SegStyle> = {
  motorway: { color: '#c2410c', weight: 3.5 }, trunk: { color: '#d97706', weight: 3 }, primary: { color: '#2563eb', weight: 2.5 },
  secondary: { color: '#0891b2', weight: 2 }, tertiary: { color: '#64748b', weight: 1.5 },
};

export function defaultStyle(s: Segment): SegStyle {
  return CLASS_STYLE[s.roadClass.replace('_link', '')] ?? { color: '#64748b', weight: 1.5 };
}

const toLatLngs = (flat: number[]): L.LatLngExpression[] => {
  const out: L.LatLngExpression[] = [];
  for (let i = 0; i + 1 < flat.length; i += 2) out.push([flat[i], flat[i + 1]]);
  return out;
};

/** Leaflet map of real OSM road segments on OpenStreetMap tiles (softened to grey so analysis colours stand out). */
export function MumbaiMap({ segments, styleOf, markers = [], overlays = [], area, onMapClick, onSegmentClick, onViewChange, height = 520, fitArea }: Props) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const roadLayer = useRef<L.LayerGroup | null>(null);
  const extraLayer = useRef<L.LayerGroup | null>(null);
  const handlers = useRef({ onMapClick, onSegmentClick, onViewChange });
  handlers.current = { onMapClick, onSegmentClick, onViewChange };

  useEffect(() => {
    if (!el.current || map.current) return;
    const m = L.map(el.current, { preferCanvas: true, zoomControl: true }).setView([19.07, 72.88], 11);
    // Standard OpenStreetMap tiles (tile usage policy: attribution shown, light academic use, no key required).
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, className: 'osm-tiles',
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(m);
    roadLayer.current = L.layerGroup().addTo(m);
    extraLayer.current = L.layerGroup().addTo(m);
    m.on('click', (e: L.LeafletMouseEvent) => handlers.current.onMapClick?.(e.latlng.lat, e.latlng.lng));
    const emit = () => {
      const b = m.getBounds();
      handlers.current.onViewChange?.({ south: b.getSouth(), west: b.getWest(), north: b.getNorth(), east: b.getEast() });
    };
    m.on('moveend', emit);
    map.current = m;
    emit();
    return () => { m.remove(); map.current = null; };
  }, []);

  useEffect(() => {
    const layer = roadLayer.current;
    if (!layer) return;
    layer.clearLayers();
    const renderer = L.canvas({ padding: 0.3 });
    for (const s of segments) {
      const st = (styleOf ?? defaultStyle)(s);
      if (!st) continue;
      const line = L.polyline(toLatLngs(s.geometry), { renderer, color: st.color, weight: st.weight, opacity: st.opacity ?? 0.85, dashArray: st.dash });
      line.on('click', (e: L.LeafletMouseEvent) => {
        if (handlers.current.onSegmentClick) {
          L.DomEvent.stopPropagation(e);
          handlers.current.onSegmentClick(s);
        }
      });
      line.bindTooltip(`${s.name || '(unnamed)'} · ${s.roadClass} · ${Math.round(s.lengthM)} m`, { sticky: true });
      line.addTo(layer);
    }
  }, [segments, styleOf]);

  useEffect(() => {
    const layer = extraLayer.current;
    if (!layer) return;
    layer.clearLayers();
    if (area) {
      L.rectangle([[area.south, area.west], [area.north, area.east]], { color: '#0f172a', weight: 1.5, fill: false, dashArray: '6 4', interactive: false }).addTo(layer);
    }
    for (const o of overlays) {
      L.polyline(o.coords as L.LatLngExpression[], { color: o.color, weight: o.weight, opacity: 0.95, dashArray: o.dash, interactive: false }).addTo(layer);
    }
    for (const mk of markers) {
      L.circleMarker([mk.lat, mk.lon], { radius: 7, color: '#fff', weight: 2, fillColor: mk.color, fillOpacity: 1 })
        .bindTooltip(mk.label, { permanent: true, direction: 'top', offset: [0, -8], className: 'map-label' }).addTo(layer);
    }
  }, [markers, overlays, area]);

  useEffect(() => {
    if (map.current && fitArea) {
      map.current.fitBounds([[fitArea.south, fitArea.west], [fitArea.north, fitArea.east]], { padding: [10, 10] });
    }
  }, [fitArea]);

  return <div ref={el} className="map" style={{ height }} role="application" aria-label="Map of Mumbai road network" />;
}

/** Flat [lat, lon, ...] arrays to Leaflet coordinate pairs. */
export function pairs(flat: number[]): number[][] {
  const out: number[][] = [];
  for (let i = 0; i + 1 < flat.length; i += 2) out.push([flat[i], flat[i + 1]]);
  return out;
}
