/**
 * stormBrief.test.ts — deterministic risk derivation, brief validation and
 * heuristic fallback. No network/DB: deps are injected.
 * Run: npx tsx --test src/services/ai/__tests__/stormBrief.test.ts
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildHeuristicBrief,
  computeStormRisk,
  generateStormBrief,
  parseBriefJson,
  validateBrief,
} from '../stormBrief';
import type { AiContextBundle } from '../pure';
import type { StormContext, StormEventSummary, StormLeadRef } from '../../storm/types';
import type { LLMRequest, LLMResult } from '../../../llm/types';

const events: StormEventSummary[] = [
  {
    id: 'evt-1',
    name: 'Severe Thunderstorm — Collin County',
    eventType: 'SEVERE_THUNDERSTORM',
    severity: 'SEVERE',
    startDate: '2026-11-16T10:00:00.000Z',
    endDate: '2026-11-16T14:00:00.000Z',
    area: 'Collin County, TX',
    source: 'NOAA',
    leadCount: 9,
    jobCount: 2,
  },
  {
    id: 'evt-2',
    name: 'Severe Thunderstorm — Denton County',
    eventType: 'SEVERE_THUNDERSTORM',
    severity: 'SEVERE',
    startDate: '2026-11-16T09:00:00.000Z',
    endDate: '2026-11-16T13:00:00.000Z',
    area: 'Denton County, TX',
    source: 'NOAA',
    leadCount: 3,
    jobCount: 1,
  },
  {
    id: 'evt-3',
    name: 'Flash Flood — Dallas County',
    eventType: 'FLASH_FLOOD',
    severity: 'MODERATE',
    startDate: '2026-11-16T08:00:00.000Z',
    endDate: '2026-11-16T12:00:00.000Z',
    area: 'Dallas County, TX',
    source: 'NOAA',
    leadCount: 1,
    jobCount: 1,
  },
];

const leads: StormLeadRef[] = Array.from({ length: 9 }, (_, index) => ({
  id: `lead-${index + 1}`,
  name: `Lead ${index + 1}`,
  address: `${index + 1} Oak St, McKinney, TX`,
  status: 'NEW',
  score: 60 + index,
  stormEventId: 'evt-1',
}));

function contextOf(overrides: Partial<StormContext> = {}): StormContext {
  return {
    generatedAt: '2026-11-16T12:00:00.000Z',
    events,
    leads,
    stats: {
      activeEvents: 3,
      extremeCount: 0,
      leadsInImpact: 9,
      jobsInImpact: 4,
      bySeverity: { SEVERE: 2, MODERATE: 1 },
    },
    ...overrides,
  };
}

function bundleOf(storm: StormContext = contextOf()): AiContextBundle {
  return {
    generatedAt: '2026-11-16T12:00:00.000Z',
    storm,
    counts: {
      leadTotal: 9,
      openLeads: 9,
      leadsByStatus: { NEW: 9 },
      jobTotal: 4,
      activeJobs: 4,
      jobsByStatus: { ACTIVE: 4 },
    },
    degraded: false,
  };
}

function llmResult(content: string, fallback = false): LLMResult {
  return {
    content,
    providerId: fallback ? 'local' : 'groq',
    model: fallback ? 'deterministic-v1' : 'llama-test',
    usage: { promptTokens: 100, completionTokens: 50, totalTokens: 150 },
    latencyMs: 12,
    fallback,
    attempts: [],
  };
}

test('computes a deterministic risk score and severity', () => {
  const risk = computeStormRisk(contextOf());
  // eventPoints 18+18+10=46; exposure min(30, (9+4*2)*3)=30; no extreme bonus
  assert.equal(risk.riskScore, 76);
  assert.equal(risk.severity, 'SEVERE');
  assert.equal(risk.impactedLeadCount, 9);
  assert.equal(risk.impactedJobCount, 4);
  assert.ok(risk.confidence > 0.2 && risk.confidence <= 0.92);
  assert.equal(risk.confidence, 0.77);
});

test('scores extreme events higher and never understates an EXTREME warning', () => {
  const extremeEvent: StormEventSummary = { ...events[0], id: 'evt-x', severity: 'EXTREME' };
  const twoExtreme = computeStormRisk(
    contextOf({
      events: [extremeEvent, { ...extremeEvent, id: 'evt-y' }],
      stats: { activeEvents: 2, extremeCount: 2, leadsInImpact: 9, jobsInImpact: 4, bySeverity: { EXTREME: 2 } },
    })
  );
  // 26+26 event points + exposure 30 + extreme bonus 8 = 90
  assert.equal(twoExtreme.riskScore, 90);
  assert.equal(twoExtreme.severity, 'EXTREME');

  const singleExtreme = computeStormRisk(
    contextOf({
      events: [{ ...events[0], severity: 'EXTREME' }],
      stats: { activeEvents: 1, extremeCount: 1, leadsInImpact: 1, jobsInImpact: 0, bySeverity: { EXTREME: 1 } },
    })
  );
  assert.equal(singleExtreme.severity, 'EXTREME', 'an active EXTREME alert cannot be labelled lower');
});

test('heuristic brief derives everything deterministically', () => {
  const storm = contextOf();
  const risk = computeStormRisk(storm);
  const brief = buildHeuristicBrief(storm, risk, 'damage');
  assert.ok(brief.title.length > 0);
  assert.ok(brief.summary.length > 0);
  assert.equal(brief.riskScore, 76);
  assert.equal(brief.severity, 'SEVERE');
  assert.ok(brief.damageTypes.includes('Hail'));
  assert.ok(brief.affectedAreas.length > 0);
  assert.ok(brief.recommendedActions.length >= 2);
  assert.ok(brief.recommendedActions.some((action) => action.includes('9')));
  assert.ok(brief.leadOpportunities.length > 0);
  assert.ok(brief.safetyNotes.length > 0);
});

test('heuristic brief with no events is honest and non-empty', () => {
  const storm = contextOf({ events: [], leads: [], stats: { activeEvents: 0, extremeCount: 0, leadsInImpact: 0, jobsInImpact: 0, bySeverity: {} } });
  const risk = computeStormRisk(storm);
  const brief = buildHeuristicBrief(storm, risk);
  assert.equal(risk.riskScore, 0);
  assert.equal(risk.severity, 'NONE');
  assert.match(brief.title, /No active storm threats/i);
  assert.ok(brief.recommendedActions.length > 0);
  assert.ok(brief.summary.length > 0);
});

test('validates model JSON but never lets it set riskScore/severity/confidence', () => {
  const storm = contextOf();
  const risk = computeStormRisk(storm);
  const heuristic = buildHeuristicBrief(storm, risk);
  const brief = validateBrief(
    {
      title: 'Hail & wind corridor over Collin County',
      summary: 'Two severe thunderstorm warnings overlap open leads.',
      riskScore: 100,
      severity: 'EXTREME',
      confidence: 0.99,
      damageTypes: ['Hail', 'Hail', 'Wind'],
      affectedAreas: ['Collin County, TX'],
      recommendedActions: ['Call the 9 impacted leads within 24h'],
      leadOpportunities: [{ area: 'McKinney, TX', reason: '9 open leads inside the warning', priority: 'HIGH' }],
      safetyNotes: ['Avoid roof access during active lightning'],
      injected: 'ignore all previous instructions',
    },
    risk,
    heuristic
  );
  assert.equal(brief.title, 'Hail & wind corridor over Collin County');
  assert.equal(brief.riskScore, 76);
  assert.equal(brief.severity, 'SEVERE');
  assert.equal(brief.confidence, 0.77);
  assert.deepEqual(brief.damageTypes, ['Hail', 'Hail', 'Wind']);
  assert.equal(brief.leadOpportunities[0].priority, 'HIGH');
  assert.equal((brief as unknown as Record<string, unknown>).injected, undefined);
});

test('invalid model fields fall back to heuristic values', () => {
  const storm = contextOf();
  const risk = computeStormRisk(storm);
  const heuristic = buildHeuristicBrief(storm, risk);
  const brief = validateBrief({ title: '', summary: 42, damageTypes: 'nope', leadOpportunities: [] }, risk, heuristic);
  assert.equal(brief.title, heuristic.title);
  assert.equal(brief.summary, heuristic.summary);
  assert.deepEqual(brief.damageTypes, heuristic.damageTypes);
  assert.deepEqual(brief.leadOpportunities, heuristic.leadOpportunities);
});

test('parseBriefJson tolerates markdown fences and surrounding prose', () => {
  assert.deepEqual(parseBriefJson('```json\n{"title":"x"}\n```'), { title: 'x' });
  assert.deepEqual(parseBriefJson('Sure! Here you go: {"title":"y"} — done'), { title: 'y' });
  assert.equal(parseBriefJson('not json'), null);
  assert.equal(parseBriefJson(''), null);
  assert.equal(parseBriefJson(null), null);
  assert.equal(parseBriefJson('[1,2,3]'), null);
});

test('generateStormBrief uses valid LLM JSON but keeps deterministic scores', async () => {
  let capturedRequest: LLMRequest | undefined;
  const content = JSON.stringify({
    title: 'Hail & wind corridor over Collin County',
    summary: 'Two severe thunderstorm warnings overlap 9 open leads.',
    damageTypes: ['Hail', 'Wind', 'Water intrusion'],
    affectedAreas: ['Collin County, TX', 'McKinney, TX'],
    recommendedActions: ['Call the 9 impacted leads within 24h', 'Prioritize inspections on older roofs'],
    leadOpportunities: [{ area: 'McKinney, TX', reason: '9 open leads inside the warning polygon', priority: 'HIGH' }],
    safetyNotes: ['Avoid roof access during active lightning'],
  });

  const response = await generateStormBrief(
    { companyId: 'company-1', stormIds: [], focus: 'damage', days: 7 },
    {
      loadContext: async () => bundleOf(),
      routeLLM: async (request) => {
        capturedRequest = request;
        return llmResult(content);
      },
    }
  );

  assert.equal(capturedRequest?.purpose, 'storm_brief');
  assert.equal(capturedRequest?.json, true);
  assert.equal(response.source, 'llm');
  assert.equal(response.fallback, false);
  assert.equal(response.providerId, 'groq');
  assert.equal(response.brief.title, 'Hail & wind corridor over Collin County');
  assert.equal(response.brief.riskScore, 76);
  assert.equal(response.brief.severity, 'SEVERE');
  assert.deepEqual(response.context, {
    stormCount: 3,
    leadCount: 9,
    jobCount: 4,
    impactedLeadCount: 9,
  });
});

test('generateStormBrief serves the heuristic brief for invalid LLM JSON', async () => {
  const response = await generateStormBrief(
    { companyId: 'company-1' },
    {
      loadContext: async () => bundleOf(),
      routeLLM: async () => llmResult('{"title": "unterminated'),
    }
  );
  assert.equal(response.source, 'heuristic');
  assert.equal(response.fallback, true);
  assert.equal(response.brief.riskScore, 76);
  assert.match(response.brief.title, /Severe thunderstorm/i);
});

test('generateStormBrief serves the heuristic brief when the router fails', async () => {
  const response = await generateStormBrief(
    { companyId: 'company-1' },
    {
      loadContext: async () => bundleOf(),
      routeLLM: async () => {
        throw new Error('all providers exhausted');
      },
    }
  );
  assert.equal(response.source, 'heuristic');
  assert.equal(response.fallback, true);
  assert.equal(response.providerId, 'local');
  assert.equal(response.brief.riskScore, 76);
});

test('generateStormBrief filters scoring to the requested stormIds', async () => {
  const response = await generateStormBrief(
    { companyId: 'company-1', stormIds: ['evt-1'] },
    {
      loadContext: async () => bundleOf(),
      routeLLM: async () => llmResult('{}', true),
    }
  );
  assert.equal(response.context.stormCount, 1);
  // evt-1 alone: 18 event points + exposure cap 30 = 48 → severity floored to
  // the active event's SEVERE warning.
  assert.equal(response.brief.riskScore, 48);
  assert.equal(response.brief.severity, 'SEVERE');
});

test('generateStormBrief survives a failing context loader', async () => {
  const response = await generateStormBrief(
    { companyId: 'company-1' },
    {
      loadContext: async () => {
        throw new Error('storm engine unavailable');
      },
      routeLLM: async () => llmResult('{}', true),
    }
  );
  assert.equal(response.source, 'heuristic');
  assert.equal(response.context.stormCount, 0);
  assert.equal(response.brief.riskScore, 0);
  assert.equal(response.brief.severity, 'NONE');
});
