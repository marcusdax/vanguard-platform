/**
 * NOAA Weather Service
 * Integration with National Weather Service API
 * Free, public API for weather data, alerts, and forecasts
 */

const axios = require('axios');
const xml2js = require('xml2js');

class NOAAWeatherService {
  constructor() {
    this.baseURL = 'https://api.weather.gov';
    this.radarURL = 'https://radar.weather.gov';
    this.headers = {
      'User-Agent': '(Atlas Helios Platform, contact@atlashelios.com)',
      'Accept': 'application/geo+json'
    };
    this.xmlParser = new xml2js.Parser({ explicitArray: false });
  }

  /**
   * Get grid point data for coordinates
   * Required for getting forecasts at a specific location
   */
  async getGridPoint(lat, lon) {
    try {
      const response = await axios.get(
        `${this.baseURL}/points/${lat},${lon}`,
        { headers: this.headers }
      );
      return response.data;
    } catch (error) {
      console.error('Error fetching grid point:', error.message);
      throw new Error(`Failed to get grid point: ${error.message}`);
    }
  }

  /**
   * Get forecast for a location
   */
  async getForecast(lat, lon) {
    try {
      const gridPoint = await this.getGridPoint(lat, lon);
      const forecastUrl = gridPoint.properties.forecast;
      
      const response = await axios.get(forecastUrl, { headers: this.headers });
      
      return {
        location: {
          lat,
          lon,
          city: gridPoint.properties.relativeLocation?.properties?.city,
          state: gridPoint.properties.relativeLocation?.properties?.state
        },
        periods: response.data.properties.periods,
        updated: response.data.properties.updated
      };
    } catch (error) {
      console.error('Error fetching forecast:', error.message);
      throw new Error(`Failed to get forecast: ${error.message}`);
    }
  }

  /**
   * Get hourly forecast
   */
  async getHourlyForecast(lat, lon) {
    try {
      const gridPoint = await this.getGridPoint(lat, lon);
      const hourlyUrl = gridPoint.properties.forecastHourly;
      
      const response = await axios.get(hourlyUrl, { headers: this.headers });
      
      return {
        location: { lat, lon },
        periods: response.data.properties.periods.slice(0, 24), // Next 24 hours
        updated: response.data.properties.updated
      };
    } catch (error) {
      console.error('Error fetching hourly forecast:', error.message);
      throw new Error(`Failed to get hourly forecast: ${error.message}`);
    }
  }

  /**
   * Get active weather alerts
   */
  async getActiveAlerts(options = {}) {
    try {
      // Build URL - NOAA API doesn't support all params, keep it simple
      let url = `${this.baseURL}/alerts/active`;
      
      // Only add point parameter if provided (most reliable filter)
      if (options.point) {
        url += `?point=${encodeURIComponent(options.point)}`;
      } else if (options.zone) {
        url += `?zone=${encodeURIComponent(options.zone)}`;
      }
      
      const response = await axios.get(url, { headers: this.headers });
      
      return {
        alerts: response.data.features.map(alert => ({
          id: alert.properties.id,
          type: alert.properties.event,
          severity: alert.properties.severity,
          urgency: alert.properties.urgency,
          certainty: alert.properties.certainty,
          effective: alert.properties.effective,
          expires: alert.properties.expires,
          onset: alert.properties.onset,
          ends: alert.properties.ends,
          headline: alert.properties.headline,
          description: alert.properties.description,
          instruction: alert.properties.instruction,
          area: alert.properties.areaDesc,
          geometry: alert.geometry,
          sender: alert.properties.senderName,
          parameters: alert.properties.parameters
        })),
        total: response.data.features.length,
        title: response.data.title,
        updated: response.data.updated
      };
    } catch (error) {
      console.error('Error fetching alerts:', error.message);
      throw new Error(`Failed to get alerts: ${error.message}`);
    }
  }

  /**
   * Get alerts for a specific area
   */
  async getAlertsForArea(lat, lon) {
    try {
      const response = await axios.get(
        `${this.baseURL}/alerts/active?point=${lat},${lon}`,
        { headers: this.headers }
      );
      
      return {
        location: { lat, lon },
        alerts: response.data.features.map(alert => ({
          id: alert.properties.id,
          type: alert.properties.event,
          severity: alert.properties.severity,
          urgency: alert.properties.urgency,
          headline: alert.properties.headline,
          description: alert.properties.description,
          effective: alert.properties.effective,
          expires: alert.properties.expires,
          geometry: alert.geometry
        })),
        total: response.data.features.length
      };
    } catch (error) {
      console.error('Error fetching area alerts:', error.message);
      throw new Error(`Failed to get area alerts: ${error.message}`);
    }
  }

  /**
   * Get severe storm reports
   */
  async getStormReports(options = {}) {
    try {
      // Storm reports are available via the SPC (Storm Prediction Center)
      const spcBaseURL = 'https://www.spc.noaa.gov/climo/reports';
      const today = new Date().toISOString().slice(0, 10).replace(/-/g, '');
      
      // Get today's reports
      const reports = {
        wind: [],
        hail: [],
        tornado: []
      };
      
      try {
        const windResponse = await axios.get(`${spcBaseURL}/${today}_rpts_wind.csv`);
        reports.wind = this.parseCSV(windResponse.data);
      } catch (e) { /* No wind reports */ }
      
      try {
        const hailResponse = await axios.get(`${spcBaseURL}/${today}_rpts_hail.csv`);
        reports.hail = this.parseCSV(hailResponse.data);
      } catch (e) { /* No hail reports */ }
      
      try {
        const tornadoResponse = await axios.get(`${spcBaseURL}/${today}_rpts_torn.csv`);
        reports.tornado = this.parseCSV(tornadoResponse.data);
      } catch (e) { /* No tornado reports */ }
      
      return reports;
    } catch (error) {
      console.error('Error fetching storm reports:', error.message);
      return { wind: [], hail: [], tornado: [] };
    }
  }

  /**
   * Get radar station information
   */
  async getRadarStations() {
    try {
      const response = await axios.get(
        `${this.baseURL}/radar/stations`,
        { headers: this.headers }
      );
      
      return response.data.features.map(station => ({
        id: station.properties.id,
        name: station.properties.name,
        location: {
          lat: station.geometry.coordinates[1],
          lon: station.geometry.coordinates[0]
        },
        elevation: station.properties.elevation,
        status: station.properties.status
      }));
    } catch (error) {
      console.error('Error fetching radar stations:', error.message);
      throw new Error(`Failed to get radar stations: ${error.message}`);
    }
  }

  /**
   * Get weather zones
   */
  async getZones(type = 'forecast') {
    try {
      const response = await axios.get(
        `${this.baseURL}/zones/${type}`,
        { headers: this.headers }
      );
      
      return response.data.features.map(zone => ({
        id: zone.properties.id,
        name: zone.properties.name,
        state: zone.properties.state,
        type: zone.properties.type,
        geometry: zone.geometry
      }));
    } catch (error) {
      console.error('Error fetching zones:', error.message);
      throw new Error(`Failed to get zones: ${error.message}`);
    }
  }

  /**
   * Get current observations for a station
   */
  async getObservations(stationId) {
    try {
      const response = await axios.get(
        `${this.baseURL}/stations/${stationId}/observations/latest`,
        { headers: this.headers }
      );
      
      const props = response.data.properties;
      return {
        stationId,
        timestamp: props.timestamp,
        temperature: props.temperature?.value,
        temperatureUnit: props.temperature?.unitCode,
        windSpeed: props.windSpeed?.value,
        windDirection: props.windDirection?.value,
        windGust: props.windGust?.value,
        humidity: props.relativeHumidity?.value,
        visibility: props.visibility?.value,
        pressure: props.barometricPressure?.value,
        conditions: props.textDescription,
        icon: props.icon,
        rawMessage: props.rawMessage
      };
    } catch (error) {
      console.error('Error fetching observations:', error.message);
      throw new Error(`Failed to get observations: ${error.message}`);
    }
  }

  /**
   * Parse CSV data for storm reports
   */
  parseCSV(csvText) {
    const lines = csvText.trim().split('\n');
    if (lines.length < 2) return [];
    
    const headers = lines[0].split(',').map(h => h.trim().toLowerCase());
    const results = [];
    
    for (let i = 1; i < lines.length; i++) {
      const values = lines[i].split(',');
      if (values.length === headers.length) {
        const entry = {};
        headers.forEach((header, index) => {
          entry[header] = values[index]?.trim();
        });
        results.push(entry);
      }
    }
    
    return results;
  }

  /**
   * Check for severe weather at location
   */
  async checkSevereWeather(lat, lon) {
    try {
      const [alerts, forecast] = await Promise.all([
        this.getAlertsForArea(lat, lon),
        this.getForecast(lat, lon)
      ]);
      
      const severeAlerts = alerts.alerts.filter(alert => 
        ['Severe', 'Extreme'].includes(alert.severity)
      );
      
      // Analyze current forecast for severe conditions
      const currentPeriod = forecast.periods[0];
      const severeKeywords = ['storm', 'tornado', 'hurricane', 'tropical', 'severe', 'warning', 'watch'];
      const isSevereForecast = severeKeywords.some(keyword => 
        currentPeriod?.detailedForecast?.toLowerCase().includes(keyword)
      );
      
      return {
        location: { lat, lon },
        hasAlerts: severeAlerts.length > 0,
        alertCount: severeAlerts.length,
        alerts: severeAlerts,
        forecastSevere: isSevereForecast,
        currentConditions: {
          temperature: currentPeriod?.temperature,
          windSpeed: currentPeriod?.windSpeed,
          shortForecast: currentPeriod?.shortForecast,
          detailedForecast: currentPeriod?.detailedForecast
        },
        timestamp: new Date().toISOString()
      };
    } catch (error) {
      console.error('Error checking severe weather:', error.message);
      throw error;
    }
  }
}

module.exports = new NOAAWeatherService();
