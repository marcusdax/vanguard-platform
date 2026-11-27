import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import * as maplibregl from 'maplibre-gl';
import L from 'leaflet';
import {
  Activity,
  CloudLightning,
  Compass,
  Layers,
  MapPin,
  Radar,
  RefreshCw,
  Satellite,
  Zap,
} from 'lucide-react';
import { api } from '@/lib/api';
import { createCircleRing, extractGeoCenter } from '@/lib/geo';

type MapStyleId = 'fiord' | 'liberty' | 'positron' | 'esri_satellite' | 'carto_dark' | 'opentopomap';
type PerspectiveMode = 'iso' | '2d' | 'street';

const OPENFREEMAP_STYLES: Partial<Record<MapStyleId, string>> = {
  fiord: 'https://tiles.openfreemap.org/styles/fiord',
  liberty: 'https://tiles.openfreemap.org/styles/liberty',
  positron: 'https://tiles.openfreemap.org/styles/positron',
};

const LEAFLET_TILE_STYLES: Record<
  'esri_satellite' | 'carto_dark' | 'opentopomap',
  { label: string; url: string; attribution: string; maxZoom: number }
> = {
  esri_satellite: {
    label: 'Esri Satellite Aerial',
    url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    attribution: 'Esri World Imagery',
    maxZoom: 19,
  },
  carto_dark: {
    label: 'Carto Dusk Dark',
    url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
    attribution: 'OpenStreetMap & CARTO',
    maxZoom: 19,
  },
  opentopomap: {
    label: 'OpenTopoMap Contours',
    url: 'https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png',
    attribution: 'OpenTopoMap (CC-BY-SA)',
    maxZoom: 17,
  },
};

const NEXRAD_TILE_URL =
  'https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/{z}/{x}/{y}.png';

interface StormMapConsoleProps {
  userAuthenticated: boolean;
  onDispatchedBounties?: () => void;
}

export function StormMapConsole({
  userAuthenticated,
  onDispatchedBounties,
}: StormMapConsoleProps) {
  const [mapStyle, setMapStyle] = useState<MapStyleId>('esri_satellite');
  const [perspective, setPerspective] = useState<PerspectiveMode>('iso');
  const [layers, setLayers] = useState({
    storms: true,
    h3Hex: true,
    properties: true,
    rings: true,
    radar: true,
    buildings3d: true,
  });

  const [focusCenter, setFocusCenter] = useState<{ lat: number; lng: number }>({
    lat: 32.5421,
    lng: -97.3208,
  });
  const [overview, setOverview] = useState<any | null>(null);
  const [eventsGeo, setEventsGeo] = useState<any>({ type: 'FeatureCollection', features: [] });
  const [hexGeo, setHexGeo] = useState<any>({ type: 'FeatureCollection', features: [] });
  const [properties, setProperties] = useState<any[]>([]);
  const [noaaMarkers, setNoaaMarkers] = useState<any[]>([]);
  const [impactReport, setImpactReport] = useState<any | null>(null);
  const [syncStatus, setSyncStatus] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const mapLibreContainerRef = useRef<HTMLDivElement | null>(null);
  const leafletContainerRef = useRef<HTMLDivElement | null>(null);
  const mapLibreRef = useRef<maplibregl.Map | null>(null);
  const leafletRef = useRef<L.Map | null>(null);
  const leafletTileRef = useRef<L.TileLayer | null>(null);
  const leafletRadarRef = useRef<L.TileLayer | null>(null);
  const leafletOverlayRef = useRef<L.LayerGroup | null>(null);

  const isMapLibre = Boolean(OPENFREEMAP_STYLES[mapStyle]);

  const loadSpatialIntel = useCallback(async () => {
    if (!userAuthenticated) return;
    try {
      const [ovRes, evRes, hexRes, propRes, mkRes] = await Promise.all([
        api.get('/storm-intel/overview?days=14'),
        api.get('/storm-intel/events.geojson?days=14'),
        api.get('/storm-intel/hex-grid?days=14'),
        api.get('/maps/property-intelligence'),
        api.get('/maps/storm-markers'),
      ]);
      setOverview(ovRes.data);
      setEventsGeo(evRes.data || { type: 'FeatureCollection', features: [] });
      setHexGeo(hexRes.data || { type: 'FeatureCollection', features: [] });
      setProperties(propRes.data?.properties ?? []);
      setNoaaMarkers(mkRes.data?.markers ?? []);
    } catch {
      // ignore initial offline degradation
    }
  }, [userAuthenticated]);

  const inspectPointImpact = useCallback(
    async (lat: number, lng: number) => {
      setFocusCenter({ lat, lng });
      if (!userAuthenticated) return;
      try {
        const { data } = await api.get(`/storm-intel/impact?lat=${lat.toFixed(5)}&lng=${lng.toFixed(5)}`);
        setImpactReport(data);
      } catch {
        // leave previous impact intact if unauthenticated
      }
    },
    [userAuthenticated],
  );

  useEffect(() => {
    void loadSpatialIntel();
  }, [loadSpatialIntel]);

  async function handleNoaaSync() {
    setBusy(true);
    setSyncStatus(null);
    try {
      const { data } = await api.post('/storm-intel/sync', { limit: 100 });
      setSyncStatus(
        data?.degraded
          ? `NOAA unavailable — loaded ${data?.events?.length ?? 0} cached storm swaths`
          : `Synced NOAA NWS alerts: ${data?.created ?? 0} new, ${data?.updated ?? 0} updated`,
      );
      await loadSpatialIntel();
    } catch (err: any) {
      setSyncStatus(err?.response?.data?.error || 'NOAA sync request failed.');
    } finally {
      setBusy(false);
    }
  }

  async function handleEmitBounties(stormEventId: string, stormName: string) {
    setBusy(true);
    setSyncStatus(null);
    try {
      const { data } = await api.post(`/vanguard/dispatch/from-storm/${stormEventId}`, {});
      setSyncStatus(`Dispatched ${data?.created?.length ?? 2} H3 field bounties from "${stormName}".`);
      onDispatchedBounties?.();
    } catch (err: any) {
      setSyncStatus(err?.response?.data?.error || 'Could not emit storm bounties.');
    } finally {
      setBusy(false);
    }
  }

  // Paint Leaflet overlay layers
  const syncLeafletOverlays = useCallback(() => {
    const map = leafletRef.current;
    const group = leafletOverlayRef.current;
    if (!map || !group) return;
    group.clearLayers();

    if (layers.radar) {
      if (!leafletRadarRef.current) {
        leafletRadarRef.current = L.tileLayer(NEXRAD_TILE_URL, {
          opacity: 0.55,
          attribution: 'IEM / NOAA NEXRAD',
        });
      }
      if (!map.hasLayer(leafletRadarRef.current)) {
        leafletRadarRef.current.addTo(map);
      }
    } else if (leafletRadarRef.current && map.hasLayer(leafletRadarRef.current)) {
      map.removeLayer(leafletRadarRef.current);
    }

    if (layers.rings) {
      [805, 1609, 2414].forEach((meters, idx) => {
        L.circle([focusCenter.lat, focusCenter.lng], {
          radius: meters,
          color: idx === 2 ? '#E09132' : '#2FA37A',
          weight: idx === 2 ? 2 : 1.2,
          dashArray: '6 4',
          fillOpacity: idx === 0 ? 0.08 : 0.02,
        }).addTo(group);
      });
    }

    if (layers.h3Hex && Array.isArray(hexGeo?.features)) {
      L.geoJSON(hexGeo, {
        style: (feat: any) => {
          const sev = String(feat?.properties?.severity || '').toUpperCase();
          const color = sev === 'EXTREME' ? '#E05252' : sev === 'SEVERE' ? '#E09132' : '#2FA37A';
          return { color, weight: 1.4, fillOpacity: 0.16 };
        },
        onEachFeature: (feat: any, layer: any) => {
          const p = feat.properties || {};
          layer.bindPopup(
            `<strong>H3 ${p.h3Index}</strong><br/>Storms: ${p.stormCount ?? 0} · Leads: ${p.leadCount ?? 0} · Jobs: ${p.jobCount ?? 0}<br/>Saturation: ${p.saturationPct ?? 0}%`,
          );
        },
      }).addTo(group);
    }

    if (layers.storms && Array.isArray(eventsGeo?.features)) {
      L.geoJSON(eventsGeo, {
        style: { color: '#E05252', weight: 2, fillOpacity: 0.18 },
        onEachFeature: (feat: any, layer: any) => {
          const p = feat.properties || {};
          layer.bindPopup(
            `<strong>${p.name || 'Storm Swath'}</strong><br/>${p.eventType || 'Storm'} · ${p.severity || 'Unknown'}`,
          );
        },
      }).addTo(group);
    }

    if (layers.properties) {
      properties.forEach((prop) => {
        const score = Number(prop.riskScore || 0);
        const col = score >= 60 ? '#E05252' : score >= 30 ? '#E09132' : '#2FA37A';
        L.circleMarker([prop.lat, prop.lng], {
          radius: 7,
          color: '#0E1116',
          weight: 1.5,
          fillColor: col,
          fillOpacity: 0.92,
        })
          .bindPopup(`<strong>${prop.address}</strong><br/>Storm Impact Score: ${score}/100`)
          .addTo(group);
      });

      noaaMarkers.forEach((mk) => {
        if (typeof mk.lat !== 'number' || typeof mk.lon !== 'number') return;
        L.circleMarker([mk.lat, mk.lon], {
          radius: 6,
          color: '#FFFFFF',
          weight: 1.2,
          fillColor: '#E05252',
          fillOpacity: 0.85,
        })
          .bindPopup(`<strong>${mk.type || 'NOAA Alert'}</strong><br/>${mk.headline || ''}`)
          .addTo(group);
      });
    }
  }, [eventsGeo, focusCenter, hexGeo, layers, noaaMarkers, properties]);

  // Paint MapLibre GL JS 3D layers
  const syncMapLibreOverlays = useCallback(() => {
    const map = mapLibreRef.current;
    if (!map || !map.isStyleLoaded()) return;

    const ringGeo = {
      type: 'FeatureCollection' as const,
      features: layers.rings
        ? [805, 1609, 2414].map((r) => ({
            type: 'Feature' as const,
            properties: { r },
            geometry: {
              type: 'Polygon' as const,
              coordinates: [createCircleRing(focusCenter.lng, focusCenter.lat, r)],
            },
          }))
        : [],
    };
    if (map.getSource('vg-rings')) {
      (map.getSource('vg-rings') as maplibregl.GeoJSONSource).setData(ringGeo);
    } else {
      map.addSource('vg-rings', { type: 'geojson', data: ringGeo });
      map.addLayer({
        id: 'vg-rings-line',
        type: 'line',
        source: 'vg-rings',
        paint: { 'line-color': '#E09132', 'line-width': 1.8, 'line-dasharray': [4, 3] },
      });
    }

    const h3Data = layers.h3Hex ? hexGeo : { type: 'FeatureCollection', features: [] };
    if (map.getSource('vg-h3')) {
      (map.getSource('vg-h3') as maplibregl.GeoJSONSource).setData(h3Data);
    } else {
      map.addSource('vg-h3', { type: 'geojson', data: h3Data });
      map.addLayer({
        id: 'vg-h3-fill',
        type: 'fill',
        source: 'vg-h3',
        paint: { 'fill-color': '#2FA37A', 'fill-opacity': 0.16 },
      });
      map.addLayer({
        id: 'vg-h3-line',
        type: 'line',
        source: 'vg-h3',
        paint: { 'line-color': '#2FA37A', 'line-width': 1.4 },
      });
    }

    const stormData = layers.storms ? eventsGeo : { type: 'FeatureCollection', features: [] };
    if (map.getSource('vg-storms')) {
      (map.getSource('vg-storms') as maplibregl.GeoJSONSource).setData(stormData);
    } else {
      map.addSource('vg-storms', { type: 'geojson', data: stormData });
      map.addLayer({
        id: 'vg-storms-fill',
        type: 'fill',
        source: 'vg-storms',
        paint: { 'fill-color': '#E05252', 'fill-opacity': 0.2 },
      });
      map.addLayer({
        id: 'vg-storms-line',
        type: 'line',
        source: 'vg-storms',
        paint: { 'line-color': '#E05252', 'line-width': 2 },
      });
    }

    if (layers.buildings3d && !map.getLayer('vg-3d-buildings')) {
      const styleSources = map.getStyle()?.sources || {};
      const vec = Object.keys(styleSources).find((k) => styleSources[k].type === 'vector');
      if (vec) {
        try {
          map.addLayer({
            id: 'vg-3d-buildings',
            source: vec,
            'source-layer': 'building',
            type: 'fill-extrusion',
            minzoom: 13.5,
            paint: {
              'fill-extrusion-color': '#2B3442',
              'fill-extrusion-height': ['coalesce', ['get', 'render_height'], 10],
              'fill-extrusion-base': ['coalesce', ['get', 'render_min_height'], 0],
              'fill-extrusion-opacity': 0.78,
            },
          });
        } catch {
          // style may omit building layer
        }
      }
    } else if (map.getLayer('vg-3d-buildings')) {
      map.setLayoutProperty(
        'vg-3d-buildings',
        'visibility',
        layers.buildings3d ? 'visible' : 'none',
      );
    }
  }, [eventsGeo, focusCenter, hexGeo, layers]);

  // Initialize / switch active map engine
  useEffect(() => {
    if (isMapLibre) {
      const styleUrl = OPENFREEMAP_STYLES[mapStyle]!;
      const el = mapLibreContainerRef.current;
      if (!el) return;

      if (!mapLibreRef.current) {
        const ml = new maplibregl.Map({
          container: el,
          style: styleUrl,
          center: [focusCenter.lng, focusCenter.lat],
          zoom: 12.2,
          pitch: perspective === '2d' ? 0 : perspective === 'street' ? 62 : 45,
          bearing: perspective === '2d' ? 0 : -18,
        });
        ml.addControl(new maplibregl.NavigationControl({ visualizePitch: true }), 'top-right');
        ml.on('style.load', () => syncMapLibreOverlays());
        ml.on('click', (e: maplibregl.MapMouseEvent) => {
          void inspectPointImpact(e.lngLat.lat, e.lngLat.lng);
        });
        ml.on('error', () => {
          // Automatic graceful fallback to reliable raster Leaflet if offline / style blocked
          setMapStyle('carto_dark');
        });
        mapLibreRef.current = ml;
      } else {
        mapLibreRef.current.setStyle(styleUrl);
        mapLibreRef.current.easeTo({
          center: [focusCenter.lng, focusCenter.lat],
          pitch: perspective === '2d' ? 0 : perspective === 'street' ? 62 : 45,
        });
        mapLibreRef.current.resize();
      }
    } else {
      const el = leafletContainerRef.current;
      if (!el) return;
      const cfg =
        LEAFLET_TILE_STYLES[mapStyle as keyof typeof LEAFLET_TILE_STYLES] ||
        LEAFLET_TILE_STYLES.esri_satellite;

      if (!leafletRef.current) {
        const lf = L.map(el).setView([focusCenter.lat, focusCenter.lng], 11);
        leafletOverlayRef.current = L.layerGroup().addTo(lf);
        lf.on('click', (e: L.LeafletMouseEvent) => {
          void inspectPointImpact(e.latlng.lat, e.latlng.lng);
        });
        leafletRef.current = lf;
      }
      if (leafletTileRef.current) {
        leafletRef.current.removeLayer(leafletTileRef.current);
      }
      leafletTileRef.current = L.tileLayer(cfg.url, {
        maxZoom: cfg.maxZoom,
        attribution: cfg.attribution,
      }).addTo(leafletRef.current);
      leafletRef.current.setView([focusCenter.lat, focusCenter.lng]);
      leafletRef.current.invalidateSize();
      syncLeafletOverlays();
    }
  }, [focusCenter, inspectPointImpact, isMapLibre, mapStyle, perspective, syncLeafletOverlays, syncMapLibreOverlays]);

  useEffect(() => {
    if (isMapLibre) syncMapLibreOverlays();
    else syncLeafletOverlays();
  }, [isMapLibre, syncLeafletOverlays, syncMapLibreOverlays]);

  const stormList = useMemo(() => overview?.events ?? [], [overview]);

  return (
    <div className="space-y-5">
      {/* Control Strip */}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-obsidian-border bg-obsidian-surface p-4">
        <div className="flex flex-wrap items-center gap-2.5">
          <label htmlFor="vg-map-style" className="text-xs font-medium text-obsidian-muted">
            Spatial Basemap
          </label>
          <select
            id="vg-map-style"
            value={mapStyle}
            onChange={(e) => setMapStyle(e.target.value as MapStyleId)}
            className="rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-1.5 font-mono text-xs text-obsidian-ink"
          >
            <option value="esri_satellite">Leaflet · Esri Satellite Aerial</option>
            <option value="carto_dark">Leaflet · Carto Dusk Dark</option>
            <option value="opentopomap">Leaflet · OpenTopo Contours</option>
            <option value="fiord">MapLibre 3D · OpenFreeMap Fiord</option>
            <option value="liberty">MapLibre 3D · OpenFreeMap Liberty</option>
            <option value="positron">MapLibre 3D · OpenFreeMap Positron</option>
          </select>

          {isMapLibre && (
            <div className="inline-flex rounded-lg border border-obsidian-border bg-obsidian-ground p-0.5 text-xs">
              {(['iso', '2d', 'street'] as PerspectiveMode[]).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => setPerspective(mode)}
                  className={`rounded px-2.5 py-1 font-mono uppercase transition ${
                    perspective === mode
                      ? 'bg-obsidian-amber text-obsidian-ground font-semibold'
                      : 'text-obsidian-muted hover:text-obsidian-ink'
                  }`}
                >
                  {mode}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          {(
            [
              ['radar', 'NEXRAD Radar', Radar],
              ['h3Hex', 'H3 Res-7 Hexes', Layers],
              ['storms', 'Storm Polygons', CloudLightning],
              ['rings', '1.5-mi Rings', Compass],
              ['properties', 'Leads & Parcels', MapPin],
            ] as const
          ).map(([key, label, Icon]) => {
            const active = layers[key];
            return (
              <button
                key={key}
                type="button"
                onClick={() => setLayers((prev) => ({ ...prev, [key]: !prev[key] }))}
                className={`inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1.5 text-xs font-medium transition ${
                  active
                    ? 'border-obsidian-proof/60 bg-obsidian-proof/15 text-obsidian-proof'
                    : 'border-obsidian-border bg-obsidian-ground text-obsidian-muted'
                }`}
              >
                <Icon className="h-3.5 w-3.5" />
                {label}
              </button>
            );
          })}

          <button
            type="button"
            disabled={!userAuthenticated || busy}
            onClick={() => void handleNoaaSync()}
            className="inline-flex items-center gap-1.5 rounded-lg bg-obsidian-amber px-3.5 py-1.5 text-xs font-semibold text-obsidian-ground transition hover:brightness-110 disabled:opacity-50"
          >
            <RefreshCw className={`h-3.5 w-3.5 ${busy ? 'animate-spin' : ''}`} />
            Sync NOAA NWS
          </button>
        </div>
      </div>

      {syncStatus && (
        <div
          role="status"
          className="rounded-lg border border-obsidian-amber/40 bg-obsidian-amber/10 px-4 py-2 font-mono text-xs text-obsidian-amber"
        >
          {syncStatus}
        </div>
      )}

      {/* Main Split: Spatial Viewport + Active Swaths & Point Impact */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-12">
        <div className="relative overflow-hidden rounded-xl border border-obsidian-border bg-obsidian-surface xl:col-span-8">
          <div
            ref={mapLibreContainerRef}
            className={isMapLibre ? 'h-[500px] w-full' : 'hidden'}
          />
          <div
            ref={leafletContainerRef}
            className={!isMapLibre ? 'h-[500px] w-full' : 'hidden'}
          />
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-obsidian-border bg-obsidian-surface px-4 py-2 font-mono text-[11px] text-obsidian-muted">
            <span className="inline-flex items-center gap-1.5">
              <Satellite className="h-3.5 w-3.5 text-obsidian-proof" />
              Click any parcel on map to compute linear storm impact & nearest NOAA cell
            </span>
            <span className="tnum">
              Focus: {focusCenter.lat.toFixed(4)}, {focusCenter.lng.toFixed(4)} · Events:{' '}
              {stormList.length} · Parcels: {properties.length}
            </span>
          </div>
        </div>

        <aside className="space-y-4 xl:col-span-4">
          {/* Point Impact Card */}
          <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5">
            <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
              <h2 className="font-display text-base font-bold text-obsidian-ink">
                Parcel Storm Impact Audit
              </h2>
              <Activity className="h-4 w-4 text-obsidian-amber" />
            </div>

            {impactReport ? (
              <div className="mt-3 space-y-2.5 text-xs">
                <div className="flex items-center justify-between rounded-lg bg-obsidian-ground px-3 py-2">
                  <span className="text-obsidian-muted">Impact Severity Index</span>
                  <span className="font-mono text-base font-bold text-obsidian-amber tnum">
                    {impactReport.impactScore ?? 0}/100 ({impactReport.severity || 'NONE'})
                  </span>
                </div>
                {Array.isArray(impactReport.recommendations) &&
                  impactReport.recommendations.slice(0, 3).map((rec: string, i: number) => (
                    <div
                      key={i}
                      className="rounded-lg border border-obsidian-border bg-obsidian-ground/60 px-3 py-2 text-obsidian-ink/90"
                    >
                      {rec}
                    </div>
                  ))}
              </div>
            ) : (
              <p className="mt-3 text-xs text-obsidian-muted">
                Click any point on the map or select an active storm below to inspect hail/wind containment and nearest polygon distance.
              </p>
            )}
          </section>

          {/* Persisted Storm Swaths -> One-Click Dispatch */}
          <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5">
            <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
              <h2 className="font-display text-base font-bold text-obsidian-ink">
                Active Storm Corridors ({stormList.length})
              </h2>
              <Zap className="h-4 w-4 text-obsidian-proof" />
            </div>

            <div className="mt-3 max-h-[260px] space-y-2.5 overflow-y-auto pr-1">
              {stormList.length === 0 ? (
                <p className="text-xs text-obsidian-muted">
                  No persisted storm swaths in window. Click <strong>Sync NOAA NWS</strong> to pull live NWS active alerts.
                </p>
              ) : (
                stormList.map((ev: any) => (
                  <div
                    key={ev.id}
                    className="rounded-lg border border-obsidian-border bg-obsidian-ground p-3 text-xs space-y-2"
                  >
                    <div className="flex items-start justify-between gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          const c = extractGeoCenter(ev.geometry);
                          if (c) void inspectPointImpact(c.lat, c.lng);
                        }}
                        className="text-left font-semibold text-obsidian-ink hover:text-obsidian-amber"
                      >
                        {ev.name}
                      </button>
                      <span className="shrink-0 rounded bg-obsidian-elevated px-2 py-0.5 font-mono text-[10px] text-obsidian-amber">
                        {ev.severity}
                      </span>
                    </div>
                    <div className="flex items-center justify-between text-[11px] text-obsidian-muted tnum">
                      <span>
                        Leads: {ev.leadCount ?? 0} · Jobs: {ev.jobCount ?? 0}
                      </span>
                      <button
                        type="button"
                        disabled={!userAuthenticated || busy}
                        onClick={() => void handleEmitBounties(ev.id, ev.name)}
                        className="rounded bg-obsidian-proof/20 px-2.5 py-1 font-semibold text-obsidian-proof transition hover:bg-obsidian-proof/30"
                      >
                        Emit H3 Bounties
                      </button>
                    </div>
                  </div>
                ))
              )}
            </div>
          </section>
        </aside>
      </div>
    </div>
  );
}
