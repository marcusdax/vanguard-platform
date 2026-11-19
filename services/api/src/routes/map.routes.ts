/**
 * Map Data API Routes — TypeScript port from Atlas Helios
 * Serves GeoJSON, storm markers, radar overlays, and tile server configs
 * Used by RoofPro CRM for map-based storm tracking and property visualization
 */
import { Router } from 'express';
import { AuthRequest } from '../middleware/auth';
import { authMiddleware } from '../middleware/auth';
import noaaService, { SEVERITY_COLORS } from '../services/NOAAWeatherService';
import { prisma } from '../prisma';
import { normalizeGeometry } from '../services/storm/geo';
import { extractLatLng } from '../services/storm/impact';

const router = Router();
router.use(authMiddleware);

/**
 * GET /maps/alerts-geojson — Get weather alerts as GeoJSON
 */
router.get('/alerts-geojson', async (req: AuthRequest, res, next) => {
  try {
    const { severity, event } = req.query;
    let alerts: { alerts: any[] } = { alerts: [] };
    try {
      alerts = await noaaService.getActiveAlerts({
        ...(severity && { severity: severity as string }),
        ...(event && { event: event as string }),
        limit: 100,
      });
    } catch (apiError) {
      console.log('NOAA API error:', (apiError as Error).message);
    }

    const geojson = {
      type: 'FeatureCollection',
      features: alerts.alerts
        .filter((alert) => alert.geometry)
        .map((alert) => ({
          type: 'Feature',
          geometry: alert.geometry,
          properties: {
            id: alert.id,
            type: alert.type,
            severity: alert.severity,
            urgency: alert.urgency,
            headline: alert.headline,
            description: alert.description,
            effective: alert.effective,
            expires: alert.expires,
            area: alert.area,
          },
        })),
    };

    res.json(geojson);
  } catch (error) {
    next(error);
  }
});

/**
 * GET /maps/storm-markers — Get storm locations as map markers
 */
router.get('/storm-markers', async (req: AuthRequest, res, next) => {
  try {
    let alerts: { alerts: any[] } = { alerts: [] };
    try {
      alerts = await noaaService.getActiveAlerts({ limit: 100 });
    } catch (apiError) {
      console.log('NOAA API error:', (apiError as Error).message);
    }

    const markers = alerts.alerts
      .filter((alert) => alert.geometry)
      .map((alert) => {
        let lat: number | undefined;
        let lon: number | undefined;

        if (alert.geometry.type === 'Point') {
          [lon, lat] = alert.geometry.coordinates;
        } else if (alert.geometry.type === 'Polygon') {
          const coords = alert.geometry.coordinates[0];
          const sumLat = coords.reduce((sum: number, c: number[]) => sum + c[1], 0);
          const sumLon = coords.reduce((sum: number, c: number[]) => sum + c[0], 0);
          lat = sumLat / coords.length;
          lon = sumLon / coords.length;
        }

        return {
          id: alert.id,
          lat,
          lon,
          type: alert.type,
          severity: alert.severity,
          urgency: alert.urgency,
          headline: alert.headline,
          expires: alert.expires,
          icon: getStormIcon(alert.type),
          color: SEVERITY_COLORS[alert.severity as keyof typeof SEVERITY_COLORS] || SEVERITY_COLORS.Unknown,
        };
      });

    res.json({ markers, count: markers.length, timestamp: new Date().toISOString() });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /maps/radar-overlay — Get radar overlay information
 */
router.get('/radar-overlay', async (req: AuthRequest, res, next) => {
  try {
    let stations: any[] = [];
    try {
      const allStations = await noaaService.getRadarStations();
      stations = allStations.filter(
        (s) =>
          s.status === 'Operational' &&
          s.location.lon < -60 &&
          s.location.lon > -130
      );
    } catch (apiError) {
      console.log('Radar stations API error:', (apiError as Error).message);
    }

    res.json({
      stations,
      tileServers: [
        {
          name: 'NWS Base Reflectivity',
          url: 'https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/{z}/{x}/{y}.png',
          attribution: 'NOAA National Weather Service',
        },
      ],
      count: stations.length,
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /maps/us-bounds — US map bounds
 */
router.get('/us-bounds', (_req: AuthRequest, res) => {
  res.json({
    bounds: {
      sw: { lat: 24.396308, lon: -124.848974 },
      ne: { lat: 49.384358, lon: -66.885444 },
    },
    center: { lat: 39.8283, lon: -98.5795 },
    zoom: 4,
  });
});

/**
 * GET /maps/tile-servers — Available map tile servers
 */
router.get('/tile-servers', (_req: AuthRequest, res) => {
  res.json({
    baseMaps: [
      {
        name: 'OpenStreetMap',
        url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19,
      },
      {
        name: 'CartoDB Dark',
        url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
        maxZoom: 19,
      },
      {
        name: 'CartoDB Light',
        url: 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
        maxZoom: 19,
      },
      {
        name: 'ESRI Satellite',
        url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        attribution: 'Tiles &copy; Esri',
        maxZoom: 18,
      },
    ],
    overlayMaps: [
      {
        name: 'NWS Radar',
        url: 'https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/{z}/{x}/{y}.png',
        attribution: 'NOAA',
        opacity: 0.6,
      },
      {
        name: 'NWS Warnings',
        url: 'https://warnings.cod.edu/wwa/{z}/{x}/{y}.png',
        attribution: 'College of DuPage',
        opacity: 0.5,
      },
    ],
  });
});

function getStormIcon(type: string): string {
  if (!type) return 'circle';
  const iconMap: Record<string, string> = {
    Tornado: 'tornado',
    'Tornado Warning': 'tornado',
    'Severe Thunderstorm': 'bolt',
    'Severe Thunderstorm Warning': 'bolt',
    Flash: 'water',
    'Flash Flood': 'water',
    'Flash Flood Warning': 'water',
    Hurricane: 'hurricane',
    'Hurricane Warning': 'hurricane',
    'Tropical Storm': 'wind',
    Winter: 'snowflake',
    Blizzard: 'snowflake',
  };
  for (const [key, icon] of Object.entries(iconMap)) {
    if (type.includes(key)) return icon;
  }
  return 'exclamation-circle';
}

/** Title-case a Prisma severity enum for the map UI ('EXTREME' -> 'Extreme'). */
function severityTitle(value: string): string {
  const lower = String(value || '').toLowerCase();
  return lower ? lower.charAt(0).toUpperCase() + lower.slice(1) : 'Unknown';
}

/** Pull the first ring of a Polygon / MultiPolygon as Leaflet [lat,lng] pairs. */
function firstRing(geometry: unknown): Array<[number, number]> {
  const normalized = normalizeGeometry(geometry) as
    | { type?: string; coordinates?: unknown }
    | null
    | undefined;
  if (!normalized || !normalized.coordinates) return [];
  const coordinates = normalized.coordinates as unknown;
  let ring: unknown = coordinates;
  if (normalized.type === 'Polygon') ring = (coordinates as unknown[][])[0];
  else if (normalized.type === 'MultiPolygon') ring = ((coordinates as unknown[][][])[0] || [])[0];
  if (!Array.isArray(ring)) return [];
  const points: Array<[number, number]> = [];
  for (const pair of ring) {
    if (!Array.isArray(pair) || pair.length < 2) continue;
    const lng = Number(pair[0]);
    const lat = Number(pair[1]);
    if (Number.isFinite(lat) && Number.isFinite(lng)) points.push([lat, lng]);
  }
  return points;
}

/**
 * GET /maps/storm-trajectories — company storm-event tracks for the animated
 * trajectory layer (derived from persisted StormEvent geometry; no fake data).
 */
router.get('/storm-trajectories', async (req: AuthRequest, res, next) => {
  try {
    const rows = await prisma.stormEvent.findMany({
      where: { companyId: req.user!.companyId },
      orderBy: { startDate: 'desc' },
      take: 25,
      select: { id: true, name: true, severity: true, startDate: true, geometry: true, metadata: true },
    });

    const trajectories = rows.flatMap((row) => {
      const coordinates = firstRing(row.geometry);
      if (coordinates.length < 2) return [];
      const metadata = (row.metadata && typeof row.metadata === 'object' ? row.metadata : {}) as Record<
        string,
        unknown
      >;
      const speed = Number(metadata.speed);
      const direction = Number(metadata.direction);
      return [
        {
          id: `trajectory:${row.id}`,
          stormId: row.id,
          name: row.name,
          coordinates,
          severity: severityTitle(row.severity),
          timestamp: row.startDate.toISOString(),
          ...(Number.isFinite(speed) ? { speed } : {}),
          ...(Number.isFinite(direction) ? { direction } : {}),
        },
      ];
    });

    res.json({ trajectories, count: trajectories.length, timestamp: new Date().toISOString() });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /maps/property-intelligence — geocoded leads/jobs as map risk nodes.
 * Tenant-scoped; risk comes from the persisted deterministic storm-impact score.
 */
router.get('/property-intelligence', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;
    const [leads, jobs] = await Promise.all([
      prisma.lead.findMany({
        where: { companyId },
        select: {
          id: true,
          firstName: true,
          lastName: true,
          propertyAddress: true,
          propertyLatLng: true,
          stormImpactScore: true,
          status: true,
        },
        take: 250,
      }),
      prisma.job.findMany({
        where: { companyId },
        select: { id: true, name: true, propertyAddress: true, propertyLatLng: true, phase: true },
        take: 150,
      }),
    ]);

    const properties: Array<Record<string, unknown>> = [];
    const pushPoint = (
      id: string,
      address: string | null | undefined,
      latLng: unknown,
      riskScore: number,
      metadata: Record<string, unknown>
    ) => {
      const point = extractLatLng(latLng);
      if (!point) return;
      properties.push({
        id,
        lat: point.lat,
        lng: point.lng,
        address: address || 'Unknown address',
        propertyType: 'Residential',
        riskScore: Math.max(0, Math.min(100, Math.round(riskScore))),
        status: 'LIVE',
        lastSync: new Date().toISOString(),
        metadata,
      });
    };

    for (const lead of leads) {
      pushPoint(
        `lead:${lead.id}`,
        lead.propertyAddress,
        lead.propertyLatLng,
        Number(lead.stormImpactScore) || 0,
        { kind: 'lead', leadId: lead.id, leadStatus: lead.status }
      );
    }
    for (const job of jobs) {
      pushPoint(`job:${job.id}`, job.propertyAddress, job.propertyLatLng, 0, {
        kind: 'job',
        jobId: job.id,
        jobPhase: job.phase,
      });
    }

    const lats = properties.map((p) => Number(p.lat));
    const lngs = properties.map((p) => Number(p.lng));
    const bounds =
      properties.length > 0
        ? {
            north: Math.max(...lats),
            south: Math.min(...lats),
            east: Math.max(...lngs),
            west: Math.min(...lngs),
          }
        : null;

    res.json({
      properties,
      count: properties.length,
      bounds,
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    next(error);
  }
});

export default router;
