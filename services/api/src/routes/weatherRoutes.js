/**
 * Weather API Routes
 */

const express = require('express');
const router = express.Router();
const noaaService = require('../services/NOAAWeatherService');

/**
 * @route GET /api/weather/status
 * @desc Get weather service status
 */
router.get('/status', async (req, res) => {
  try {
    // Test with a sample coordinate (Dallas, TX)
    await noaaService.getGridPoint(32.7767, -96.7970);
    
    res.json({
      status: 'operational',
      provider: 'NOAA National Weather Service',
      endpoint: 'https://api.weather.gov',
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    res.status(503).json({
      status: 'unavailable',
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/forecast
 * @desc Get forecast for location
 * @query lat - Latitude
 * @query lon - Longitude
 */
router.get('/forecast', async (req, res) => {
  try {
    const { lat, lon } = req.query;
    
    if (!lat || !lon) {
      return res.status(400).json({
        error: 'Missing required parameters: lat and lon'
      });
    }
    
    const forecast = await noaaService.getForecast(parseFloat(lat), parseFloat(lon));
    res.json(forecast);
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/forecast/hourly
 * @desc Get hourly forecast for location
 */
router.get('/forecast/hourly', async (req, res) => {
  try {
    const { lat, lon } = req.query;
    
    if (!lat || !lon) {
      return res.status(400).json({
        error: 'Missing required parameters: lat and lon'
      });
    }
    
    const forecast = await noaaService.getHourlyForecast(parseFloat(lat), parseFloat(lon));
    res.json(forecast);
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/alerts
 * @desc Get active weather alerts
 */
router.get('/alerts', async (req, res) => {
  try {
    const options = {
      ...req.query.severity && { severity: req.query.severity },
      ...req.query.urgency && { urgency: req.query.urgency },
      ...req.query.event && { event: req.query.event },
      limit: req.query.limit || 50
    };
    
    try {
      const alerts = await noaaService.getActiveAlerts(options);
      res.json(alerts);
    } catch (apiError) {
      // Return empty alerts if API fails
      res.json({
        alerts: [],
        total: 0,
        title: 'Active Alerts',
        updated: new Date().toISOString(),
        note: 'NOAA API temporarily unavailable'
      });
    }
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/alerts/area
 * @desc Get alerts for specific area
 */
router.get('/alerts/area', async (req, res) => {
  try {
    const { lat, lon } = req.query;
    
    if (!lat || !lon) {
      return res.status(400).json({
        error: 'Missing required parameters: lat and lon'
      });
    }
    
    try {
      const alerts = await noaaService.getAlertsForArea(parseFloat(lat), parseFloat(lon));
      res.json(alerts);
    } catch (apiError) {
      res.json({
        location: { lat: parseFloat(lat), lon: parseFloat(lon) },
        alerts: [],
        total: 0,
        note: 'NOAA API temporarily unavailable'
      });
    }
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/severe
 * @desc Check for severe weather at location
 */
router.get('/severe', async (req, res) => {
  try {
    const { lat, lon } = req.query;
    
    if (!lat || !lon) {
      return res.status(400).json({
        error: 'Missing required parameters: lat and lon'
      });
    }
    
    try {
      const severe = await noaaService.checkSevereWeather(parseFloat(lat), parseFloat(lon));
      res.json(severe);
    } catch (apiError) {
      // Return safe default if API fails
      res.json({
        location: { lat: parseFloat(lat), lon: parseFloat(lon) },
        hasAlerts: false,
        alertCount: 0,
        alerts: [],
        forecastSevere: false,
        currentConditions: null,
        timestamp: new Date().toISOString(),
        note: 'Weather data temporarily unavailable'
      });
    }
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/radar/stations
 * @desc Get radar station list
 */
router.get('/radar/stations', async (req, res) => {
  try {
    const stations = await noaaService.getRadarStations();
    res.json({ stations, count: stations.length });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/stormreports
 * @desc Get storm reports
 */
router.get('/stormreports', async (req, res) => {
  try {
    const reports = await noaaService.getStormReports(req.query);
    res.json(reports);
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/zones
 * @desc Get weather zones
 */
router.get('/zones', async (req, res) => {
  try {
    const { type = 'forecast' } = req.query;
    const zones = await noaaService.getZones(type);
    res.json({ zones, count: zones.length });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/weather/observations/:stationId
 * @desc Get observations from a weather station
 */
router.get('/observations/:stationId', async (req, res) => {
  try {
    const observations = await noaaService.getObservations(req.params.stationId);
    res.json(observations);
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route POST /api/weather/batch/severe
 * @desc Check severe weather for multiple locations
 */
router.post('/batch/severe', async (req, res) => {
  try {
    const { locations } = req.body;
    
    if (!Array.isArray(locations)) {
      return res.status(400).json({
        error: 'Request body must include locations array with {lat, lon} objects'
      });
    }
    
    const results = await Promise.all(
      locations.map(async (loc) => {
        try {
          return await noaaService.checkSevereWeather(loc.lat, loc.lon);
        } catch (err) {
          return {
            location: loc,
            error: err.message,
            hasAlerts: false
          };
        }
      })
    );
    
    res.json({
      results,
      total: results.length,
      severeCount: results.filter(r => r.hasAlerts).length,
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

module.exports = router;
