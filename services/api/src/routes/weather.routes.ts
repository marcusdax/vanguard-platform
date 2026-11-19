/**
 * Weather API Routes — TypeScript port from Atlas Helios
 * Preserves NOAA NWS integration for RoofPro CRM storm tracking
 */
import { Router } from 'express';
import { AuthRequest } from '../middleware/auth';
import { authMiddleware } from '../middleware/auth';
import noaaService from '../services/NOAAWeatherService';

const router = Router();

// Apply optional auth — weather data can be accessed without auth for initial storm scouting
// but authenticated users get company-scoped data
router.use(authMiddleware);

/**
 * GET /weather/status — Service status
 */
router.get('/status', async (_req: AuthRequest, res, next) => {
  try {
    await noaaService.getGridPoint(32.7767, -96.797);
    res.json({
      status: 'operational',
      provider: 'NOAA National Weather Service',
      endpoint: 'https://api.weather.gov',
      timestamp: new Date().toISOString(),
    });
  } catch (error) {
    res.status(503).json({
      status: 'unavailable',
      error: (error as Error).message,
      timestamp: new Date().toISOString(),
    });
  }
});

/**
 * GET /weather/forecast?lat=..&lon=..
 */
router.get('/forecast', async (req: AuthRequest, res, next) => {
  try {
    const { lat, lon } = req.query;
    if (!lat || !lon) {
      return res.status(400).json({ error: 'Missing required parameters: lat and lon' });
    }
    const forecast = await noaaService.getForecast(Number(lat), Number(lon));
    res.json(forecast);
  } catch (error) { next(error); }
});

/**
 * GET /weather/forecast/hourly?lat=..&lon=..
 */
router.get('/forecast/hourly', async (req: AuthRequest, res, next) => {
  try {
    const { lat, lon } = req.query;
    if (!lat || !lon) {
      return res.status(400).json({ error: 'Missing required parameters: lat and lon' });
    }
    const forecast = await noaaService.getHourlyForecast(Number(lat), Number(lon));
    res.json(forecast);
  } catch (error) { next(error); }
});

/**
 * GET /weather/alerts?limit=..&severity=..&urgency=..&event=..
 */
router.get('/alerts', async (req: AuthRequest, res, next) => {
  try {
    const options: any = {
      limit: req.query.limit ? Number(req.query.limit) : 50,
    };
    if (req.query.severity) options.severity = req.query.severity;
    if (req.query.urgency) options.urgency = req.query.urgency;
    if (req.query.event) options.event = req.query.event;

    try {
      const alerts = await noaaService.getActiveAlerts(options);
      res.json(alerts);
    } catch (apiError) {
      res.json({
        alerts: [],
        total: 0,
        title: 'Active Alerts',
        updated: new Date().toISOString(),
        note: 'NOAA API temporarily unavailable',
      });
    }
  } catch (error) { next(error); }
});

/**
 * GET /weather/alerts/area?lat=..&lon=..
 */
router.get('/alerts/area', async (req: AuthRequest, res, next) => {
  try {
    const { lat, lon } = req.query;
    if (!lat || !lon) {
      return res.status(400).json({ error: 'Missing required parameters: lat and lon' });
    }
    try {
      const alerts = await noaaService.getAlertsForArea(Number(lat), Number(lon));
      res.json(alerts);
    } catch (apiError) {
      res.json({
        location: { lat: Number(lat), lon: Number(lon) },
        alerts: [],
        total: 0,
        note: 'NOAA API temporarily unavailable',
      });
    }
  } catch (error) { next(error); }
});

/**
 * GET /weather/severe?lat=..&lon=..
 */
router.get('/severe', async (req: AuthRequest, res, next) => {
  try {
    const { lat, lon } = req.query;
    if (!lat || !lon) {
      return res.status(400).json({ error: 'Missing required parameters: lat and lon' });
    }
    try {
      const severe = await noaaService.checkSevereWeather(Number(lat), Number(lon));
      res.json(severe);
    } catch (apiError) {
      res.json({
        location: { lat: Number(lat), lon: Number(lon) },
        hasAlerts: false,
        alertCount: 0,
        alerts: [],
        forecastSevere: false,
        currentConditions: null,
        timestamp: new Date().toISOString(),
        note: 'Weather data temporarily unavailable',
      });
    }
  } catch (error) { next(error); }
});

/**
 * POST /weather/batch/severe — Check multiple locations
 */
router.post('/batch/severe', async (req: AuthRequest, res, next) => {
  try {
    const { locations } = req.body;
    if (!Array.isArray(locations)) {
      return res.status(400).json({
        error: 'Request body must include locations array with {lat, lon} objects',
      });
    }

    const results = await Promise.all(
      locations.map(async (loc: { lat: number; lon: number }) => {
        try {
          return await noaaService.checkSevereWeather(loc.lat, loc.lon);
        } catch (err) {
          return {
            location: loc,
            error: (err as Error).message,
            hasAlerts: false,
          };
        }
      })
    );

    res.json({
      results,
      total: results.length,
      severeCount: results.filter((r: any) => r.hasAlerts).length,
      timestamp: new Date().toISOString(),
    });
  } catch (error) { next(error); }
});

/**
 * GET /weather/impact-score?lat=..&lon=.. — Score storm impact for a property
 * RoofPro CRM: Used for lead scoring based on storm severity
 */
router.get('/impact-score', async (req: AuthRequest, res, next) => {
  try {
    const { lat, lon } = req.query;
    if (!lat || !lon) {
      return res.status(400).json({ error: 'Missing required parameters: lat and lon' });
    }
    const score = await noaaService.getStormImpactScore(Number(lat), Number(lon));
    res.json({ ...score, timestamp: new Date().toISOString() });
  } catch (error) { next(error); }
});

export default router;
