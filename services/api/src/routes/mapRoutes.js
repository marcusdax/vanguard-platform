/**
 * Map Data API Routes
 * Serves GeoJSON and map-related data
 */

const express = require('express');
const router = express.Router();
const noaaService = require('../services/NOAAWeatherService');

/**
 * @route GET /api/maps/alerts-geojson
 * @desc Get weather alerts as GeoJSON
 */
router.get('/alerts-geojson', async (req, res) => {
  try {
    const { severity, event } = req.query;
    
    let alerts = { alerts: [] };
    try {
      alerts = await noaaService.getActiveAlerts({
        ...severity && { severity },
        ...event && { event },
        limit: 100
      });
    } catch (apiError) {
      console.log('NOAA API error:', apiError.message);
    }
    
    const geojson = {
      type: 'FeatureCollection',
      features: alerts.alerts
        .filter(alert => alert.geometry)
        .map(alert => ({
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
            area: alert.area
          }
        }))
    };
    
    res.json(geojson);
  } catch (error) {
    res.status(500).json({
      error: error.message,
      timestamp: new Date().toISOString()
    });
  }
});

/**
 * @route GET /api/maps/storm-markers
 * @desc Get storm locations as map markers
 */
router.get('/storm-markers', async (req, res) => {
  try {
    let alerts = { alerts: [] };
    try {
      alerts = await noaaService.getActiveAlerts({ limit: 100 });
    } catch (apiError) {
      console.log('NOAA API error:', apiError.message);
    }
    
    const markers = alerts.alerts
      .filter(alert => alert.geometry)
      .map(alert => {
        // Calculate centroid for polygon geometries
        let lat, lon;
        
        if (alert.geometry.type === 'Point') {
          [lon, lat] = alert.geometry.coordinates;
        } else if (alert.geometry.type === 'Polygon') {
          // Simple centroid calculation for polygon
          const coords = alert.geometry.coordinates[0];
          const sumLat = coords.reduce((sum, c) => sum + c[1], 0);
          const sumLon = coords.reduce((sum, c) => sum + c[0], 0);
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
          color: getSeverityColor(alert.severity)
        };
      });
    
    res.json({
      markers,
      count: markers.length,
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
 * @route GET /api/maps/radar-overlay
 * @desc Get radar overlay information
 */
router.get('/radar-overlay', async (req, res) => {
  try {
    let stations = [];
    try {
      const allStations = await noaaService.getRadarStations();
      // Filter to active US stations
      stations = allStations.filter(s => 
        s.status === 'Operational' && 
        s.location.lon < -60 && // Continental US
        s.location.lon > -130
      );
    } catch (apiError) {
      console.log('Radar stations API error:', apiError.message);
    }
    
    res.json({
      stations,
      tileServers: [
        {
          name: 'NWS Base Reflectivity',
          url: 'https://radar.weather.gov/ridge/standard/{z}/{x}/{y}_0.png',
          attribution: 'NOAA National Weather Service'
        }
      ],
      count: stations.length,
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
 * @route GET /api/maps/us-bounds
 * @desc Get US map bounds
 */
router.get('/us-bounds', (req, res) => {
  res.json({
    bounds: {
      sw: { lat: 24.396308, lon: -124.848974 },
      ne: { lat: 49.384358, lon: -66.885444 }
    },
    center: { lat: 39.8283, lon: -98.5795 },
    zoom: 4
  });
});

/**
 * @route GET /api/maps/tile-servers
 * @desc Get available map tile servers
 */
router.get('/tile-servers', (req, res) => {
  res.json({
    baseMaps: [
      {
        name: 'OpenStreetMap',
        url: 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19
      },
      {
        name: 'CartoDB Dark',
        url: 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
        maxZoom: 19
      },
      {
        name: 'CartoDB Light',
        url: 'https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}{r}.png',
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>',
        maxZoom: 19
      },
      {
        name: 'ESRI Satellite',
        url: 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        attribution: 'Tiles &copy; Esri',
        maxZoom: 18
      }
    ],
    overlayMaps: [
      {
        name: 'NWS Radar',
        url: 'https://radar.weather.gov/ridge/standard/{z}/{x}/{y}_0.png',
        attribution: 'NOAA',
        opacity: 0.6
      },
      {
        name: 'NWS Warnings',
        url: 'https://warnings.cod.edu/wwa/{z}/{x}/{y}.png',
        attribution: 'College of DuPage',
        opacity: 0.5
      }
    ]
  });
});

// Helper functions
function getStormIcon(type) {
  if (!type) return 'circle';
  
  const iconMap = {
    'Tornado': 'tornado',
    'Tornado Warning': 'tornado',
    'Severe Thunderstorm': 'bolt',
    'Severe Thunderstorm Warning': 'bolt',
    'Flash Flood': 'water',
    'Flash Flood Warning': 'water',
    'Hurricane': 'hurricane',
    'Hurricane Warning': 'hurricane',
    'Tropical Storm': 'wind',
    'Winter Storm': 'snowflake',
    'Blizzard': 'snowflake'
  };
  
  for (const [key, icon] of Object.entries(iconMap)) {
    if (type.includes(key)) return icon;
  }
  
  return 'exclamation-circle';
}

function getSeverityColor(severity) {
  const colors = {
    'Extreme': '#dc2626', // red-600
    'Severe': '#ea580c',  // orange-600
    'Moderate': '#ca8a04', // yellow-600
    'Minor': '#2563eb',   // blue-600
    'Unknown': '#6b7280'  // gray-500
  };
  
  return colors[severity] || colors.Unknown;
}

module.exports = router;
