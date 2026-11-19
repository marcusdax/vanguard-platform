/**
 * Storm Tracking API Routes
 */

const express = require('express');
const router = express.Router();
const noaaService = require('../services/NOAAWeatherService');

// In-memory store for active storms (in production, use Redis/DB)
let activeStorms = new Map();

/**
 * @route GET /api/storms
 * @desc Get active storms
 */
router.get('/', async (req, res) => {
  try {
    // Get active weather alerts
    let alerts = { alerts: [], total: 0 };
    try {
      alerts = await noaaService.getActiveAlerts({ limit: 100 });
    } catch (apiError) {
      console.log('NOAA API error, returning empty alerts:', apiError.message);
    }
    
    // Group alerts by event type
    const stormEvents = alerts.alerts.reduce((acc, alert) => {
      const type = alert.type || 'Unknown';
      if (!acc[type]) acc[type] = [];
      acc[type].push(alert);
      return acc;
    }, {});
    
    res.json({
      active: Array.from(activeStorms.values()),
      alerts: stormEvents,
      totalAlerts: alerts.total,
      lastUpdated: new Date().toISOString()
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/storms/active
 * @desc Get currently active storm systems
 */
router.get('/active', async (req, res) => {
  try {
    const severeEvents = [
      'Tornado Warning',
      'Severe Thunderstorm Warning',
      'Flash Flood Warning',
      'Hurricane Warning',
      'Tropical Storm Warning',
      'Winter Storm Warning',
      'Blizzard Warning'
    ];
    
    let stormAlerts = { alerts: [] };
    try {
      stormAlerts = await noaaService.getActiveAlerts({ limit: 100 });
    } catch (apiError) {
      console.log('NOAA API error:', apiError.message);
    }
    
    // Filter for severe weather alerts
    const severeStorms = stormAlerts.alerts.filter(alert => 
      severeEvents.some(event => alert.type?.includes(event)) ||
      ['Extreme', 'Severe'].includes(alert.severity)
    );
    
    // Calculate storm centers and affected areas
    const stormSystems = severeStorms.map(storm => ({
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
      isActive: new Date(storm.expires) > new Date()
    }));
    
    res.json({
      storms: stormSystems,
      count: stormSystems.length,
      highSeverity: stormSystems.filter(s => s.severity === 'Extreme').length,
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/storms/nearby
 * @desc Find storms near a location
 */
router.get('/nearby', async (req, res) => {
  try {
    const { lat, lon, radius = 100 } = req.query;
    
    if (!lat || !lon) {
      return res.status(400).json({
        error: 'Missing required parameters: lat and lon'
      });
    }
    
    let alerts = { alerts: [] };
    try {
      alerts = await noaaService.getAlertsForArea(parseFloat(lat), parseFloat(lon));
    } catch (apiError) {
      console.log('NOAA API error:', apiError.message);
    }
    
    // Sort by severity
    const sortedAlerts = alerts.alerts.sort((a, b) => {
      const severityOrder = { 'Extreme': 3, 'Severe': 2, 'Moderate': 1, 'Minor': 0 };
      return (severityOrder[b.severity] || 0) - (severityOrder[a.severity] || 0);
    });
    
    res.json({
      location: { lat: parseFloat(lat), lon: parseFloat(lon) },
      radius: parseInt(radius),
      storms: sortedAlerts,
      count: sortedAlerts.length,
      hasSevere: sortedAlerts.some(a => ['Extreme', 'Severe'].includes(a.severity)),
      timestamp: new Date().toISOString()
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/storms/:id
 * @desc Get specific storm details
 */
router.get('/:id', async (req, res) => {
  try {
    // Try to get from active storms first
    const storm = activeStorms.get(req.params.id);
    
    if (storm) {
      return res.json(storm);
    }
    
    // Otherwise search in alerts
    let alerts = { alerts: [] };
    try {
      const allAlerts = await noaaService.getActiveAlerts({ limit: 500 });
      alerts.alerts = allAlerts.alerts;
    } catch (apiError) {
      console.log('NOAA API error:', apiError.message);
    }
    
    const alert = alerts.alerts.find(a => a.id === req.params.id);
    
    if (!alert) {
      return res.status(404).json({
        error: 'Storm not found'
      });
    }
    
    res.json({
      ...alert,
      isHistorical: false
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route POST /api/storms/track
 * @desc Start tracking a storm
 */
router.post('/track', (req, res) => {
  try {
    const { id, name, type, location, severity } = req.body;
    
    if (!id || !name) {
      return res.status(400).json({
        error: 'Missing required fields: id and name'
      });
    }
    
    const stormData = {
      id,
      name,
      type: type || 'Unknown',
      location,
      severity: severity || 'Unknown',
      trackedSince: new Date().toISOString(),
      lastUpdated: new Date().toISOString(),
      history: [{
        timestamp: new Date().toISOString(),
        location,
        severity
      }]
    };
    
    activeStorms.set(id, stormData);
    
    res.json({
      message: 'Storm tracking started',
      storm: stormData
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route POST /api/storms/:id/update
 * @desc Update storm tracking data
 */
router.post('/:id/update', (req, res) => {
  try {
    const storm = activeStorms.get(req.params.id);
    
    if (!storm) {
      return res.status(404).json({
        error: 'Storm not being tracked'
      });
    }
    
    const { location, severity, notes } = req.body;
    
    const update = {
      timestamp: new Date().toISOString(),
      ...location && { location },
      ...severity && { severity },
      ...notes && { notes }
    };
    
    storm.history.push(update);
    storm.lastUpdated = update.timestamp;
    if (location) storm.location = location;
    if (severity) storm.severity = severity;
    
    activeStorms.set(req.params.id, storm);
    
    res.json({
      message: 'Storm updated',
      storm
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route DELETE /api/storms/:id
 * @desc Stop tracking a storm
 */
router.delete('/:id', (req, res) => {
  try {
    const storm = activeStorms.get(req.params.id);
    
    if (!storm) {
      return res.status(404).json({
        error: 'Storm not found'
      });
    }
    
    activeStorms.delete(req.params.id);
    
    res.json({
      message: 'Storm tracking stopped',
      storm: {
        ...storm,
        endedAt: new Date().toISOString()
      }
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/storms/reports/daily
 * @desc Get today's storm reports
 */
router.get('/reports/daily', async (req, res) => {
  try {
    let reports = { wind: [], hail: [], tornado: [] };
    try {
      reports = await noaaService.getStormReports();
    } catch (apiError) {
      console.log('Storm reports API error:', apiError.message);
    }
    
    res.json({
      date: new Date().toISOString().slice(0, 10),
      reports,
      summary: {
        wind: reports.wind.length,
        hail: reports.hail.length,
        tornado: reports.tornado.length,
        total: reports.wind.length + reports.hail.length + reports.tornado.length
      }
    });
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

module.exports = router;
