/**
 * NOAA Weather Service — TypeScript port from Atlas Helios
 * Integration with National Weather Service API for storm tracking
 * Free, public API for weather data, alerts, forecasts, and radar
 */
import axios from 'axios';
import xml2js from 'xml2js';

export interface GeoPoint {
  lat: number;
  lon: number;
}

export interface LocationInfo {
  lat: number;
  lon: number;
  city?: string;
  state?: string;
  country?: string;
}

export interface ForecastPeriod {
  number: number;
  name: string;
  isDaytime: boolean;
  temperature: number | null;
  temperatureUnit: string;
  windSpeed: string;
  windDirection: string;
  icon: string;
  shortForecast: string;
  detailedForecast: string;
}

export interface Forecast {
  location: LocationInfo;
  periods: ForecastPeriod[];
  updated: string;
}

export interface SeverityLevel {
  Extreme: string;
  Severe: string;
  Moderate: string;
  Minor: string;
  Unknown: string;
}

export const SEVERITY_COLORS: Record<string, string> = {
  Extreme: '#ef4444',
  Severe: '#f97316',
  Moderate: '#fbbf24',
  Minor: '#3b82f6',
  Unknown: '#6b7280',
};

export interface WeatherAlert {
  id: string;
  type: string;
  severity: string;
  urgency: string;
  certainty: string;
  effective: string;
  expires: string;
  onset: string;
  ends: string;
  headline: string;
  description: string;
  instruction: string;
  area: string;
  geometry: any;
  sender: string;
  parameters: any;
}

export interface ActiveAlertsResult {
  alerts: WeatherAlert[];
  total: number;
  title: string;
  updated: string;
}

export interface AreaAlertsResult {
  location: GeoPoint;
  alerts: WeatherAlert[];
  total: number;
}

export interface CurrentConditions {
  temperature: number | null;
  temperatureUnit: string;
  windSpeed: string;
  windDirection: string;
  windGust: string | null;
  humidity: number | null;
  visibility: number | null;
  pressure: number | null;
  conditions: string;
  icon: string;
  rawMessage: string;
}

export interface SevereWeatherResult {
  location: GeoPoint;
  hasAlerts: boolean;
  alertCount: number;
  alerts: WeatherAlert[];
  forecastSevere: boolean;
  currentConditions: CurrentConditions | null;
  timestamp: string;
}

class NOAAWeatherService {
  private baseURL: string;
  private radarURL: string;
  private headers: Record<string, string>;
  private xmlParser: xml2js.Parser;

  constructor() {
    this.baseURL = process.env.NOAA_API_URL || 'https://api.weather.gov';
    this.radarURL = 'https://radar.weather.gov';
    this.headers = {
      'User-Agent': process.env.NOAA_USER_AGENT || '(RoofPro CRM, contact@roofprocrm.com)',
      'Accept': 'application/geo+json',
    };
    this.xmlParser = new xml2js.Parser({ explicitArray: false });
  }

  async getGridPoint(lat: number, lon: number) {
    const response = await axios.get(`${this.baseURL}/points/${lat},${lon}`, { headers: this.headers });
    return response.data;
  }

  async getForecast(lat: number, lon: number): Promise<Forecast> {
    const gridPoint = await this.getGridPoint(lat, lon);
    const forecastUrl = gridPoint.properties.forecast;

    const response = await axios.get(forecastUrl, { headers: this.headers });

    return {
      location: {
        lat,
        lon,
        city: gridPoint.properties.relativeLocation?.properties?.city,
        state: gridPoint.properties.relativeLocation?.properties?.state,
      },
      periods: response.data.properties.periods,
      updated: response.data.properties.updated,
    };
  }

  async getHourlyForecast(lat: number, lon: number): Promise<Forecast> {
    const gridPoint = await this.getGridPoint(lat, lon);
    const hourlyUrl = gridPoint.properties.forecastHourly;

    const response = await axios.get(hourlyUrl, { headers: this.headers });

    return {
      location: { lat, lon },
      periods: response.data.properties.periods.slice(0, 24),
      updated: response.data.properties.updated,
    };
  }

  async getActiveAlerts(options: { severity?: string; urgency?: string; event?: string; limit?: number; point?: string; zone?: string } = {}): Promise<ActiveAlertsResult> {
    let url = `${this.baseURL}/alerts/active`;

    if (options.point) {
      url += `?point=${encodeURIComponent(options.point)}`;
    } else if (options.zone) {
      url += `?zone=${encodeURIComponent(options.zone)}`;
    }

    const response = await axios.get(url, { headers: this.headers });

    return {
      alerts: response.data.features.map((alert: any) => ({
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
        parameters: alert.properties.parameters,
      })),
      total: response.data.features.length,
      title: response.data.title,
      updated: response.data.updated,
    };
  }

  async getAlertsForArea(lat: number, lon: number): Promise<AreaAlertsResult> {
    const response = await axios.get(
      `${this.baseURL}/alerts/active?point=${lat},${lon}`,
      { headers: this.headers }
    );

    return {
      location: { lat, lon },
      alerts: response.data.features.map((alert: any) => ({
        id: alert.properties.id,
        type: alert.properties.event,
        severity: alert.properties.severity,
        urgency: alert.properties.urgency,
        headline: alert.properties.headline,
        description: alert.properties.description,
        effective: alert.properties.effective,
        expires: alert.properties.expires,
        geometry: alert.geometry,
      })),
      total: response.data.features.length,
    };
  }

  async getStormReports(_options: Record<string, unknown> = {}) {
    const spcBaseURL = 'https://www.spc.noaa.gov/climo/reports';
    const today = new Date().toISOString().slice(0, 10).replace(/-/g, '');

    const reports = {
      wind: [] as any[],
      hail: [] as any[],
      tornado: [] as any[],
    };

    try {
      const windResponse = await axios.get(`${spcBaseURL}/${today}_rpts_wind.csv`);
      reports.wind = this.parseCSV(windResponse.data);
    } catch { /* No wind reports */ }

    try {
      const hailResponse = await axios.get(`${spcBaseURL}/${today}_rpts_hail.csv`);
      reports.hail = this.parseCSV(hailResponse.data);
    } catch { /* No hail reports */ }

    try {
      const tornadoResponse = await axios.get(`${spcBaseURL}/${today}_rpts_torn.csv`);
      reports.tornado = this.parseCSV(tornadoResponse.data);
    } catch { /* No tornado reports */ }

    return reports;
  }

  async getRadarStations() {
    const response = await axios.get(`${this.baseURL}/radar/stations`, { headers: this.headers });
    return response.data.features.map((station: any) => ({
      id: station.properties.id,
      name: station.properties.name,
      location: {
        lat: station.geometry.coordinates[1],
        lon: station.geometry.coordinates[0],
      },
      elevation: station.properties.elevation,
      status: station.properties.status,
    }));
  }

  async getZones(type = 'forecast') {
    const response = await axios.get(`${this.baseURL}/zones/${type}`, { headers: this.headers });
    return response.data.features.map((zone: any) => ({
      id: zone.properties.id,
      name: zone.properties.name,
      state: zone.properties.state,
      type: zone.properties.type,
      geometry: zone.geometry,
    }));
  }

  async getObservations(stationId: string): Promise<CurrentConditions> {
    const response = await axios.get(
      `${this.baseURL}/stations/${stationId}/observations/latest`,
      { headers: this.headers }
    );

    const props = response.data.properties;
    return {
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
      rawMessage: props.rawMessage,
    };
  }

  parseCSV(csvText: string): Record<string, string>[] {
    const lines = csvText.trim().split('\n');
    if (lines.length < 2) return [];

    const headers = lines[0].split(',').map((h) => h.trim().toLowerCase());
    const results: Record<string, string>[] = [];

    for (let i = 1; i < lines.length; i++) {
      const values = lines[i].split(',');
      if (values.length === headers.length) {
        const entry: Record<string, string> = {};
        headers.forEach((header, index) => {
          entry[header] = values[index]?.trim() ?? '';
        });
        results.push(entry);
      }
    }

    return results;
  }

  async checkSevereWeather(lat: number, lon: number): Promise<SevereWeatherResult> {
    const [alerts, forecast] = await Promise.all([
      this.getAlertsForArea(lat, lon),
      this.getForecast(lat, lon),
    ]);

    const severeAlerts = alerts.alerts.filter(
      (alert) => ['Severe', 'Extreme'].includes(alert.severity)
    );

    const currentPeriod = forecast.periods[0];
    const severeKeywords = ['storm', 'tornado', 'hurricane', 'tropical', 'severe', 'warning', 'watch'];
    const isSevereForecast = severeKeywords.some((keyword) =>
      currentPeriod?.detailedForecast?.toLowerCase().includes(keyword)
    );

    return {
      location: { lat, lon },
      hasAlerts: severeAlerts.length > 0,
      alertCount: severeAlerts.length,
      alerts: severeAlerts,
      forecastSevere: isSevereForecast,
      currentConditions: null,
      timestamp: new Date().toISOString(),
    };
  }

  // For RoofPro CRM: Score a property location based on nearby storm severity
  async getStormImpactScore(lat: number, lon: number): Promise<{
    score: number;  // 0-100
    severity: string;
    alerts: WeatherAlert[];
  }> {
    const alerts = await this.getAlertsForArea(lat, lon);
    const severityWeights = { Extreme: 100, Severe: 70, Moderate: 40, Minor: 20, Unknown: 0 };

    let score = 0;
    let maxSeverity = 'Unknown';

    for (const alert of alerts.alerts) {
      const weight = severityWeights[alert.severity as keyof typeof severityWeights] || 0;
      score += weight;
    }

    score = Math.min(100, score);

    for (const alert of alerts.alerts) {
      if (severityWeights[alert.severity as keyof typeof severityWeights] >
          severityWeights[maxSeverity as keyof typeof severityWeights]) {
        maxSeverity = alert.severity;
      }
    }

    return {
      score,
      severity: score > 0 ? maxSeverity : 'None',
      alerts: alerts.alerts,
    };
  }
}

export default new NOAAWeatherService();
