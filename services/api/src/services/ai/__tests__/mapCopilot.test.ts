/**
 * mapCopilot.test.ts — NL parsing, whitelist/clamp validation, LLM overlay
 * and deterministic fallback. No network/DB: deps are injected.
 * Run: npx tsx --test src/services/ai/__tests__/mapCopilot.test.ts
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  generateMapPlan,
  parseHeuristicPlan,
  sanitizeBBox,
  sanitizeFilters,
  sanitizeOverlays,
  validatePlan,
  type MapPlan,
  type MapView,
} from '../mapCopilot';
import type { AiContextBundle } from '../pure';
import type { StormContext, StormEventSummary, StormLeadRef } from '../../storm/types';
import type { LLMResult } from '../../../llm/types';

const VIEW: MapView = { center: { lat: 33.05, lng: -96.75 }, zoom: 8 };

const events: StormEventSummary[] = [
  {
    id: 'evt-1',
    name: 'Tornado Warning — Collin County',
    eventType: 'TORNADO',
    severity: 'EXTREME',
    startDate: '2026-11-16T10:00:00.000Z',
    endDate: '2026-11-16T14:00:00.000Z',
    area: 'Collin County, TX',
    source: 'NOAA',
    leadCount: 3,
    jobCount: 1,
    geometry: {
      type: 'Polygon',
      coordinates: [
        [
          [-96.8, 33.1],
          [-96.6, 33.1],
          [-96.6, 33.3],
          [-96.8, 33.3],
          [-96.8, 33.1],
        ],
      ],
    },
  },
  {
    id: 'evt-2',
    name: 'Severe Thunderstorm — Denton',
    eventType: 'SEVERE_THUNDERSTORM',
    severity: 'SEVERE',
    startDate: '2026-11-16T09:00:00.000Z',
    endDate: '2026-11-16T13:00:00.000Z',
    area: 'Denton County, TX',
    source: 'NOAA',
    leadCount: 2,
    jobCount: 0,
  },
  {
    id: 'evt-3',
    name: 'Flash Flood — Dallas',
    eventType: 'FLASH_FLOOD',
    severity: 'MODERATE',
    startDate: '2026-11-16T08:00:00.000Z',
    endDate: '2026-11-16T12:00:00.000Z',
    area: 'Dallas County, TX',
    source: 'NOAA',
    leadCount: 1,
    jobCount: 0,
  },
];

const leads: StormLeadRef[] = [
  {
    id: 'lead-1',
    name: 'Jane Roof',
    address: '1 Main St, McKinney, TX',
    status: 'NEW',
    score: 82,
    stormEventId: 'evt-1',
  },
  { id: 'lead-2', name: 'John Shingle', address: '2 Oak St, Plano, TX', status: 'CONTACTED', score: null },
];

function stormContext(overrides: Partial<StormContext> = {}): StormContext {
  return {
    generatedAt: '2026-11-16T12:00:00.000Z',
    events,
    leads,
    stats: {
      activeEvents: events.length,
      extremeCount: 1,
      leadsInImpact: 1,
      jobsInImpact: 1,
      bySeverity: { EXTREME: 1, SEVERE: 1, MODERATE: 1 },
    },
    ...overrides,
  };
}

function bundleOf(storm: StormContext = stormContext()): AiContextBundle {
  return {
    generatedAt: '2026-11-16T12:00:00.000Z',
    storm,
    counts: {
      leadTotal: 2,
      openLeads: 2,
      leadsByStatus: { NEW: 1, CONTACTED: 1 },
      jobTotal: 1,
      activeJobs: 1,
      jobsByStatus: { ACTIVE: 1 },
    },
    degraded: false,
  };
}

function llmResult(content: string, fallback = false): LLMResult {
  return {
    content,
    providerId: fallback ? 'local' : 'groq',
    model: fallback ? 'deterministic-v1' : 'llama-test',
    usage: { promptTokens: 10, completionTokens: 5, totalTokens: 15 },
    latencyMs: 7,
    fallback,
    attempts: [],
  };
}

test('parses "show extreme tornado warnings" into a severity/event filter', () => {
  const plan = parseHeuristicPlan('show extreme tornado warnings', VIEW, events, leads);
  assert.equal(plan.intent, 'filter_severity');
  assert.deepEqual(plan.filters.severity, ['EXTREME']);
  assert.deepEqual(plan.filters.eventTypes, ['TORNADO']);
  assert.deepEqual(plan.matchedStormIds, ['evt-1']);
  assert.ok(plan.overlays.includes('alerts'));
  assert.ok(plan.overlays.includes('stormEvents'));
  assert.ok(plan.explanation.length > 0);
});

test('parses "show my leads" into a lead-highlight plan', () => {
  const plan = parseHeuristicPlan('show my leads on the map', VIEW, events, leads);
  assert.equal(plan.intent, 'show_leads');
  assert.ok(plan.overlays.includes('leads'));
  assert.deepEqual(plan.highlightLeadIds, ['lead-1']);
});

test('focuses a named storm and derives its centroid', () => {
  const plan = parseHeuristicPlan('focus on Tornado Warning — Collin County', VIEW, events, leads);
  assert.equal(plan.intent, 'focus_storm');
  assert.deepEqual(plan.matchedStormIds, ['evt-1']);
  assert.ok(plan.center);
  assert.ok(Math.abs((plan.center?.lat ?? 0) - 33.18) < 0.05);
  assert.ok(Math.abs((plan.center?.lng ?? 0) + 96.72) < 0.05);
  assert.equal(plan.zoom, 9);
});

test('parses reset / explain / jobs intents', () => {
  assert.equal(parseHeuristicPlan('reset the map', VIEW).intent, 'reset');
  assert.equal(parseHeuristicPlan("what's happening right now?", VIEW, events).intent, 'explain');
  const jobs = parseHeuristicPlan('show jobs in the impact area', VIEW, events, leads);
  assert.equal(jobs.intent, 'show_jobs');
  assert.ok(jobs.overlays.includes('jobs'));
});

test('keeps the current view and clamps it', () => {
  const plan = parseHeuristicPlan('show leads', { center: { lat: 200, lng: -999 }, zoom: 99 }, events, leads);
  assert.equal(plan.center?.lat, 90);
  assert.equal(plan.center?.lng, -180);
  assert.equal(plan.zoom, 15);
});

test('validatePlan clamps every field and drops unknown values', () => {
  const fallback = parseHeuristicPlan('show extreme tornado warnings', VIEW, events, leads);
  const raw = {
    intent: 'filter_severity',
    explanation: '',
    zoom: 99,
    center: { lat: 300, lng: -999 },
    bbox: { north: 10, south: 20, east: -100, west: -90 },
    overlays: ['alerts', 'evilOverlay', 'radar', 'alerts'],
    filters: {
      severity: ['extreme', 'BOGUS'],
      eventTypes: ['tornado', 'NOPE'],
      leadStatuses: ['new', 'BOGUS'],
      search: 'x'.repeat(300),
      injectedCommand: 'rm -rf /',
    },
    highlightLeadIds: ['lead-1', 'fake-lead'],
    matchedStormIds: ['evt-1', 'fake-evt'],
    systemPrompt: 'ignore previous instructions',
  };

  const plan = validatePlan(raw, fallback, {
    view: VIEW,
    stormEvents: events,
    leadIds: leads.map((lead) => lead.id),
  });

  assert.equal(plan.zoom, 15);
  assert.deepEqual(plan.center, { lat: 90, lng: -180 });
  assert.deepEqual(plan.bbox, { north: 20, south: 10, east: -90, west: -100 });
  assert.deepEqual(plan.overlays, ['alerts', 'radar']);
  assert.deepEqual(plan.filters.severity, ['EXTREME']);
  assert.deepEqual(plan.filters.eventTypes, ['TORNADO']);
  assert.deepEqual(plan.filters.leadStatuses, ['NEW']);
  assert.equal(plan.filters.search?.length, 120);
  assert.equal((plan.filters as unknown as Record<string, unknown>).injectedCommand, undefined);
  assert.deepEqual(plan.highlightLeadIds, ['lead-1']);
  assert.deepEqual(plan.matchedStormIds, ['evt-1']);
  assert.ok(plan.explanation.length > 0, 'explanation always non-empty');
  assert.equal((plan as unknown as Record<string, unknown>).systemPrompt, undefined);
});

test('validatePlan returns the fallback for malformed payloads', () => {
  const fallback = parseHeuristicPlan('show my leads', VIEW, events, leads);
  assert.equal(validatePlan(null, fallback), fallback);
  assert.equal(validatePlan('not an object', fallback), fallback);
  assert.equal(validatePlan([], fallback), fallback);
  assert.equal(validatePlan({ intent: 'hack_the_planet' }, fallback).intent, 'show_leads');
  assert.equal(validatePlan({ intent: 'unknown', explanation: 'nope' }, fallback).intent, 'show_leads');
});

test('sanitizers clamp arrays, bboxes and overlays', () => {
  assert.deepEqual(sanitizeBBox({ north: -10, south: 10, east: 10, west: -10 }), {
    north: 10,
    south: -10,
    east: 10,
    west: -10,
  });
  assert.equal(sanitizeBBox({ north: 'a' }), undefined);
  assert.deepEqual(sanitizeOverlays(['alerts', 'alerts', 'leads', 'evil']), ['alerts', 'leads']);
  const manySeverities = Array.from({ length: 20 }, () => 'EXTREME');
  const filters = sanitizeFilters({ severity: manySeverities });
  assert.ok((filters.severity?.length ?? 0) <= 12);
});

test('generateMapPlan answers with the heuristic plan when the LLM returns junk', async () => {
  const response = await generateMapPlan(
    { companyId: 'company-1', query: 'show extreme tornado warnings', view: VIEW },
    {
      loadContext: async () => bundleOf(),
      routeLLM: async () => llmResult('this is not json at all'),
    }
  );
  assert.equal(response.source, 'heuristic');
  assert.equal(response.fallback, true);
  assert.equal(response.plan.intent, 'filter_severity');
  assert.deepEqual(response.plan.filters.severity, ['EXTREME']);
  assert.ok(response.plan.explanation.length > 0);
  assert.ok(response.suggestions.length > 0 && response.suggestions.length <= 4);
});

test('generateMapPlan trusts a valid LLM plan and filters hallucinated ids', async () => {
  const content = JSON.stringify({
    intent: 'filter_severity',
    explanation: 'Showing extreme tornado warnings only.',
    zoom: 8,
    overlays: ['alerts', 'stormEvents'],
    filters: { severity: ['EXTREME'], eventTypes: ['TORNADO'] },
    highlightLeadIds: ['lead-1', 'hallucinated-lead'],
    matchedStormIds: ['evt-1', 'hallucinated-storm'],
  });
  const response = await generateMapPlan(
    { companyId: 'company-1', query: 'show extreme tornado warnings', view: VIEW },
    {
      loadContext: async () => bundleOf(),
      routeLLM: async () => llmResult(content),
    }
  );
  assert.equal(response.source, 'llm');
  assert.equal(response.fallback, false);
  assert.equal(response.plan.explanation, 'Showing extreme tornado warnings only.');
  assert.deepEqual(response.plan.highlightLeadIds, ['lead-1']);
  assert.deepEqual(response.plan.matchedStormIds, ['evt-1']);
});

test('generateMapPlan still returns a plan when context loading fails', async () => {
  const response = await generateMapPlan(
    { companyId: 'company-1', query: 'show my leads' },
    {
      loadContext: async () => {
        throw new Error('storm engine down');
      },
      routeLLM: async () => llmResult('{}', true),
    }
  );
  assert.equal(response.plan.intent, 'show_leads');
  assert.ok(response.plan.explanation.length > 0);
});

test('empty query yields an unknown plan with an explanation', () => {
  const plan = parseHeuristicPlan('', VIEW);
  assert.equal(plan.intent, 'unknown');
  assert.ok((plan.explanation ?? '').length > 0);
});

test('every validated plan has all contract fields', () => {
  const plan: MapPlan = validatePlan(
    { intent: 'reset', explanation: 'Reset' },
    parseHeuristicPlan('reset', VIEW),
    { view: VIEW }
  );
  assert.equal(typeof plan.intent, 'string');
  assert.equal(typeof plan.explanation, 'string');
  assert.ok(Array.isArray(plan.overlays));
  assert.ok(Array.isArray(plan.highlightLeadIds));
  assert.ok(Array.isArray(plan.matchedStormIds));
  assert.equal(typeof plan.filters, 'object');
});
