/**
 * Tenant-scoped AI context loader.
 *
 * Wraps `getStormContext()` (owned by backend-storm) plus cheap company counts
 * so every AI feature works from one snapshot. All lookups are company-scoped
 * and failures degrade to an empty context instead of failing the AI endpoint.
 *
 * Pure shapes/helpers live in `pure.ts` so deterministic heuristics can be
 * tested (and used) without loading the database or storm engine.
 */
import { prisma } from '../../prisma';
import { getStormContext } from '../storm/stormIntelligence';
import type { BBox, StormContext } from '../storm/types';
import {
  emptyCompanyCounts,
  emptyStormContext,
  extractLatLng,
  normalizeStormContext,
  OPEN_LEAD_STATUSES,
  type AiContextBundle,
  type CompanyCounts,
  type LeadDetail,
} from './pure';

export * from './pure';

async function loadStormContextSafe(
  companyId: string,
  opts?: { days?: number; bbox?: BBox; limit?: number }
): Promise<{ storm: StormContext; degraded: boolean }> {
  try {
    const storm = await getStormContext(companyId, opts);
    return { storm: normalizeStormContext(storm), degraded: false };
  } catch (error) {
    console.warn('[ai/context] getStormContext failed; using empty context:', (error as Error).message);
    return { storm: emptyStormContext(), degraded: true };
  }
}

async function loadCompanyCountsSafe(companyId: string): Promise<CompanyCounts> {
  const counts = emptyCompanyCounts();
  try {
    const [leadGroups, jobGroups, company] = await Promise.all([
      prisma.lead.groupBy({
        by: ['status'],
        where: { companyId },
        _count: { _all: true },
      }),
      prisma.job.groupBy({
        by: ['status'],
        where: { companyId },
        _count: { _all: true },
      }),
      prisma.company.findUnique({ where: { id: companyId }, select: { name: true } }),
    ]);

    for (const group of leadGroups) {
      const total = Number((group._count as { _all?: number })?._all ?? 0);
      counts.leadsByStatus[group.status] = total;
      counts.leadTotal += total;
      if (OPEN_LEAD_STATUSES.includes(group.status)) counts.openLeads += total;
    }
    for (const group of jobGroups) {
      const total = Number((group._count as { _all?: number })?._all ?? 0);
      counts.jobsByStatus[group.status] = total;
      counts.jobTotal += total;
      if (group.status === 'ACTIVE') counts.activeJobs += total;
    }
    if (company?.name) counts.companyName = company.name;
    return counts;
  } catch (error) {
    console.warn('[ai/context] company counts failed; using zeros:', (error as Error).message);
    return counts;
  }
}

/** One tenant-scoped snapshot used by stormBrief / mapCopilot. */
export async function loadAiContext(
  companyId: string,
  opts?: { days?: number; bbox?: BBox; limit?: number }
): Promise<AiContextBundle> {
  const [{ storm, degraded }, counts] = await Promise.all([
    loadStormContextSafe(companyId, opts),
    loadCompanyCountsSafe(companyId),
  ]);
  return { generatedAt: new Date().toISOString(), storm, counts, degraded };
}

/** Company-scoped lead lookup for /ai/lead-insights (null = not found). */
export async function getLeadDetail(companyId: string, leadId: string): Promise<LeadDetail | null> {
  const lead = await prisma.lead.findFirst({
    where: { id: leadId, companyId },
    select: {
      id: true,
      firstName: true,
      lastName: true,
      propertyAddress: true,
      propertyLatLng: true,
      status: true,
      score: true,
      stormImpactScore: true,
      stormEventId: true,
      createdAt: true,
    },
  });
  if (!lead) return null;
  const latLng = extractLatLng(lead.propertyLatLng);
  return {
    id: lead.id,
    name: `${lead.firstName ?? ''} ${lead.lastName ?? ''}`.trim() || 'Unnamed lead',
    address: lead.propertyAddress ?? '',
    status: lead.status,
    ...(latLng ? { lat: latLng.lat, lng: latLng.lng } : {}),
    score: typeof lead.score === 'number' ? lead.score : null,
    stormImpactScore: typeof lead.stormImpactScore === 'number' ? lead.stormImpactScore : null,
    stormEventId: lead.stormEventId ?? null,
    createdAt: lead.createdAt.toISOString(),
  };
}
