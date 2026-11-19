/**
 * AI Map Copilot (contract §5.4) — natural language → validated `MapPlan`.
 *
 * Security property: LLM output is never executed as free text. It is parsed as
 * JSON and validated field-by-field against whitelists with clamping; unknown
 * keys are dropped. A deterministic regex parser produces the same shape with
 * zero providers, so the copilot works offline.
 */
import { emptyAiContextBundle, LEAD_STATUSES, type AiContextBundle } from './pure';
import { routeLLM } from '../../llm/router';
import type { LLMResult } from '../../llm/types';
import type { BBox, StormContext, StormEventSummary, StormLeadRef } from '../storm/types';

export type MapIntent =
  | 'focus_storm'
  | 'filter_severity'
  | 'show_leads'
  | 'show_jobs'
  | 'reset'
  | 'explain'
  | 'unknown';

export type MapOverlay = 'alerts' | 'stormEvents' | 'hailSwath' | 'leads' | 'jobs' | 'radar';

export const MAP_INTENTS: MapIntent[] = [
  'focus_storm',
  'filter_severity',
  'show_leads',
  'show_jobs',
  'reset',
  'explain',
  'unknown',
];
export const MAP_OVERLAYS: MapOverlay[] = ['alerts', 'stormEvents', 'hailSwath', 'leads', 'jobs', 'radar'];
export const MAP_SEVERITIES = ['MINOR', 'MODERATE', 'SEVERE', 'EXTREME'];
export const MAP_EVENT_TYPES = [
  'TORNADO',
  'HURRICANE',
  'SEVERE_THUNDERSTORM',
  'FLASH_FLOOD',
  'WINTER_STORM',
  'WILDFIRE',
];

const MAX_ARRAY = 12;
const MIN_ZOOM = 3;
const MAX_ZOOM = 15;
const DEFAULT_ZOOM = 6;

export interface MapView {
  center: { lat: number; lng: number };
  zoom: number;
}

export interface MapPlanFilters {
  severity?: string[];
  eventTypes?: string[];
  leadStatuses?: string[];
  search?: string;
}

export interface MapPlan {
  intent: MapIntent;
  explanation: string;
  center?: { lat: number; lng: number };
  zoom?: number;
  bbox?: BBox;
  overlays: MapOverlay[];
  filters: MapPlanFilters;
  highlightLeadIds: string[];
  matchedStormIds: string[];
}

export interface MapCopilotResponse {
  generatedAt: string;
  source: 'llm' | 'heuristic';
  providerId: string;
  model: string;
  latencyMs: number;
  fallback: boolean;
  plan: MapPlan;
  suggestions: string[];
}

export interface MapCopilotInput {
  companyId: string;
  userId?: string;
  query: string;
  view?: MapView;
}

export interface MapCopilotDeps {
  loadContext?: (companyId: string, opts?: { days?: number; limit?: number }) => Promise<AiContextBundle>;
  routeLLM?: (request: Parameters<typeof routeLLM>[0], ctx: Parameters<typeof routeLLM>[1]) => Promise<LLMResult>;
  now?: () => number;
}

/* ───────────────────────────── clamping / sanitising ───────────────────────────── */

function truncate(value: string, max: number): string {
  const clean = value.replace(/\s+/g, ' ').trim();
  return clean.length > max ? `${clean.slice(0, max - 1)}…` : clean;
}

export function clampZoom(value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round(value)));
}

export function clampLat(value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  return Math.min(90, Math.max(-90, value));
}

export function clampLng(value: unknown): number | undefined {
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  return Math.min(180, Math.max(-180, value));
}

export function sanitizeCenter(value: unknown): { lat: number; lng: number } | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const obj = value as Record<string, unknown>;
  const lat = clampLat(obj.lat);
  const lng = clampLng(obj.lng ?? obj.lon);
  if (lat === undefined || lng === undefined) return undefined;
  return { lat, lng };
}

/** Normalise a bbox: valid ranges, correct orientation, never inverted. */
export function sanitizeBBox(value: unknown): BBox | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const obj = value as Record<string, unknown>;
  const north = clampLat(obj.north);
  const south = clampLat(obj.south);
  const east = clampLng(obj.east);
  const west = clampLng(obj.west);
  if (north === undefined || south === undefined || east === undefined || west === undefined) return undefined;
  return {
    north: Math.max(north, south),
    south: Math.min(north, south),
    east: Math.max(east, west),
    west: Math.min(east, west),
  };
}

function sanitizeStringArray(value: unknown, maxItems: number, maxLen: number): string[] {
  if (!Array.isArray(value)) return [];
  const out: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string') continue;
    const clean = truncate(item, maxLen);
    if (clean && !out.includes(clean)) out.push(clean);
    if (out.length >= maxItems) break;
  }
  return out;
}

export function sanitizeOverlays(value: unknown): MapOverlay[] {
  if (!Array.isArray(value)) return [];
  const out: MapOverlay[] = [];
  for (const item of value) {
    if (typeof item !== 'string') continue;
    const overlay = item.trim() as MapOverlay;
    if (MAP_OVERLAYS.includes(overlay) && !out.includes(overlay)) out.push(overlay);
    if (out.length >= MAP_OVERLAYS.length) break;
  }
  return out;
}

export function sanitizeFilters(value: unknown): MapPlanFilters {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const obj = value as Record<string, unknown>;
  const filters: MapPlanFilters = {};

  const severities = sanitizeStringArray(obj.severity, MAX_ARRAY, 20)
    .map((item) => item.toUpperCase())
    .filter((item) => MAP_SEVERITIES.includes(item));
  if (severities.length > 0) filters.severity = [...new Set(severities)];

  const eventTypes = sanitizeStringArray(obj.eventTypes, MAX_ARRAY, 40)
    .map((item) => item.toUpperCase().replace(/[\s-]+/g, '_'))
    .filter((item) => MAP_EVENT_TYPES.includes(item));
  if (eventTypes.length > 0) filters.eventTypes = [...new Set(eventTypes)];

  const leadStatuses = sanitizeStringArray(obj.leadStatuses, MAX_ARRAY, 40)
    .map((item) => item.toUpperCase().replace(/[\s-]+/g, '_'))
    .filter((item) => (LEAD_STATUSES as readonly string[]).includes(item));
  if (leadStatuses.length > 0) filters.leadStatuses = [...new Set(leadStatuses)];

  if (typeof obj.search === 'string' && obj.search.trim()) filters.search = truncate(obj.search, 120);
  return filters;
}

function geoCentroid(geometry: unknown): { lat: number; lng: number } | null {
  if (!geometry || typeof geometry !== 'object') return null;
  const coordinates =
    (geometry as Record<string, unknown>).coordinates ?? (geometry as Record<string, unknown>).geometry;
  if (!coordinates) return null;
  const points: number[][] = [];
  const walk = (node: unknown, depth: number): void => {
    if (depth > 6 || !Array.isArray(node)) return;
    if (node.length === 2 && typeof node[0] === 'number' && typeof node[1] === 'number') {
      points.push(node as number[]);
      return;
    }
    for (const child of node) walk(child, depth + 1);
  };
  walk(coordinates, 0);
  if (points.length === 0) return null;
  const lng = points.reduce((sum, point) => sum + point[0], 0) / points.length;
  const lat = points.reduce((sum, point) => sum + point[1], 0) / points.length;
  const clampedLat = clampLat(lat);
  const clampedLng = clampLng(lng);
  if (clampedLat === undefined || clampedLng === undefined) return null;
  return { lat: clampedLat, lng: clampedLng };
}

/* ───────────────────────────── deterministic parser ───────────────────────────── */

const WORD = (pattern: string): RegExp => new RegExp(`\\b(?:${pattern})\\b`, 'i');

function describeSources(severities: string[], eventTypes: string[]): string {
  const parts: string[] = [];
  if (severities.length > 0) {
    parts.push(severities.map((severity) => severity.toLowerCase()).join(' and '));
  }
  if (eventTypes.length > 0) {
    parts.push(eventTypes.map((type) => type.toLowerCase().replace(/_/g, ' ')).join(' and '));
  }
  return parts.length > 0 ? parts.join(' ') : 'all';
}

function leadHighlights(leads: StormLeadRef[]): string[] {
  const scored = leads.filter((lead) => (typeof lead.score === 'number' && lead.score > 0) || lead.stormEventId);
  const pool = scored.length > 0 ? scored : leads;
  return pool.slice(0, MAX_ARRAY).map((lead) => lead.id);
}

/**
 * Deterministic NL → MapPlan.
 * Precedence: reset/focus/explain intent, then severity/event filters, then
 * lead/job overlays; no match yields `intent: "unknown"` with an explanation.
 */
export function parseHeuristicPlan(
  query: string,
  view?: MapView,
  stormEvents: StormEventSummary[] = [],
  leads: StormLeadRef[] = []
): MapPlan {
  const q = (query || '').toLowerCase().trim();
  const plan: MapPlan = {
    intent: 'unknown',
    explanation:
      "I couldn't map that request to a supported map action. Try \"show extreme tornado warnings\", \"show my leads\" or \"reset the map\".",
    overlays: [],
    filters: {},
    highlightLeadIds: [],
    matchedStormIds: [],
  };

  const safeCenter = view ? sanitizeCenter(view.center) : undefined;
  const safeZoom = view ? clampZoom(view.zoom) : undefined;
  if (safeCenter) plan.center = safeCenter;
  if (safeZoom !== undefined) plan.zoom = safeZoom;

  const namedStorm = stormEvents.find(
    (event) =>
      (event.name && q.includes(event.name.toLowerCase())) ||
      (event.area ? q.includes(event.area.toLowerCase()) : false)
  );
  if (namedStorm) {
    return {
      intent: 'focus_storm',
      explanation: `Focused on ${namedStorm.name}${namedStorm.area ? ` (${namedStorm.area})` : ''} — ${namedStorm.severity.toLowerCase()} ${
        namedStorm.eventType.toLowerCase().replace(/_/g, ' ')
      }.`,
      ...(geoCentroid(namedStorm.geometry) ? { center: geoCentroid(namedStorm.geometry) as { lat: number; lng: number } } : {}),
      zoom: 9,
      overlays: ['alerts', 'stormEvents'],
      filters: { severity: [namedStorm.severity], eventTypes: [namedStorm.eventType] },
      highlightLeadIds: [],
      matchedStormIds: [namedStorm.id],
    };
  }

  if (WORD('reset|clear|home|default|zoom out|show everything|show all').test(q)) {
    return {
      intent: 'reset',
      explanation: 'Resetting the map: clearing filters and showing all alerts, storm outlines, leads and jobs.',
      ...(safeCenter ? { center: safeCenter } : {}),
      zoom: 5,
      overlays: ['alerts', 'stormEvents', 'leads', 'jobs'],
      filters: {},
      highlightLeadIds: [],
      matchedStormIds: [],
    };
  }

  if (WORD("explain|summar|overview|status|what'?s happening|what is happening|going on|what does|tell me").test(q)) {
    const extreme = stormEvents.filter((event) => event.severity === 'EXTREME').length;
    return {
      intent: 'explain',
      explanation:
        stormEvents.length === 0
          ? 'There are no active storm events on the map right now; only your leads and jobs are shown.'
          : `There are ${stormEvents.length} active storm event${stormEvents.length === 1 ? '' : 's'}${
              extreme > 0 ? `, ${extreme} at EXTREME severity` : ''
            }. The strongest is ${stormEvents[0].name}.`,
      overlays: ['alerts', 'stormEvents'],
      filters: {},
      highlightLeadIds: [],
      matchedStormIds: [],
    };
  }

  const severities: string[] = [];
  if (WORD('extreme').test(q)) severities.push('EXTREME');
  if (WORD('severe').test(q)) severities.push('SEVERE');
  if (WORD('moderate').test(q)) severities.push('MODERATE');
  if (WORD('minor').test(q)) severities.push('MINOR');

  const eventTypes: string[] = [];
  if (WORD('tornado|tornadoes|twister').test(q)) eventTypes.push('TORNADO');
  if (WORD('hurricane|tropical|cyclone').test(q)) eventTypes.push('HURRICANE');
  if (WORD('thunderstorm|thunderstorms|t-?storm|wind|windy').test(q)) eventTypes.push('SEVERE_THUNDERSTORM');
  if (WORD('flood|flooding|flash flood').test(q)) eventTypes.push('FLASH_FLOOD');
  if (WORD('winter|blizzard|snow|ice|freeze').test(q)) eventTypes.push('WINTER_STORM');
  if (WORD('wildfire|fire|ember').test(q)) eventTypes.push('WILDFIRE');

  const hail = WORD('hail|hailstorm').test(q);
  const leadsRequested = WORD('lead|leads|homeowner|homeowners|prospect|prospects|customer|customers').test(q);
  const jobsRequested = WORD('job|jobs|work order|work orders|project|projects|install|installs|crew|crews').test(q);

  const hasFilter = severities.length > 0 || eventTypes.length > 0 || hail;
  let working: MapPlan | null = null;

  if (hasFilter) {
    const overlays: MapOverlay[] = ['alerts', 'stormEvents'];
    if (hail) overlays.push('hailSwath');
    const matchedStormIds = stormEvents
      .filter(
        (event) =>
          (severities.length === 0 || severities.includes(event.severity)) &&
          (eventTypes.length === 0 || eventTypes.includes(event.eventType))
      )
      .slice(0, MAX_ARRAY)
      .map((event) => event.id);
    working = {
      intent: 'filter_severity',
      explanation: `Showing ${describeSources(severities, eventTypes)} alerts${hail ? ' with the hail swath overlay' : ''} on the map.`,
      ...(safeCenter ? { center: safeCenter } : {}),
      ...(safeZoom !== undefined ? { zoom: safeZoom } : {}),
      overlays,
      filters: {
        ...(severities.length > 0 ? { severity: severities } : {}),
        ...(eventTypes.length > 0 ? { eventTypes } : {}),
      },
      highlightLeadIds: [],
      matchedStormIds,
    };
  }

  if (leadsRequested) {
    const overlays = new Set<MapOverlay>(working?.overlays ?? []);
    overlays.add('leads');
    working = {
      intent: 'show_leads',
      explanation:
        (working ? `${working.explanation} ` : '') +
        `Highlighting ${Math.min(MAX_ARRAY, Math.max(1, leadHighlights(leads).length))} leads${
          working?.filters.severity?.length ? ' in the selected storm area' : ' with active storm scores'
        }.`,
      ...((working?.center ?? safeCenter) ? { center: (working?.center ?? safeCenter) as { lat: number; lng: number } } : {}),
      ...(working?.zoom !== undefined || safeZoom !== undefined ? { zoom: working?.zoom ?? safeZoom } : {}),
      bbox: working?.bbox,
      overlays: [...overlays],
      filters: working?.filters ?? {},
      highlightLeadIds: leadHighlights(leads),
      matchedStormIds: working?.matchedStormIds ?? [],
    };
  }

  if (jobsRequested) {
    if (working) {
      const overlays = new Set<MapOverlay>(working.overlays);
      overlays.add('jobs');
      working = { ...working, overlays: [...overlays] };
    } else {
      working = {
        intent: 'show_jobs',
        explanation: 'Showing jobs on the map so you can see which active projects sit in storm areas.',
        ...(safeCenter ? { center: safeCenter } : {}),
        ...(safeZoom !== undefined ? { zoom: safeZoom } : {}),
        overlays: ['jobs', 'stormEvents'],
        filters: {},
        highlightLeadIds: [],
        matchedStormIds: [],
      };
    }
  }

  if (working) {
    return {
      ...working,
      explanation: working.explanation.trim() || 'Updated the map.',
    };
  }
  return plan;
}

/* ───────────────────────────── validation ───────────────────────────── */

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

/**
 * Whitelist + clamp a raw plan. Always returns a usable plan with a non-empty
 * explanation; unknown fields are discarded.
 */
export function validatePlan(
  raw: unknown,
  fallback: MapPlan,
  opts: { view?: MapView; stormEvents?: StormEventSummary[]; leadIds?: string[] } = {}
): MapPlan {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return fallback;
  const obj = raw as Record<string, unknown>;

  const intentRaw = typeof obj.intent === 'string' ? obj.intent : '';
  const intent = (MAP_INTENTS as string[]).includes(intentRaw) ? (intentRaw as MapIntent) : fallback.intent;

  const explanation =
    typeof obj.explanation === 'string' && obj.explanation.trim()
      ? truncate(obj.explanation, 500)
      : fallback.explanation || 'Updated the map.';

  const filters = sanitizeFilters(obj.filters);
  const overlays = sanitizeOverlays(obj.overlays);

  const center = sanitizeCenter(obj.center) ?? fallback.center ?? (opts.view ? sanitizeCenter(opts.view.center) : undefined);
  const zoom = clampZoom(obj.zoom) ?? fallback.zoom ?? (opts.view ? clampZoom(opts.view.zoom) : undefined);
  const bbox = sanitizeBBox(obj.bbox) ?? fallback.bbox;

  let highlightLeadIds = sanitizeStringArray(obj.highlightLeadIds, MAX_ARRAY, 80);
  if (opts.leadIds) {
    const allowedLeads = new Set(opts.leadIds);
    highlightLeadIds = highlightLeadIds.filter((id) => allowedLeads.has(id));
    if (fallback.highlightLeadIds.length > 0) {
      highlightLeadIds = [...new Set([...highlightLeadIds, ...fallback.highlightLeadIds])].slice(0, MAX_ARRAY);
    }
  }
  if (highlightLeadIds.length === 0) highlightLeadIds = fallback.highlightLeadIds.slice(0, MAX_ARRAY);

  let matchedStormIds = sanitizeStringArray(obj.matchedStormIds, MAX_ARRAY, 80);
  if (opts.stormEvents) {
    const allowedStorms = new Set(opts.stormEvents.map((event) => event.id));
    matchedStormIds = matchedStormIds.filter((id) => allowedStorms.has(id));
    if (fallback.matchedStormIds.length > 0) {
      matchedStormIds = [...new Set([...matchedStormIds, ...fallback.matchedStormIds])].slice(0, MAX_ARRAY);
    }
  }
  if (matchedStormIds.length === 0) matchedStormIds = fallback.matchedStormIds.slice(0, MAX_ARRAY);

  const validated: MapPlan = {
    intent,
    explanation,
    ...(center ? { center } : {}),
    ...(zoom !== undefined ? { zoom } : {}),
    ...(bbox ? { bbox } : {}),
    overlays: overlays.length > 0 ? overlays : fallback.overlays,
    filters: Object.keys(filters).length > 0 ? filters : fallback.filters,
    highlightLeadIds,
    matchedStormIds,
  };

  // A model that cannot map the request must not beat a confident heuristic.
  if (validated.intent === 'unknown' && fallback.intent !== 'unknown') return fallback;
  return validated;
}

/* ───────────────────────────── suggestions / prompt ───────────────────────────── */

export function buildSuggestions(storm: StormContext): string[] {
  const suggestions: string[] = [];
  const extreme = storm.events.find((event) => event.severity === 'EXTREME');
  if (extreme) suggestions.push(`Focus on ${extreme.name}`);
  suggestions.push('Show extreme tornado warnings');
  suggestions.push('Show leads inside the hail swath');
  suggestions.push('Which jobs are in the impact area?');
  suggestions.push('Reset the map');
  return [...new Set(suggestions)].slice(0, 4);
}

function compactStorm(event: StormEventSummary): Record<string, unknown> {
  return {
    id: event.id,
    name: truncate(event.name ?? '', 120),
    severity: event.severity,
    eventType: event.eventType,
    area: event.area ?? null,
    leadCount: event.leadCount,
    jobCount: event.jobCount,
  };
}

export function buildMapCopilotMessages(
  query: string,
  view: MapView | undefined,
  bundle: AiContextBundle
): Array<{ role: 'system' | 'user'; content: string }> {
  const system = [
    'You convert a roofing contractor\'s natural-language request into a JSON map plan for a Leaflet storm map.',
    'Return ONE JSON object only (no markdown, no prose) with exactly these fields:',
    '{"intent": "focus_storm"|"filter_severity"|"show_leads"|"show_jobs"|"reset"|"explain"|"unknown", "explanation": string, "center": {"lat": number, "lng": number}, "zoom": number (3-15), "bbox": {"north": number, "south": number, "east": number, "west": number}, "overlays": ["alerts"|"stormEvents"|"hailSwath"|"leads"|"jobs"|"radar"], "filters": {"severity": ["MINOR"|"MODERATE"|"SEVERE"|"EXTREME"], "eventTypes": ["TORNADO"|"HURRICANE"|"SEVERE_THUNDERSTORM"|"FLASH_FLOOD"|"WINTER_STORM"|"WILDFIRE"], "leadStatuses": string[], "search": string}, "highlightLeadIds": string[], "matchedStormIds": string[]}.',
    'All fields are optional except intent and explanation; explanation must be a short sentence describing what the map will show.',
    'Only use ids that appear in the provided storms/leads lists. Do not invent ids, coordinates or filters.',
    'If the request cannot be represented, return {"intent":"unknown","explanation":"<why>"}.',
  ].join(' ');

  const user = JSON.stringify({
    request: truncate(query, 500),
    currentView: view ? { center: view.center, zoom: view.zoom } : null,
    storms: bundle.storm.events.slice(0, 10).map(compactStorm),
    leads: bundle.storm.leads.slice(0, 15).map((lead) => ({
      id: lead.id,
      name: truncate(lead.name ?? '', 80),
      address: truncate(lead.address ?? '', 120),
      status: lead.status,
      score: lead.score ?? null,
    })),
  });

  return [
    { role: 'system', content: system },
    { role: 'user', content: user },
  ];
}

/** Full endpoint implementation with deterministic fallback. */
export async function generateMapPlan(input: MapCopilotInput, deps: MapCopilotDeps = {}): Promise<MapCopilotResponse> {
  const now = deps.now ?? (() => Date.now());
  const startedAt = now();
  const loadContext =
    deps.loadContext ??
    (async (companyId: string, opts?: { days?: number; limit?: number }) => {
      // Deferred import: the storm-engine/DB modules are only needed on a real request.
      const { loadAiContext } = await import('./context');
      return loadAiContext(companyId, opts);
    });
  const callRouter = deps.routeLLM ?? routeLLM;

  const query = truncate(input.query ?? '', 500);
  let bundle: AiContextBundle;
  try {
    bundle = await loadContext(input.companyId, { days: 7, limit: 60 });
  } catch (error) {
    console.warn('[ai/mapCopilot] context unavailable; using empty context:', (error as Error).message);
    bundle = emptyAiContextBundle();
  }
  const storm = bundle.storm;
  const suggestions = buildSuggestions(storm);
  const heuristic = parseHeuristicPlan(query, input.view, storm.events, storm.leads);

  try {
    const result = await callRouter(
      {
        messages: buildMapCopilotMessages(query, input.view, bundle),
        purpose: 'map_copilot',
        json: true,
        temperature: 0.1,
        maxTokens: 500,
      },
      { companyId: input.companyId, userId: input.userId }
    );

    if (result.fallback) {
      return {
        generatedAt: new Date().toISOString(),
        source: 'heuristic',
        providerId: result.providerId,
        model: result.model,
        latencyMs: result.latencyMs,
        fallback: true,
        plan: heuristic,
        suggestions,
      };
    }

    const raw = extractJsonObject(result.content);
    const plan = validatePlan(raw, heuristic, {
      view: input.view,
      stormEvents: storm.events,
      leadIds: storm.leads.map((lead) => lead.id),
    });
    return {
      generatedAt: new Date().toISOString(),
      source: raw ? 'llm' : 'heuristic',
      providerId: result.providerId,
      model: result.model,
      latencyMs: result.latencyMs,
      fallback: !raw,
      plan,
      suggestions,
    };
  } catch (error) {
    console.warn('[ai/mapCopilot] LLM unavailable; serving heuristic plan:', (error as Error).message);
    return {
      generatedAt: new Date().toISOString(),
      source: 'heuristic',
      providerId: 'local',
      model: 'deterministic-v1',
      latencyMs: Math.max(0, now() - startedAt),
      fallback: true,
      plan: heuristic,
      suggestions,
    };
  }
}
