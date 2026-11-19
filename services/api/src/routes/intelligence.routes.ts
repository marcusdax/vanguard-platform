/**
 * Intelligence aggregation routes — powers the dashboard command-center panels.
 *
 * Mounted at /api/v1/intelligence behind the global auth middleware.
 * All figures are computed from the company's own rows (no synthetic metrics).
 */
import { Router } from 'express';
import { AuthRequest } from '../middleware/auth';
import { prisma } from '../prisma';

const router = Router();

const OPEN_LEAD_STATUSES = ['NEW', 'CONTACTED', 'INSPECTION_SCHEDULED', 'ESTIMATE_SENT'];
const ACTIVE_JOB_STATUSES = ['ACTIVE', 'ON_HOLD'];

/** GET /intelligence/dashboard — aggregated operating picture for the command center. */
router.get('/dashboard', async (req: AuthRequest, res, next) => {
  try {
    const companyId = req.user!.companyId;

    const [leads, jobs, estimates, stormEvents, aiUsage, providerCount] = await Promise.all([
      prisma.lead.findMany({
        where: { companyId },
        select: { id: true, status: true, stormImpactScore: true, createdAt: true },
      }),
      prisma.job.findMany({
        where: { companyId },
        select: { id: true, status: true, phase: true, budget: true, actualCost: true, createdAt: true },
      }),
      prisma.estimate.findMany({
        where: { companyId },
        select: { id: true, status: true, totalPrice: true, createdAt: true },
      }),
      prisma.stormEvent.findMany({
        where: { companyId },
        select: { id: true, severity: true, startDate: true, endDate: true },
        orderBy: { startDate: 'desc' },
        take: 100,
      }),
      prisma.aIUsage.findMany({
        where: { companyId },
        select: { fallback: true, purpose: true, totalTokens: true, createdAt: true },
        orderBy: { createdAt: 'desc' },
        take: 500,
      }),
      prisma.aIProvider.count({ where: { enabled: true } }),
    ]);

    const now = Date.now();
    const weekAgo = now - 7 * 86_400_000;

    const leadsByStatus: Record<string, number> = {};
    for (const lead of leads) leadsByStatus[lead.status] = (leadsByStatus[lead.status] || 0) + 1;

    const jobsByPhase: Record<string, number> = {};
    for (const job of jobs) jobsByPhase[job.phase] = (jobsByPhase[job.phase] || 0) + 1;

    const openLeads = leads.filter((lead) => OPEN_LEAD_STATUSES.includes(lead.status));
    const scoredLeads = leads.filter((lead) => typeof lead.stormImpactScore === 'number');
    const impactedLeads = scoredLeads.filter((lead) => (lead.stormImpactScore || 0) >= 50);
    const activeJobs = jobs.filter((job) => ACTIVE_JOB_STATUSES.includes(job.status));
    const pipelineValue = estimates
      .filter((estimate) => estimate.status === 'DRAFT' || estimate.status === 'SENT')
      .reduce((sum, estimate) => sum + (estimate.totalPrice || 0), 0);
    const approvedValue = estimates
      .filter((estimate) => estimate.status === 'APPROVED')
      .reduce((sum, estimate) => sum + (estimate.totalPrice || 0), 0);
    const riskIndex = scoredLeads.length
      ? Math.round(
          scoredLeads.reduce((sum, lead) => sum + (lead.stormImpactScore || 0), 0) / scoredLeads.length
        )
      : 0;
    const activeStorms = stormEvents.filter((event) => new Date(event.endDate).getTime() >= now);
    const extremeStorms = stormEvents.filter((event) => event.severity === 'EXTREME');
    const aiRequests = aiUsage.length;
    const fallbackRate = aiRequests
      ? Math.round((aiUsage.filter((row) => row.fallback).length / aiRequests) * 100) / 100
      : 0;

    res.json({
      generatedAt: new Date().toISOString(),
      riskIndex,
      findings: [] as Array<Record<string, unknown>>,
      properties: {
        tracked: new Set(leads.map((lead) => lead.id)).size + activeJobs.length,
        activeProperties: openLeads.length + activeJobs.length,
      },
      storms: {
        events: stormEvents.length,
        activeEvents: activeStorms.length,
        extremeCount: extremeStorms.length,
        lastEventAt: stormEvents[0]?.startDate ?? null,
      },
      crm: {
        leads: {
          total: leads.length,
          byStatus: leadsByStatus,
          open: openLeads.length,
          newThisWeek: leads.filter((lead) => new Date(lead.createdAt).getTime() >= weekAgo).length,
          impacted: impactedLeads.length,
        },
        jobs: {
          total: jobs.length,
          active: activeJobs.length,
          byPhase: jobsByPhase,
          newThisWeek: jobs.filter((job) => new Date(job.createdAt).getTime() >= weekAgo).length,
        },
        estimates: {
          total: estimates.length,
          pipelineValue: Math.round(pipelineValue),
          approvedValue: Math.round(approvedValue),
        },
      },
      ai: {
        requestsLast500: aiRequests,
        fallbackRate,
        providersConfigured: providerCount,
        tokensMetered: aiUsage.reduce((sum, row) => sum + (row.totalTokens || 0), 0),
      },
    });
  } catch (error) {
    next(error);
  }
});

export default router;
