/**
 * Storm Intelligence Brief (contract §5.3).
 *
 * The deterministic engine computes riskScore/severity/confidence from the
 * storm context; the LLM may only refine wording and ordering. Any LLM failure,
 * timeout or invalid JSON falls back to the deterministic brief with HTTP 200 —
 * the feature must work with zero provider keys.
 */
import { emptyAiContextBundle, filterStormContext, type AiContextBundle } from './pure';
import { routeLLM } from '../../llm/router';
import type { LLMResult } from '../../llm/types';
import type { StormContext, StormEventSummary, StormLeadRef } from '../storm/types';
import type { BBox, StormSeverity } from '../storm/types';

export type StormFocus = 'damage' | 'leads' | 'safety' | 'operations';
export const STORM_FOCUSES: StormFocus[] = ['damage', 'leads', 'safety', 'operations'];

export type StormBriefSeverity = 'NONE' | StormSeverity;

export interface StormRisk {
  riskScore: number;
  severity: StormBriefSeverity;
  confidence: number;
  impactedLeadCount: number;
  impactedJobCount: number;
}

export interface LeadOpportunity {
  area: string;
  reason: string;
  priority: 'HIGH' | 'MEDIUM' | 'LOW';
}

export interface StormBriefBody {
  title: string;
  summary: string;
  severity: StormBriefSeverity;
  riskScore: number;
  confidence: number;
  damageTypes: string[];
  affectedAreas: string[];
  recommendedActions: string[];
  leadOpportunities: LeadOpportunity[];
  safetyNotes: string[];
}

export interface StormBriefContextCounts {
  stormCount: number;
  leadCount: number;
  jobCount: number;
  impactedLeadCount: number;
}

export interface StormBriefResponse {
  generatedAt: string;
  source: 'llm' | 'heuristic';
  providerId: string;
  model: string;
  latencyMs: number;
  fallback: boolean;
  brief: StormBriefBody;
  context: StormBriefContextCounts;
}

export interface StormBriefInput {
  companyId: string;
  userId?: string;
  stormIds?: string[];
  bbox?: BBox;
  focus?: StormFocus;
  days?: number;
}

export interface StormBriefDeps {
  loadContext?: (companyId: string, opts?: { days?: number; bbox?: BBox; limit?: number }) => Promise<AiContextBundle>;
  routeLLM?: (request: Parameters<typeof routeLLM>[0], ctx: Parameters<typeof routeLLM>[1]) => Promise<LLMResult>;
  now?: () => number;
}

const SEVERITY_WEIGHT: Record<string, number> = { EXTREME: 26, SEVERE: 18, MODERATE: 10, MINOR: 5 };

const EVENT_TYPE_LABEL: Record<string, string> = {
  TORNADO: 'Tornado',
  HURRICANE: 'Hurricane',
  SEVERE_THUNDERSTORM: 'Severe thunderstorm',
  FLASH_FLOOD: 'Flash flood',
  WINTER_STORM: 'Winter storm',
  WILDFIRE: 'Wildfire',
};

const EVENT_TYPE_DAMAGE: Record<string, string[]> = {
  TORNADO: ['Wind', 'Structural'],
  HURRICANE: ['Wind', 'Water intrusion'],
  SEVERE_THUNDERSTORM: ['Hail', 'Wind', 'Water intrusion'],
  FLASH_FLOOD: ['Water intrusion'],
  WINTER_STORM: ['Ice damming', 'Water intrusion'],
  WILDFIRE: ['Ember damage', 'Smoke'],
};

const SEVERITY_RANK: Record<string, number> = { EXTREME: 4, SEVERE: 3, MODERATE: 2, MINOR: 1 };

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function truncate(value: string, max: number): string {
  const clean = value.replace(/\s+/g, ' ').trim();
  return clean.length > max ? `${clean.slice(0, max - 1)}…` : clean;
}

function eventLabel(eventType: string | undefined): string {
  if (!eventType) return 'Storm';
  return EVENT_TYPE_LABEL[eventType] ?? eventType.replace(/_/g, ' ').toLowerCase().replace(/^\w/, (c) => c.toUpperCase());
}

function severityRank(severity: string | undefined): number {
  return SEVERITY_RANK[severity ?? ''] ?? 0;
}

function sortedEvents(events: StormEventSummary[]): StormEventSummary[] {
  return [...events].sort((a, b) => severityRank(b.severity) - severityRank(a.severity));
}

/** Derive deterministic risk from the context. The model never sets these. */
export function computeStormRisk(storm: StormContext): StormRisk {
  const events = storm.events ?? [];
  const impactedLeadCount = Number(storm.stats?.leadsInImpact ?? 0);
  const impactedJobCount = Number(storm.stats?.jobsInImpact ?? 0);

  let eventPoints = 0;
  for (const event of events) eventPoints += SEVERITY_WEIGHT[event.severity] ?? SEVERITY_WEIGHT.MINOR;
  eventPoints = Math.min(60, eventPoints);

  const exposure = Math.min(30, Math.round((impactedLeadCount + impactedJobCount * 2) * 3));
  const extremeBonus = (storm.stats?.extremeCount ?? 0) > 0 ? 8 : 0;
  const riskScore = clamp(Math.round(eventPoints + exposure + extremeBonus), 0, 100);

  const scoredSeverity: StormBriefSeverity =
    riskScore >= 80 ? 'EXTREME' : riskScore >= 60 ? 'SEVERE' : riskScore >= 35 ? 'MODERATE' : riskScore >= 1 ? 'MINOR' : 'NONE';

  // Never understate an active warning: the brief severity is at least the
  // highest event severity in the context.
  const highestEventSeverity = events.reduce<StormBriefSeverity>((highest, event) => {
    return severityRank(event.severity) > severityRank(highest) ? (event.severity as StormBriefSeverity) : highest;
  }, 'NONE');
  const severity: StormBriefSeverity =
    severityRank(highestEventSeverity) > severityRank(scoredSeverity) ? highestEventSeverity : scoredSeverity;

  const confidence = clamp(
    Number((0.35 + Math.min(0.35, events.length * 0.08) + Math.min(0.2, impactedLeadCount * 0.02)).toFixed(2)),
    0.2,
    0.92
  );

  return { riskScore, severity, confidence, impactedLeadCount, impactedJobCount };
}

function extractCity(address: string | undefined): string | null {
  if (!address) return null;
  const parts = address.split(',').map((part) => part.trim());
  if (parts.length >= 2 && parts[1]) return parts[1].slice(0, 60);
  return null;
}

function collectAreas(storm: StormContext): string[] {
  const areas: string[] = [];
  const push = (value: string | null | undefined): void => {
    const clean = value?.trim();
    if (clean && !areas.some((existing) => existing.toLowerCase() === clean.toLowerCase())) {
      areas.push(truncate(clean, 80));
    }
  };
  for (const event of sortedEvents(storm.events)) {
    push(event.area);
    push(event.name);
  }
  for (const lead of storm.leads as StormLeadRef[]) push(extractCity(lead.address));
  return areas.slice(0, 6);
}

function collectDamageTypes(storm: StormContext): string[] {
  const types: string[] = [];
  for (const event of storm.events) {
    for (const damage of EVENT_TYPE_DAMAGE[event.eventType] ?? []) {
      if (!types.includes(damage)) types.push(damage);
    }
  }
  return types.slice(0, 6);
}

function focusNote(focus: StormFocus | undefined, risk: StormRisk, storm: StormContext): string {
  switch (focus) {
    case 'damage':
      return `Damage focus: prioritize inspections where roofs were exposed to the highest-severity cells (${risk.impactedLeadCount} leads, ${risk.impactedJobCount} jobs in the impact area).`;
    case 'leads':
      return `Lead focus: ${risk.impactedLeadCount} tracked leads sit in the affected area and are the fastest revenue opportunity.`;
    case 'safety':
      return 'Safety focus: no roof access while lightning or damaging wind is active; verify crew check-ins before dispatch.';
    case 'operations':
      return `Operations focus: stage crews, tarps and material deliveries for ${risk.impactedJobCount} active jobs in the impact area.`;
    default:
      return storm.events.length === 0
        ? 'No active storm events are currently tracked for your service area.'
        : `Highest severity currently tracked: ${storm.events[0]?.severity ?? 'MINOR'}.`;
  }
}

/** Deterministic brief used when no LLM is available or its output is invalid. */
export function buildHeuristicBrief(
  storm: StormContext,
  risk: StormRisk = computeStormRisk(storm),
  focus?: StormFocus
): StormBriefBody {
  const events = sortedEvents(storm.events);
  const areas = collectAreas(storm);
  const damageTypes = collectDamageTypes(storm);
  const top = events[0];

  const title =
    events.length === 0
      ? 'No active storm threats detected'
      : `${eventLabel(top?.eventType)} risk across ${areas[0] ?? 'your service area'}`;

  const summaryParts: string[] = [];
  if (events.length === 0) {
    summaryParts.push(
      `No active storm events are tracked in your service area right now. ${storm.leads.length} leads and ${risk.impactedJobCount} jobs are being monitored.`
    );
  } else {
    summaryParts.push(
      `${events.length} active storm event${events.length === 1 ? '' : 's'} overlap your book of business: ${risk.impactedLeadCount} lead${
        risk.impactedLeadCount === 1 ? '' : 's'
      } and ${risk.impactedJobCount} job${risk.impactedJobCount === 1 ? '' : 's'} in the impact area. Highest severity is ${risk.severity}.`
    );
    if (top) {
      summaryParts.push(
        `${top.name}${top.area ? ` covers ${top.area}` : ''} with ${risk.impactedLeadCount > 0 ? 'direct exposure to open leads' : 'no open leads currently flagged'}.`
      );
    }
  }
  summaryParts.push(focusNote(focus, risk, storm));

  const recommendedActions: string[] = [];
  if (risk.impactedLeadCount > 0) {
    recommendedActions.push(
      `Call the ${risk.impactedLeadCount} impacted lead${risk.impactedLeadCount === 1 ? '' : 's'} within 24 hours`
    );
  }
  if (risk.impactedJobCount > 0) {
    recommendedActions.push(`Schedule inspections on the ${risk.impactedJobCount} impacted job${risk.impactedJobCount === 1 ? '' : 's'}`);
  }
  if (risk.severity === 'EXTREME' || risk.severity === 'SEVERE') {
    recommendedActions.push('Pre-stage tarps, underlayment and water-mitigation crews before the next system arrives');
    recommendedActions.push('Document pre-existing damage with photos before any permanent repair');
  } else if (events.length > 0) {
    recommendedActions.push('Review storm-hit addresses and prepare inspection routes');
  } else {
    recommendedActions.push('Keep monitoring the NOAA feed; no field action is required today');
  }

  const leadOpportunities: LeadOpportunity[] = [];
  for (const event of events) {
    if (leadOpportunities.length >= 4) break;
    const opportunityArea = event.area || extractCity(storm.leads.find((lead) => lead.stormEventId === event.id)?.address) || event.name;
    leadOpportunities.push({
      area: truncate(opportunityArea, 80),
      reason:
        event.leadCount > 0
          ? `${event.leadCount} open lead${event.leadCount === 1 ? '' : 's'} inside the ${risk.severity.toLowerCase()} ${eventLabel(
              event.eventType
            ).toLowerCase()} area`
          : `${eventLabel(event.eventType)} activity recorded; verify whether nearby addresses were affected`,
      priority: severityRank(event.severity) >= 3 ? 'HIGH' : severityRank(event.severity) === 2 ? 'MEDIUM' : 'LOW',
    });
  }

  const safetyNotes: string[] = [];
  if (risk.severity === 'EXTREME' || risk.severity === 'SEVERE') {
    safetyNotes.push('Avoid roof access during active lightning or damaging wind');
  }
  safetyNotes.push('Wear fall protection and keep a spotter when inspecting storm-damaged roofs');

  return {
    title,
    summary: truncate(summaryParts.join(' '), 700),
    severity: risk.severity,
    riskScore: risk.riskScore,
    confidence: risk.confidence,
    damageTypes: damageTypes.length > 0 ? damageTypes : ['No damage indicators detected'],
    affectedAreas: areas.length > 0 ? areas : ['Your service area'],
    recommendedActions: recommendedActions.slice(0, 6),
    leadOpportunities,
    safetyNotes: safetyNotes.slice(0, 4),
  };
}

/* ───────────────────────────── LLM prompt + validation ───────────────────────────── */

function compactEvent(event: StormEventSummary): Record<string, unknown> {
  return {
    id: event.id,
    name: truncate(event.name ?? '', 120),
    eventType: event.eventType,
    severity: event.severity,
    area: event.area ?? null,
    startDate: event.startDate,
    endDate: event.endDate,
    leadCount: event.leadCount,
    jobCount: event.jobCount,
  };
}

function compactLead(lead: StormLeadRef): Record<string, unknown> {
  return {
    id: lead.id,
    name: truncate(lead.name ?? '', 80),
    address: truncate(lead.address ?? '', 140),
    status: lead.status,
    score: lead.score ?? null,
  };
}

export function buildStormBriefMessages(
  storm: StormContext,
  risk: StormRisk,
  input: { focus?: StormFocus; days?: number; counts?: { leadTotal: number; jobTotal: number } }
): Array<{ role: 'system' | 'user'; content: string }> {
  const system = [
    'You are the storm-intelligence analyst for a US roofing contractor CRM.',
    'Write a concise, operational brief the office and field teams can act on today.',
    'Return ONE JSON object only, with no markdown fences and no commentary outside the JSON.',
    'Schema: {"title": string (<=120 chars), "summary": string (<=700 chars), "damageTypes": string[], "affectedAreas": string[], "recommendedActions": string[], "leadOpportunities": [{"area": string, "reason": string, "priority": "HIGH"|"MEDIUM"|"LOW"}], "safetyNotes": string[]}.',
    'Do NOT include riskScore, severity or confidence — those are computed server-side and must not be repeated.',
    'Use only facts present in the user message. Never invent addresses, counts, dates or damage. If the data is empty, say there are no active storm threats and recommend monitoring.',
    'Keep arrays short: at most 6 items; leadOpportunities at most 5 items.',
  ].join(' ');

  const user = JSON.stringify({
    focus: input.focus ?? 'damage',
    days: input.days ?? 7,
    deterministicRisk: { severity: risk.severity, riskScore: risk.riskScore },
    company: input.counts ?? { leadTotal: storm.leads.length, jobTotal: 0 },
    stats: storm.stats,
    events: storm.events.slice(0, 10).map(compactEvent),
    leads: storm.leads.slice(0, 12).map(compactLead),
  });

  return [
    { role: 'system', content: system },
    { role: 'user', content: user },
  ];
}

/** Parse JSON out of a model reply, tolerating markdown fences and prose. */
export function parseBriefJson(content: string | null | undefined): Record<string, unknown> | null {
  if (!content || typeof content !== 'string') return null;
  let text = content.trim();
  if (text.startsWith('```')) {
    text = text.replace(/^```[a-zA-Z]*\s*/, '').replace(/```\s*$/, '').trim();
  }
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start === -1 || end === -1 || end <= start) return null;
  try {
    const parsed = JSON.parse(text.slice(start, end + 1)) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

function asStringArray(value: unknown, maxItems: number, maxLen: number): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string') continue;
    const clean = truncate(item, maxLen);
    if (clean) out.push(clean);
    if (out.length >= maxItems) break;
  }
  return out;
}

function asString(value: unknown, fallback: string, maxLen: number): string {
  if (typeof value !== 'string' || !value.trim()) return fallback;
  return truncate(value, maxLen);
}

function asLeadOpportunities(value: unknown, fallback: LeadOpportunity[]): LeadOpportunity[] {
  if (!Array.isArray(value)) return fallback;
  const out: LeadOpportunity[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const obj = item as Record<string, unknown>;
    const area = typeof obj.area === 'string' ? truncate(obj.area, 80) : '';
    const reason = typeof obj.reason === 'string' ? truncate(obj.reason, 240) : '';
    if (!area || !reason) continue;
    const rawPriority = typeof obj.priority === 'string' ? obj.priority.toUpperCase() : '';
    const priority: LeadOpportunity['priority'] =
      rawPriority === 'HIGH' || rawPriority === 'MEDIUM' || rawPriority === 'LOW' ? (rawPriority as LeadOpportunity['priority']) : 'MEDIUM';
    out.push({ area, reason, priority });
    if (out.length >= 5) break;
  }
  return out.length > 0 ? out : fallback;
}

/**
 * Validate/normalise the model's brief. Field-by-field overlay on the
 * heuristic brief: unknown fields are dropped, invalid values replaced, and
 * riskScore/severity/confidence are always the server-computed values.
 */
export function validateBrief(
  raw: Record<string, unknown> | null,
  risk: StormRisk,
  heuristic: StormBriefBody
): StormBriefBody {
  if (!raw) return heuristic;
  return {
    title: asString(raw.title, heuristic.title, 120),
    summary: asString(raw.summary, heuristic.summary, 700),
    severity: risk.severity,
    riskScore: risk.riskScore,
    confidence: risk.confidence,
    damageTypes: asStringArray(raw.damageTypes, 8, 60).length > 0
      ? asStringArray(raw.damageTypes, 8, 60)
      : heuristic.damageTypes,
    affectedAreas: asStringArray(raw.affectedAreas, 8, 80).length > 0
      ? asStringArray(raw.affectedAreas, 8, 80)
      : heuristic.affectedAreas,
    recommendedActions: asStringArray(raw.recommendedActions, 8, 240).length > 0
      ? asStringArray(raw.recommendedActions, 8, 240)
      : heuristic.recommendedActions,
    leadOpportunities: asLeadOpportunities(raw.leadOpportunities, heuristic.leadOpportunities),
    safetyNotes: asStringArray(raw.safetyNotes, 6, 240).length > 0
      ? asStringArray(raw.safetyNotes, 6, 240)
      : heuristic.safetyNotes,
  };
}

function clampDays(days: number | undefined): number {
  if (typeof days !== 'number' || !Number.isFinite(days)) return 7;
  return Math.min(30, Math.max(1, Math.round(days)));
}

/** Full endpoint implementation: deterministic risk + LLM wording + fallback. */
export async function generateStormBrief(input: StormBriefInput, deps: StormBriefDeps = {}): Promise<StormBriefResponse> {
  const now = deps.now ?? (() => Date.now());
  const startedAt = now();
  const loadContext =
    deps.loadContext ??
    (async (companyId: string, opts?: { days?: number; bbox?: BBox; limit?: number }) => {
      // Deferred import: the storm-engine/DB modules are only needed on a real request.
      const { loadAiContext } = await import('./context');
      return loadAiContext(companyId, opts);
    });
  const callRouter = deps.routeLLM ?? routeLLM;

  const days = clampDays(input.days);
  let bundle: AiContextBundle;
  try {
    bundle = await loadContext(input.companyId, { days, ...(input.bbox ? { bbox: input.bbox } : {}), limit: 60 });
  } catch (error) {
    console.warn('[ai/stormBrief] context unavailable; using empty context:', (error as Error).message);
    bundle = emptyAiContextBundle();
  }
  const storm = filterStormContext(bundle.storm, input.stormIds ?? []);
  const risk = computeStormRisk(storm);
  const heuristic = buildHeuristicBrief(storm, risk, input.focus);

  const contextCounts: StormBriefContextCounts = {
    stormCount: storm.events.length,
    leadCount: storm.leads.length,
    jobCount: risk.impactedJobCount,
    impactedLeadCount: risk.impactedLeadCount,
  };

  const base = {
    generatedAt: new Date().toISOString(),
    context: contextCounts,
  };

  try {
    const result = await callRouter(
      {
        messages: buildStormBriefMessages(storm, risk, {
          focus: input.focus,
          days,
          counts: { leadTotal: bundle.counts.leadTotal, jobTotal: bundle.counts.jobTotal },
        }),
        purpose: 'storm_brief',
        json: true,
        temperature: 0.2,
        maxTokens: 900,
      },
      { companyId: input.companyId, userId: input.userId }
    );

    if (result.fallback) {
      return {
        ...base,
        source: 'heuristic',
        providerId: result.providerId,
        model: result.model,
        latencyMs: result.latencyMs,
        fallback: true,
        brief: heuristic,
      };
    }

    const raw = parseBriefJson(result.content);
    const brief = validateBrief(raw, risk, heuristic);
    return {
      ...base,
      source: raw ? 'llm' : 'heuristic',
      providerId: result.providerId,
      model: result.model,
      latencyMs: result.latencyMs,
      fallback: !raw,
      brief,
    };
  } catch (error) {
    console.warn('[ai/stormBrief] LLM unavailable; serving heuristic brief:', (error as Error).message);
    return {
      ...base,
      source: 'heuristic',
      providerId: 'local',
      model: 'deterministic-v1',
      latencyMs: Math.max(0, now() - startedAt),
      fallback: true,
      brief: heuristic,
    };
  }
}
