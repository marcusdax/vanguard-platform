/**
 * Storm Tracking API Routes — TypeScript port from Atlas Helios
 * Provides active storm data, storm tracking, and daily reports
 * Integrated with RoofPro CRM as storm_events entity
 */
import { Router } from 'express';
import { AuthRequest } from '../middleware/auth';
import { authMiddleware, requireRole } from '../middleware/auth';
import noaaService from '../services/NOAAWeatherService';
import { prisma } from '../prisma';

const router = Router();

// In-memory store for actively tracked storms (Redis-backed in production)
let activeStorms: Map<string, any> = new Map();

router.use(authMiddleware);

/**
 * GET /storms/active — Get currently active storm systems
 */
router.get('/active', async (req: AuthRequest, res, next) => {
  try {
    const severeEvents = [
      'Tornado Warning',
      'Severe Thunderstorm Warning',
      'Flash Flood Warning',
      'Hurricane Warning',
      'Tropical Storm Warning',
      'Winter Storm Warning',
      'Blizzard Warning',
    ];

    let stormAlerts: { alerts: any[] } = { alerts: [] };
    try {
      stormAlerts = await noaaService.getActiveAlerts({ limit: 100 });
    } catch (apiError) {
      // NOAA API unavailable
    }

    const severeStorms = stormAlerts.alerts.filter(
      (alert) =>
        severeEvents.some((event) => alert.type?.includes(event)) ||
        ['Extreme', 'Severe'].includes(alert.severity)
    );

    const stormSystems = severeStorms.map((storm) => ({
      id: storm.id,
      type: storm.type,
      severity: storm.severity,
      urgency: storm.urgency,
      location: storm.area,
      effective: storm.effective,
      expires: storm.expires,
      headline: storm.headline,
      description: storm.description,
      geometry: storm.geometry,
      sender: storm.sender,
      isActive: new Date(storm.expires) > new Date(),
    }));

    res.json({
      storms: stormSystems,
      count: stormSystems.length,
      highSeverity: stormSystems.filter((s) => s.severity === 'Extreme').length,
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /storms — Get all storms (alerts grouped by type)
 */
router.get('/', async (req: AuthRequest, res, next) => {
  try {
    let alerts: { alerts: any[]; total: number } = { alerts: [], total: 0 };
    try {
      alerts = await noaaService.getActiveAlerts({ limit: 100 });
    } catch (apiError) {
      console.log('NOAA API error, returning empty alerts:', (apiError as Error).message);
    }

    const stormEvents = alerts.alerts.reduce((acc: Record<string, any[]>, alert) => {
      const type = alert.type || 'Unknown';
      if (!acc[type]) acc[type] = [];
      acc[type].push(alert);
      return acc;
    }, {});

    res.json({
      active: Array.from(activeStorms.values()),
      alerts: stormEvents,
      totalAlerts: alerts.total,
      lastUpdated: new Date().toISOString(),
    });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /storms/nearby?lat=..&lon=..&radius=..
 */
router.get('/nearby', async (req: AuthRequest, res, next) => {
  try {
    const { lat, lon, radius = 100 } = req.query;
    if (!lat || !lon) {
      return res.status(400).json({ error: 'Missing required parameters: lat and lon' });
    }

    let alerts: { alerts: any[] } = { alerts: [] };
    try {
      alerts = await noaaService.getAlertsForArea(Number(lat), Number(lon));
    } catch (apiError) {
      console.log('NOAA API error:', (apiError as Error).message);
    }

    const sortedAlerts = alerts.alerts.sort((a, b) => {
      const severityOrder = { Extreme: 3, Severe: 2, Moderate: 1, Minor: 0 };
      return (severityOrder[b.severity as keyof typeof severityOrder] || 0) -
             (severityOrder[a.severity as keyof typeof severityOrder] || 0);
    });

    res.json({
      location: { lat: Number(lat), lon: Number(lon) },
      radius: Number(radius),
      storms: sortedAlerts,
      count: sortedAlerts.length,
      hasSevere: sortedAlerts.some((a) => ['Extreme', 'Severe'].includes(a.severity)),
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /storms/:id — Get specific storm details
 */
router.get('/:id', async (req: AuthRequest, res, next) => {
  // `/storms/events` is a static collection route declared later in this file;
  // without this guard `:id` would shadow it (pre-existing bug fixed in v1.1).
  if (req.params.id === 'events') return next();
  try {
    // Check in-memory store first
    const storm = activeStorms.get(req.params.id);
    if (storm) {
      return res.json(storm);
    }

    // Search NOAA alerts
    let alerts: { alerts: any[] } = { alerts: [] };
    try {
      const allAlerts = await noaaService.getActiveAlerts({ limit: 500 });
      alerts.alerts = allAlerts.alerts;
    } catch (apiError) {
      console.log('NOAA API error:', (apiError as Error).message);
    }

    const alert = alerts.alerts.find((a) => a.id === req.params.id);
    if (!alert) {
      return res.status(404).json({ error: 'Storm not found' });
    }

    res.json({ ...alert, isHistorical: false });
  } catch (error) {
    next(error);
  }
});

/**
 * POST /storms/track — Start tracking a storm
 */
router.post('/track', requireRole('OWNER', 'MANAGER', 'PROJECT_MANAGER'), async (req: AuthRequest, res, next) => {
  try {
    const { id, name, type, location, severity } = req.body;
    if (!id || !name) {
      return res.status(400).json({ error: 'Missing required fields: id and name' });
    }

    const stormData = {
      id,
      name,
      type: type || 'Unknown',
      location,
      severity: severity || 'Unknown',
      trackedSince: new Date().toISOString(),
      lastUpdated: new Date().toISOString(),
      history: [{ timestamp: new Date().toISOString(), location, severity }],
    };

    activeStorms.set(id, stormData);
    res.json({ message: 'Storm tracking started', storm: stormData });
  } catch (error) {
    next(error);
  }
});

/**
 * POST /storms/:id/update — Update storm tracking data
 */
router.post('/:id/update', async (req: AuthRequest, res, next) => {
  try {
    const storm = activeStorms.get(req.params.id);
    if (!storm) {
      return res.status(404).json({ error: 'Storm not being tracked' });
    }

    const { location, severity, notes } = req.body;
    const update: any = { timestamp: new Date().toISOString() };
    if (location) update.location = location;
    if (severity) update.severity = severity;
    if (notes) update.notes = notes;

    storm.history.push(update);
    storm.lastUpdated = update.timestamp;
    if (location) storm.location = location;
    if (severity) storm.severity = severity;

    activeStorms.set(req.params.id, storm);
    res.json({ message: 'Storm updated', storm });
  } catch (error) {
    next(error);
  }
});

/**
 * DELETE /storms/:id — Stop tracking a storm
 */
router.delete('/:id', async (req: AuthRequest, res, next) => {
  try {
    const storm = activeStorms.get(req.params.id);
    if (!storm) {
      return res.status(404).json({ error: 'Storm not found' });
    }
    activeStorms.delete(req.params.id);
    res.json({ message: 'Storm tracking stopped', storm: { ...storm, endedAt: new Date().toISOString() } });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /storms/reports/daily — Get today's storm reports
 */
router.get('/reports/daily', async (req: AuthRequest, res, next) => {
  try {
    let reports: { wind: any[]; hail: any[]; tornado: any[] } = { wind: [], hail: [], tornado: [] };
    try {
      reports = await noaaService.getStormReports();
    } catch (apiError) {
      console.log('Storm reports API error:', (apiError as Error).message);
    }

    res.json({
      date: new Date().toISOString().slice(0, 10),
      reports,
      summary: {
        wind: reports.wind.length,
        hail: reports.hail.length,
        tornado: reports.tornado.length,
        total: reports.wind.length + reports.hail.length + reports.tornado.length,
      },
    });
  } catch (error) {
    next(error);
  }
});

/**
 * GET /storms/events — Get company storm events from DB
 */
router.get('/events', async (req: AuthRequest, res, next) => {
  try {
    const events = await prisma.stormEvent.findMany({
      where: { companyId: req.user!.companyId },
      orderBy: { startDate: 'desc' },
    });
    res.json({ events, count: events.length });
  } catch (error) { next(error); }
});

/**
 * POST /storms/events — Create a storm event record (links to leads)
 */
router.post('/events', requireRole('OWNER', 'MANAGER', 'SALES_REP'), async (req: AuthRequest, res, next) => {
  try {
    const { name, description, startDate, endDate, severity, eventType, affectedArea } = req.body;
    const event = await prisma.stormEvent.create({
      data: {
        companyId: req.user!.companyId,
        name,
        description,
        startDate: new Date(startDate),
        endDate: new Date(endDate),
        severity,
        eventType,
        affectedArea,
      },
    });
    res.status(201).json({ event });
  } catch (error) { next(error); }
});

export default router;
