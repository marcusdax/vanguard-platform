import { Router } from 'express';
import { authMiddleware } from '../middleware/auth';

// Import route modules
import authRoutes from './auth.routes';
import companyRoutes from './companies.routes';
import leadRoutes from './leads.routes';
import jobRoutes from './jobs.routes';
import fieldRoutes from './field.routes';
import fieldSocialRoutes from './fieldSocial.routes';
import estimateRoutes from './estimates.routes';
import calendarRoutes from './calendar.routes';
import feedRoutes from './feed.routes';
import documentRoutes from './documents.routes';
import notificationRoutes from './notifications.routes';
import integrationRoutes from './integrations.routes';
import stormRoutes from './storms.routes';
import weatherRoutes from './weather.routes';
import mapRoutes from './map.routes';
import materialRoutes from './materials.routes';
import subcontractorRoutes from './subcontractors.routes';
import reportRoutes from './reports.routes';
import aiRoutes from './ai.routes';
import stormIntelRoutes from './stormIntel.routes';
import intelligenceRoutes from './intelligence.routes';
import marketingRoutes from './marketing.routes';
import publicMarketingRoutes from './publicMarketing.routes';
import vanguardRoutes from './vanguard.routes';
import publicVanguardRoutes from './publicVanguard.routes';

// Health check route (no auth required)
const healthRouter = Router();
healthRouter.get('/', (req, res) => {
  res.json({
    status: 'healthy',
    timestamp: new Date().toISOString(),
    service: 'Vanguard Platform API',
    version: '1.0.0',
    integrations: {
      noaa_weather: {
        connected: true,
        endpoint: process.env.NOAA_API_URL || 'https://api.weather.gov',
      },
      database: {
        connected: true,
        provider: 'PostgreSQL',
      },
    },
  });
});

// API v1 router
const v1Router = Router();

// Public routes (no auth required)
v1Router.use('/health', healthRouter);
v1Router.use('/auth', authRoutes);
// Marketing Studio scan-to-lead landing (public, rate-limited)
v1Router.use('/public/m', publicMarketingRoutes);
// Vanguard public catalog & <5KB RFC 6962 verifier endpoint
v1Router.use('/public/vanguard', publicVanguardRoutes);

// Protected routes (require auth)
v1Router.use(authMiddleware);

// API routes
v1Router.use('/companies', companyRoutes);
v1Router.use('/leads', leadRoutes);
v1Router.use('/jobs', jobRoutes);
v1Router.use('/field/social', fieldSocialRoutes);
v1Router.use('/field', fieldRoutes);
v1Router.use('/vanguard', vanguardRoutes);
v1Router.use('/estimates', estimateRoutes);
v1Router.use('/calendar', calendarRoutes);
v1Router.use('/feed', feedRoutes);
v1Router.use('/documents', documentRoutes);
v1Router.use('/notifications', notificationRoutes);
v1Router.use('/integrations', integrationRoutes);
v1Router.use('/storms', stormRoutes);
v1Router.use('/weather', weatherRoutes);
v1Router.use('/maps', mapRoutes);
v1Router.use('/materials', materialRoutes);
v1Router.use('/subcontractors', subcontractorRoutes);
v1Router.use('/reports', reportRoutes);
v1Router.use('/marketing', marketingRoutes);

// AI gateway (FreeLLMAPI-compatible) + storm intelligence
v1Router.use('/ai', aiRoutes);
v1Router.use('/storm-intel', stormIntelRoutes);
v1Router.use('/intelligence', intelligenceRoutes);

// Root endpoint
v1Router.get('/', (req, res) => {
  res.json({
    name: 'Vanguard Platform API',
    version: '1.0.0',
    description: 'Vanguard Field Operations, Trust Engine, Dispatch & Telemetry API',
    endpoints: {
      health: '/api/v1/health',
      auth: '/api/v1/auth',
      companies: '/api/v1/companies',
      leads: '/api/v1/leads',
      jobs: '/api/v1/jobs',
      estimates: '/api/v1/estimates',
      calendar: '/api/v1/calendar',
      feed: '/api/v1/feed',
      documents: '/api/v1/documents',
      notifications: '/api/v1/notifications',
      integrations: '/api/v1/integrations',
      storms: '/api/v1/storms',
      weather: '/api/v1/weather',
      maps: '/api/v1/maps',
      materials: '/api/v1/materials',
      subcontractors: '/api/v1/subcontractors',
      reports: '/api/v1/reports',
      marketing: '/api/v1/marketing',
      ai: '/api/v1/ai',
      stormIntel: '/api/v1/storm-intel',
    },
    ai: {
      status: '/api/v1/ai/status',
      stormBrief: 'POST /api/v1/ai/storm-brief',
      mapCopilot: 'POST /api/v1/ai/map-copilot',
      leadInsights: 'POST /api/v1/ai/lead-insights',
      providers: '/api/v1/ai/providers',
      usage: '/api/v1/ai/usage',
    },
  });
});

export default v1Router;
