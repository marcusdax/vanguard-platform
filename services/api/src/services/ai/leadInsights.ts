/**
 * Lead storm-insight explanation (contract §5.5).
 *
 * The score, severity and drivers come from the deterministic
 * `scoreLeadAgainstStorms()` engine. The LLM may only *explain* them; if it
 * fails or returns invalid JSON we serve a deterministic explanation so the
 * panel always has content.
 */
import { LEAD_STATUSES, type LeadDetail } from './pure';
import { routeLLM } from '../../llm/router';
import type { LLMResult } from '../../llm/types';

export type LeadRiskSeverity = 'NONE' | 'MINOR' | 'MODERATE' | 'SEVERE' | 'EXTREME';

export interface LeadDriver {
  label: string;
  weight: number;
}

export interface LeadScoreResult {
  score: number;
  eventId?: string;
  drivers: LeadDriver[];
}

export interface LeadInsightResponse {
  leadId: string;
  leadName: string;
  score: number;
  severity: LeadRiskSeverity;
  drivers: LeadDriver[];
  explanation: string;
  nextBestAction: string;
  recommendedStatus: string;
  talkTrack: string;
  riskFlags: string[];
  source: 'llm' | 'heuristic';
  providerId?: string;
  model?: string;
  latencyMs?: number;
  generatedAt: string;
}

export interface LeadInsightsInput {
  companyId: string;
  userId?: string;
  leadId: string;
}

export interface LeadInsightsDeps {
  loadLead?: (companyId: string, leadId: string) => Promise<LeadDetail | null>;
  scoreLead?: (companyId: string, leadId: string) => Promise<LeadScoreResult>;
  routeLLM?: (request: Parameters<typeof routeLLM>[0], ctx: Parameters<typeof routeLLM>[1]) => Promise<LLMResult>;
  now?: () => number;
}

const NEXT_STATUS: Record<string, string> = {
  NEW: 'CONTACTED',
  CONTACTED: 'INSPECTION_SCHEDULED',
  INSPECTION_SCHEDULED: 'ESTIMATE_SENT',
  ESTIMATE_SENT: 'ESTIMATE_SENT',
  WON: 'WON',
  LOST: 'LOST',
};

/** 0–100 deterministic score → qualitative severity. */
export function severityFromScore(score: number): LeadRiskSeverity {
  if (!Number.isFinite(score)) return 'NONE';
  if (score >= 80) return 'EXTREME';
  if (score >= 60) return 'SEVERE';
  if (score >= 35) return 'MODERATE';
  if (score >= 15) return 'MINOR';
  return 'NONE';
}

function clampScore(score: unknown): number {
  if (typeof score !== 'number' || !Number.isFinite(score)) return 0;
  return Math.min(100, Math.max(0, Math.round(score)));
}

function sanitizeDrivers(value: unknown): LeadDriver[] {
  if (!Array.isArray(value)) return [];
  const out: LeadDriver[] = [];
  for (const item of value) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    const obj = item as Record<string, unknown>;
    if (typeof obj.label !== 'string' || !obj.label.trim()) continue;
    out.push({
      label: obj.label.trim().slice(0, 80),
      weight: typeof obj.weight === 'number' && Number.isFinite(obj.weight) ? obj.weight : 1,
    });
    if (out.length >= 8) break;
  }
  return out;
}

function driverText(drivers: LeadDriver[]): string {
  if (drivers.length === 0) return 'No specific storm drivers were flagged for this property.';
  return `Key drivers: ${drivers
    .slice(0, 4)
    .map((driver) => `${driver.label.replace(/_/g, ' ').toLowerCase()} (${driver.weight})`)
    .join(', ')}.`;
}

/** Deterministic explanation/action plan used when no LLM is available. */
export function buildHeuristicInsight(
  lead: LeadDetail,
  scoreResult: LeadScoreResult
): { explanation: string; nextBestAction: string; recommendedStatus: string; talkTrack: string; riskFlags: string[] } {
  const score = clampScore(scoreResult.score);
  const severity = severityFromScore(score);
  const drivers = sanitizeDrivers(scoreResult.drivers);

  const riskFlags: string[] = drivers
    .filter((driver) => driver.weight >= 1)
    .slice(0, 4)
    .map((driver) => driver.label.replace(/_/g, ' '));
  if (!lead.lat && !lead.lng) riskFlags.push('No property coordinates on file — scoring confidence is lower');
  if (score >= 60) riskFlags.push('Elevated storm damage probability');
  if (lead.status === 'WON' || lead.status === 'LOST') riskFlags.push('Lead already closed — confirm before re-contacting');

  let nextBestAction: string;
  let talkTrack: string;
  switch (severity) {
    case 'EXTREME':
      nextBestAction = 'Call the homeowner today and dispatch an emergency tarp/inspection crew.';
      talkTrack =
        'Hi, this is the storm response team. A severe storm cell moved directly over your neighborhood and we are booking priority inspections for homes in the impact area. Can we get someone on your roof in the next 24 hours?';
      break;
    case 'SEVERE':
      nextBestAction = 'Call within 24 hours and book a full roof inspection.';
      talkTrack =
        'Hi, we are reaching out because a storm system with reported wind and hail passed through your area. We are offering no-obligation roof inspections this week — would a morning or afternoon slot work better?';
      break;
    case 'MODERATE':
      nextBestAction = 'Reach out this week to schedule an inspection before the next system.';
      talkTrack =
        'Hi, a storm passed near your home recently and we are checking nearby roofs for hidden wind or hail damage. Many repairs are covered by insurance — would you like a quick inspection?';
      break;
    case 'MINOR':
      nextBestAction = 'Add the lead to the storm follow-up list and monitor the next forecast.';
      talkTrack =
        'Hi, we are tracking storms in your area and wanted to offer a free roof check. No pressure — we can schedule something whenever it is convenient.';
      break;
    default:
      nextBestAction = 'No storm impact detected — continue normal follow-up cadence.';
      talkTrack =
        'Hi, this is your roofing team checking in. Let us know if you would like a routine roof inspection or have any questions about your roof.';
  }

  const recommendedStatus = severity === 'NONE' || severity === 'MINOR' ? lead.status : NEXT_STATUS[lead.status] ?? lead.status;
  const explanation = `Storm-impact score ${score}/100 (${severity}). ${driverText(drivers)}${
    scoreResult.eventId ? ' The lead is associated with a tracked storm event.' : ''
  } Recommended next step: ${nextBestAction.toLowerCase()}`;

  return {
    explanation,
    nextBestAction,
    recommendedStatus,
    talkTrack,
    riskFlags: [...new Set(riskFlags)].slice(0, 6),
  };
}

function extractJsonObject(content: string | null | undefined): Record<string, unknown> | null {
  if (!content || typeof content !== 'string') return null;
  let text = content.trim();
  if (text.startsWith('```')) {
    text = text.replace(/^```[a-zA-Z]*\s*/, '').replace(/```\s*$/, '').trim();
  }
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start === -1 || end <= start) return null;
  try {
    const parsed = JSON.parse(text.slice(start, end + 1)) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

function safeString(value: unknown, fallback: string, maxLen: number): string {
  if (typeof value !== 'string' || !value.trim()) return fallback;
  const clean = value.replace(/\s+/g, ' ').trim();
  return clean.length > maxLen ? `${clean.slice(0, maxLen - 1)}…` : clean;
}

function safeStringArray(value: unknown, maxItems: number, maxLen: number): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string' || !item.trim()) continue;
    const clean = item.replace(/\s+/g, ' ').trim();
    out.push(clean.length > maxLen ? `${clean.slice(0, maxLen - 1)}…` : clean);
    if (out.length >= maxItems) break;
  }
  return out;
}

export function buildLeadInsightMessages(
  lead: LeadDetail,
  scoreResult: LeadScoreResult,
  severity: LeadRiskSeverity
): Array<{ role: 'system' | 'user'; content: string }> {
  const system = [
    'You explain a deterministic storm-risk score to a roofing contractor sales rep.',
    'Return ONE JSON object only (no markdown, no prose) with exactly: {"explanation": string, "nextBestAction": string, "recommendedStatus": "NEW"|"CONTACTED"|"INSPECTION_SCHEDULED"|"ESTIMATE_SENT"|"WON"|"LOST", "talkTrack": string, "riskFlags": string[]}.',
    'NEVER change or repeat a different score: the score and severity are computed server-side from geospatial data.',
    'Do not invent storm facts beyond the provided drivers; keep explanation under 600 characters, talkTrack under 600 characters and riskFlags at most 5 short items.',
  ].join(' ');

  const user = JSON.stringify({
    lead: {
      name: lead.name,
      address: lead.address,
      status: lead.status,
      hasCoordinates: Boolean(lead.lat || lead.lng),
      existingLeadScore: lead.score,
    },
    deterministic: {
      score: clampScore(scoreResult.score),
      severity,
      eventId: scoreResult.eventId ?? null,
      drivers: sanitizeDrivers(scoreResult.drivers),
    },
  });

  return [
    { role: 'system', content: system },
    { role: 'user', content: user },
  ];
}

/** Full endpoint implementation. Returns null when the lead is not in the tenant. */
export async function generateLeadInsights(
  input: LeadInsightsInput,
  deps: LeadInsightsDeps = {}
): Promise<LeadInsightResponse | null> {
  const now = deps.now ?? (() => Date.now());
  const startedAt = now();
  const loadLead =
    deps.loadLead ??
    (async (companyId: string, leadId: string) => {
      // Deferred import: DB/storm-engine modules are only needed on a real request.
      const { getLeadDetail } = await import('./context');
      return getLeadDetail(companyId, leadId);
    });
  const scoreLead =
    deps.scoreLead ??
    (async (companyId: string, leadId: string) => {
      const { scoreLeadAgainstStorms } = await import('../storm/stormIntelligence');
      return scoreLeadAgainstStorms(companyId, leadId);
    });
  const callRouter = deps.routeLLM ?? routeLLM;

  const lead = await loadLead(input.companyId, input.leadId);
  if (!lead) return null;

  let scoreResult: LeadScoreResult;
  try {
    const scored = await scoreLead(input.companyId, input.leadId);
    scoreResult = {
      score: clampScore(scored?.score),
      ...(scored?.eventId ? { eventId: scored.eventId } : {}),
      drivers: sanitizeDrivers(scored?.drivers),
    };
  } catch (error) {
    console.warn('[ai/leadInsights] score engine failed; using stored score:', (error as Error).message);
    scoreResult = {
      score: clampScore(lead.stormImpactScore ?? lead.score ?? 0),
      drivers: [],
    };
  }

  const severity = severityFromScore(scoreResult.score);
  const heuristic = buildHeuristicInsight(lead, scoreResult);
  const base = {
    leadId: lead.id,
    leadName: lead.name,
    score: clampScore(scoreResult.score),
    severity,
    drivers: scoreResult.drivers,
    generatedAt: new Date().toISOString(),
  };

  try {
    const result = await callRouter(
      {
        messages: buildLeadInsightMessages(lead, scoreResult, severity),
        purpose: 'lead_insights',
        json: true,
        temperature: 0.3,
        maxTokens: 600,
      },
      { companyId: input.companyId, userId: input.userId }
    );

    if (result.fallback) {
      return {
        ...base,
        ...heuristic,
        source: 'heuristic',
        providerId: result.providerId,
        model: result.model,
        latencyMs: result.latencyMs,
      };
    }

    const raw = extractJsonObject(result.content);
    if (!raw) {
      return {
        ...base,
        ...heuristic,
        source: 'heuristic',
        providerId: result.providerId,
        model: result.model,
        latencyMs: result.latencyMs,
      };
    }

    const rawStatus = typeof raw.recommendedStatus === 'string' ? raw.recommendedStatus.toUpperCase().replace(/[\s-]+/g, '_') : '';
    const recommendedStatus = (LEAD_STATUSES as readonly string[]).includes(rawStatus)
      ? rawStatus
      : heuristic.recommendedStatus;
    const riskFlags = safeStringArray(raw.riskFlags, 5, 120);

    return {
      ...base,
      explanation: safeString(raw.explanation, heuristic.explanation, 1_200),
      nextBestAction: safeString(raw.nextBestAction, heuristic.nextBestAction, 300),
      recommendedStatus,
      talkTrack: safeString(raw.talkTrack, heuristic.talkTrack, 1_200),
      riskFlags: riskFlags.length > 0 ? riskFlags : heuristic.riskFlags,
      source: 'llm',
      providerId: result.providerId,
      model: result.model,
      latencyMs: result.latencyMs,
    };
  } catch (error) {
    console.warn('[ai/leadInsights] LLM unavailable; serving heuristic insight:', (error as Error).message);
    return {
      ...base,
      ...heuristic,
      source: 'heuristic',
      providerId: 'local',
      model: 'deterministic-v1',
      latencyMs: Math.max(0, now() - startedAt),
    };
  }
}
