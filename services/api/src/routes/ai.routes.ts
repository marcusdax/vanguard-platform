/**
 * AI gateway routes — contract §5.1–§5.6 of docs/AI_STORM_ARCHITECTURE.md.
 *
 * Mounted at /api/v1/ai behind the global JWT authMiddleware (see routes/index.ts).
 * Provider mutations require OWNER/MANAGER. API keys are AES-256-GCM encrypted
 * at rest and only ever returned masked (••••last4); ciphertext never leaves the
 * process. Every endpoint degrades to the deterministic local engine when no
 * provider is configured.
 */
import { Router, type NextFunction, type Response } from 'express';
import { randomUUID } from 'crypto';
import { Prisma } from '@prisma/client';
import { AuthRequest, requireRole } from '../middleware/auth';
import { prisma } from '../prisma';
import { decryptSecret, encryptSecret } from '../llm/crypto';
import {
  getProviderDefinition,
  listProviderIds,
  loadProviderState,
  persistProviderHealth,
  toProviderView,
  type ProviderHealth,
  type ProviderRuntime,
} from '../llm/registry';
import {
  buildFallbackChain,
  describeProviderRuntime,
  loadRoutingSettings,
  readRouterEnv,
  routeLLM,
} from '../llm/router';
import { markFailure, markSuccess } from '../llm/rateLimit';
import { chatCompletion, GatewayError } from '../llm/gateway';
import type { ChatMessage } from '../llm/types';
import { generateStormBrief, STORM_FOCUSES, type StormFocus } from '../services/ai/stormBrief';
import { clampLat, clampLng, clampZoom, generateMapPlan } from '../services/ai/mapCopilot';
import { generateLeadInsights } from '../services/ai/leadInsights';
import type { BBox } from '../services/storm/types';

const router = Router();

const LOCAL_ENGINE = 'deterministic-v1';
const MAX_MESSAGES = 50;
const MAX_MESSAGE_CHARS = 20_000;
const MAX_MODELS = 12;
const MAX_PROVIDER_PRIORITY = 1_000;
const PROVIDER_ADMIN_ROLES = ['OWNER', 'MANAGER'];

/* ───────────────────────────── helpers ───────────────────────────── */

type AsyncHandler = (req: AuthRequest, res: Response) => Promise<unknown> | unknown;

function handle(fn: AsyncHandler) {
  return (req: AuthRequest, res: Response, next: NextFunction): void => {
    Promise.resolve(fn(req, res)).catch(next);
  };
}

function fail(res: Response, status: number, error: string, code?: string) {
  return res.status(status).json({ error, ...(code ? { code } : {}) });
}

function asObject(body: unknown): Record<string, unknown> | null {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return null;
  return body as Record<string, unknown>;
}

function asTrimmedString(value: unknown, maxLen = 500): string | null {
  if (typeof value !== 'string') return null;
  const clean = value.trim();
  if (!clean) return null;
  return clean.length > maxLen ? clean.slice(0, maxLen) : clean;
}

function parseStringArray(value: unknown, maxItems: number, maxLen: number): string[] | null {
  if (!Array.isArray(value)) return null;
  if (value.length > maxItems) return null;
  const out: string[] = [];
  for (const item of value) {
    const clean = asTrimmedString(item, maxLen);
    if (!clean) return null;
    out.push(clean);
  }
  return out;
}

function isHttpUrl(value: string): boolean {
  return /^https?:\/\/[^\s]+$/i.test(value);
}

function readRequestBBox(value: unknown): { bbox?: BBox; error?: string } {
  if (value === undefined || value === null) return {};
  if (typeof value !== 'object' || Array.isArray(value)) return { error: 'bbox must be an object' };
  const obj = value as Record<string, unknown>;
  const north = obj.north;
  const south = obj.south;
  const east = obj.east;
  const west = obj.west;
  if (![north, south, east, west].every((n) => typeof n === 'number' && Number.isFinite(n))) {
    return { error: 'bbox requires numeric north, south, east, west' };
  }
  const n = north as number;
  const s = south as number;
  const e = east as number;
  const w = west as number;
  if (n < -90 || n > 90 || s < -90 || s > 90) return { error: 'bbox latitudes must be between -90 and 90' };
  if (e < -180 || e > 180 || w < -180 || w > 180) return { error: 'bbox longitudes must be between -180 and 180' };
  if (n <= s) return { error: 'bbox north must be greater than south' };
  if (e <= w) return { error: 'bbox east must be greater than west' };
  return { bbox: { north: n, south: s, east: e, west: w } };
}

function readView(value: unknown): { center: { lat: number; lng: number }; zoom: number } | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const obj = value as Record<string, unknown>;
  const centerRaw =
    obj.center && typeof obj.center === 'object' && !Array.isArray(obj.center)
      ? (obj.center as Record<string, unknown>)
      : obj;
  const lat = clampLat(centerRaw.lat);
  const lng = clampLng(centerRaw.lng ?? centerRaw.lon);
  if (lat === undefined || lng === undefined) return undefined;
  return { center: { lat, lng }, zoom: clampZoom(obj.zoom) ?? 6 };
}

function providerViews(providers: ProviderRuntime[]): ReturnType<typeof toProviderView>[] {
  return providers.map((provider) => {
    const { health, cooldownUntil } = describeProviderRuntime(provider);
    return toProviderView(provider, { healthOverride: health, cooldownUntil });
  });
}

function viewForProvider(providers: ProviderRuntime[], providerId: string) {
  const runtime = providers.find((provider) => provider.providerId === providerId);
  if (!runtime) return null;
  const { health, cooldownUntil } = describeProviderRuntime(runtime);
  return toProviderView(runtime, { healthOverride: health, cooldownUntil });
}

function providerHealthJson(health: ProviderHealth): Prisma.InputJsonValue {
  return health as unknown as Prisma.InputJsonValue;
}

/* ───────────────────────────── body guard ───────────────────────────── */

// Reject non-object JSON bodies early. (Malformed JSON is rejected by the global
// express.json error path; this guard catches arrays/primitives reaching us.)
router.use((req, res, next) => {
  if (['POST', 'PATCH', 'PUT'].includes(req.method) && req.body !== undefined && req.body !== null && asObject(req.body) === null) {
    return fail(res, 400, 'Request body must be a JSON object', 'AI_INVALID_BODY');
  }
  next();
});

/* ───────────────────────────── 5.1 status ───────────────────────────── */

router.get(
  '/status',
  handle(async (req, res) => {
    if (!req.user) return fail(res, 401, 'Not authenticated', 'AUTH_NOT_AUTHENTICATED');
    const env = readRouterEnv();
    const companyId = req.user.companyId;
    const [settings, state, usage] = await Promise.all([
      loadRoutingSettings(companyId),
      loadProviderState(),
      loadUsageSummary(companyId),
    ]);
    const fallbackChain = buildFallbackChain(state.providers, settings, { includeCoolingDown: true });

    res.json({
      enabled: env.enabled,
      routing: {
        strategy: settings.strategy,
        stickyMinutes: settings.stickyMinutes,
        fallbackChain,
      },
      providers: providerViews(state.providers),
      localFallback: {
        available: env.allowLocalFallback && settings.allowLocalFallback,
        engine: LOCAL_ENGINE,
      },
      usage,
    });
  })
);

async function loadUsageSummary(companyId: string) {
  const zeros = {
    today: { requests: 0, tokens: 0, fallbackRate: 0 },
    last7d: { requests: 0, tokens: 0 },
  };
  try {
    const now = new Date();
    const todayStart = new Date(now);
    todayStart.setHours(0, 0, 0, 0);
    const weekStart = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

    const [todayRows, weekAgg] = await Promise.all([
      prisma.aIUsage.findMany({
        where: { companyId, createdAt: { gte: todayStart } },
        select: { totalTokens: true, fallback: true },
      }),
      prisma.aIUsage.aggregate({
        where: { companyId, createdAt: { gte: weekStart } },
        _count: { _all: true },
        _sum: { totalTokens: true },
      }),
    ]);

    const requests = todayRows.length;
    const tokens = todayRows.reduce((sum, row) => sum + (row.totalTokens ?? 0), 0);
    const fallbacks = todayRows.filter((row) => row.fallback).length;
    return {
      today: {
        requests,
        tokens,
        fallbackRate: requests > 0 ? Number((fallbacks / requests).toFixed(3)) : 0,
      },
      last7d: {
        requests: weekAgg._count?._all ?? 0,
        tokens: weekAgg._sum?.totalTokens ?? 0,
      },
    };
  } catch (error) {
    console.warn('[ai/routes] usage summary unavailable:', (error as Error).message);
    return zeros;
  }
}

/* ───────────────────────────── 5.2 chat ───────────────────────────── */

router.post(
  '/chat',
  handle(async (req, res) => {
    if (!req.user) return fail(res, 401, 'Not authenticated', 'AUTH_NOT_AUTHENTICATED');
    const body = asObject(req.body) ?? {};
    const rawMessages = body.messages;
    if (!Array.isArray(rawMessages) || rawMessages.length === 0 || rawMessages.length > MAX_MESSAGES) {
      return fail(res, 400, `messages must be a non-empty array (max ${MAX_MESSAGES})`, 'AI_INVALID_MESSAGES');
    }

    const messages: ChatMessage[] = [];
    for (const item of rawMessages) {
      const message = asObject(item);
      if (!message) return fail(res, 400, 'Each message must be an object', 'AI_INVALID_MESSAGES');
      const role = message.role;
      if (role !== 'system' && role !== 'user' && role !== 'assistant') {
        return fail(res, 400, 'Message role must be system, user or assistant', 'AI_INVALID_MESSAGE_ROLE');
      }
      if (typeof message.content !== 'string' || !message.content.trim()) {
        return fail(res, 400, 'Message content must be a non-empty string', 'AI_INVALID_MESSAGE_CONTENT');
      }
      messages.push({
        role,
        content: message.content.length > MAX_MESSAGE_CHARS ? message.content.slice(0, MAX_MESSAGE_CHARS) : message.content,
      });
    }

    const conversationId = asTrimmedString(body.conversationId, 200) ?? undefined;
    const json = body.json === true;
    const temperature =
      typeof body.temperature === 'number' && Number.isFinite(body.temperature)
        ? Math.min(2, Math.max(0, body.temperature))
        : undefined;

    const result = await routeLLM(
      {
        messages,
        purpose: 'chat',
        ...(json ? { json: true } : {}),
        ...(temperature !== undefined ? { temperature } : {}),
        ...(conversationId ? { conversationId } : {}),
      },
      { companyId: req.user.companyId, userId: req.user.id }
    );

    res.json({
      id: randomUUID(),
      content: result.content,
      providerId: result.providerId,
      model: result.model,
      usage: result.usage,
      latencyMs: result.latencyMs,
      fallback: result.fallback,
      createdAt: new Date().toISOString(),
    });
  })
);

/* ───────────────────────────── 5.3 storm brief ───────────────────────────── */

router.post(
  '/storm-brief',
  handle(async (req, res) => {
    if (!req.user) return fail(res, 401, 'Not authenticated', 'AUTH_NOT_AUTHENTICATED');
    const body = asObject(req.body) ?? {};

    let stormIds: string[] | undefined;
    if (body.stormIds !== undefined) {
      const parsed = parseStringArray(body.stormIds, 20, 100);
      if (!parsed) return fail(res, 400, 'stormIds must be an array of up to 20 ids', 'AI_INVALID_STORM_IDS');
      stormIds = parsed;
    }

    const { bbox, error: bboxError } = readRequestBBox(body.bbox);
    if (bboxError) return fail(res, 400, bboxError, 'AI_INVALID_BBOX');

    let focus: StormFocus | undefined;
    if (body.focus !== undefined) {
      if (typeof body.focus !== 'string' || !(STORM_FOCUSES as string[]).includes(body.focus)) {
        return fail(res, 400, `focus must be one of ${STORM_FOCUSES.join(', ')}`, 'AI_INVALID_FOCUS');
      }
      focus = body.focus as StormFocus;
    }

    let days: number | undefined;
    if (body.days !== undefined) {
      if (typeof body.days !== 'number' || !Number.isFinite(body.days) || body.days < 1 || body.days > 30) {
        return fail(res, 400, 'days must be a number between 1 and 30', 'AI_INVALID_DAYS');
      }
      days = Math.round(body.days);
    }

    const response = await generateStormBrief(
      {
        companyId: req.user.companyId,
        userId: req.user.id,
        ...(stormIds ? { stormIds } : {}),
        ...(bbox ? { bbox } : {}),
        ...(focus ? { focus } : {}),
        ...(days ? { days } : {}),
      },
      {}
    );
    res.json(response);
  })
);

/* ───────────────────────────── 5.4 map copilot ───────────────────────────── */

router.post(
  '/map-copilot',
  handle(async (req, res) => {
    if (!req.user) return fail(res, 401, 'Not authenticated', 'AUTH_NOT_AUTHENTICATED');
    const body = asObject(req.body) ?? {};
    const query = asTrimmedString(body.query, 500);
    if (!query || query.length < 2) {
      return fail(res, 400, 'query must be a string between 2 and 500 characters', 'AI_INVALID_QUERY');
    }
    const view = readView(body.view);

    const response = await generateMapPlan({
      companyId: req.user.companyId,
      userId: req.user.id,
      query,
      ...(view ? { view } : {}),
    });
    res.json(response);
  })
);

/* ───────────────────────────── 5.5 lead insights ───────────────────────────── */

router.post(
  '/lead-insights',
  handle(async (req, res) => {
    if (!req.user) return fail(res, 401, 'Not authenticated', 'AUTH_NOT_AUTHENTICATED');
    const body = asObject(req.body) ?? {};
    const leadId = asTrimmedString(body.leadId, 100);
    if (!leadId) return fail(res, 400, 'leadId is required', 'AI_INVALID_LEAD_ID');

    const response = await generateLeadInsights({
      companyId: req.user.companyId,
      userId: req.user.id,
      leadId,
    });
    if (!response) return fail(res, 404, 'Lead not found', 'AI_LEAD_NOT_FOUND');
    res.json(response);
  })
);

/* ───────────────────────────── 5.6 models / usage ───────────────────────────── */

router.get(
  '/models',
  handle(async (_req, res) => {
    const state = await loadProviderState();
    const data: Array<{ id: string; providerId: string; model: string; configured: boolean }> = [];
    const seen = new Set<string>();
    for (const provider of state.providers) {
      for (const model of provider.models) {
        const id = `${provider.providerId}:${model}`;
        if (seen.has(id)) continue;
        seen.add(id);
        data.push({ id, providerId: provider.providerId, model, configured: provider.configured });
      }
    }
    res.json({ data });
  })
);

router.get(
  '/usage',
  handle(async (req, res) => {
    if (!req.user) return fail(res, 401, 'Not authenticated', 'AUTH_NOT_AUTHENTICATED');
    const companyId = req.user.companyId;
    const daysRaw = Number.parseInt(String(req.query.days ?? '7'), 10);
    const days = Number.isFinite(daysRaw) ? Math.min(90, Math.max(1, daysRaw)) : 7;
    const since = new Date(Date.now() - days * 24 * 60 * 60 * 1000);

    type UsageRow = {
      providerId: string | null;
      purpose: string;
      promptTokens: number;
      completionTokens: number;
      totalTokens: number;
      latencyMs: number;
      fallback: boolean;
      createdAt: Date;
    };
    let rows: UsageRow[] = [];
    try {
      rows = await prisma.aIUsage.findMany({
        where: { companyId, createdAt: { gte: since } },
        orderBy: { createdAt: 'asc' },
        take: 5_000,
        select: {
          providerId: true,
          purpose: true,
          promptTokens: true,
          completionTokens: true,
          totalTokens: true,
          latencyMs: true,
          fallback: true,
          createdAt: true,
        },
      });
    } catch (error) {
      console.warn('[ai/routes] usage query failed:', (error as Error).message);
    }

    const totals = {
      requests: rows.length,
      tokens: 0,
      promptTokens: 0,
      completionTokens: 0,
      fallbackRequests: 0,
      fallbackRate: 0,
      avgLatencyMs: 0,
    };
    const byDay = new Map<string, { date: string; requests: number; tokens: number; fallback: number }>();
    const byProvider = new Map<string, { providerId: string; requests: number; tokens: number }>();
    const byPurpose = new Map<string, { purpose: string; requests: number; tokens: number }>();
    let latencySum = 0;

    for (const row of rows) {
      totals.tokens += row.totalTokens;
      totals.promptTokens += row.promptTokens;
      totals.completionTokens += row.completionTokens;
      latencySum += row.latencyMs;
      if (row.fallback) totals.fallbackRequests += 1;

      const date = row.createdAt.toISOString().slice(0, 10);
      const day = byDay.get(date) ?? { date, requests: 0, tokens: 0, fallback: 0 };
      day.requests += 1;
      day.tokens += row.totalTokens;
      if (row.fallback) day.fallback += 1;
      byDay.set(date, day);

      const providerId = row.providerId ?? 'local';
      const provider = byProvider.get(providerId) ?? { providerId, requests: 0, tokens: 0 };
      provider.requests += 1;
      provider.tokens += row.totalTokens;
      byProvider.set(providerId, provider);

      const purpose = byPurpose.get(row.purpose) ?? { purpose: row.purpose, requests: 0, tokens: 0 };
      purpose.requests += 1;
      purpose.tokens += row.totalTokens;
      byPurpose.set(row.purpose, purpose);
    }

    totals.fallbackRate = totals.requests > 0 ? Number((totals.fallbackRequests / totals.requests).toFixed(3)) : 0;
    totals.avgLatencyMs = totals.requests > 0 ? Math.round(latencySum / totals.requests) : 0;

    res.json({
      days,
      since: since.toISOString(),
      totals,
      byDay: [...byDay.values()].sort((a, b) => a.date.localeCompare(b.date)),
      byProvider: [...byProvider.values()].sort((a, b) => b.requests - a.requests || a.providerId.localeCompare(b.providerId)),
      byPurpose: [...byPurpose.values()].sort((a, b) => b.requests - a.requests || a.purpose.localeCompare(b.purpose)),
    });
  })
);

/* ───────────────────────────── provider admin ───────────────────────────── */

router.get(
  '/providers',
  handle(async (req, res) => {
    if (!req.user) return fail(res, 401, 'Not authenticated', 'AUTH_NOT_AUTHENTICATED');
    const state = await loadProviderState();
    const settings = await loadRoutingSettings(req.user.companyId);
    const chain = buildFallbackChain(state.providers, settings, { includeCoolingDown: true }).map(
      (entry) => entry.providerId
    );
    res.json({ providers: providerViews(state.providers), chain });
  })
);

router.post(
  '/providers',
  requireRole(...PROVIDER_ADMIN_ROLES),
  handle(async (req, res) => {
    const body = asObject(req.body) ?? {};
    const providerId = asTrimmedString(body.providerId, 60);
    if (!providerId) return fail(res, 400, 'providerId is required', 'AI_INVALID_PROVIDER_ID');

    const definition = getProviderDefinition(providerId);
    if (!definition) {
      return fail(res, 400, `Unknown providerId. Supported: ${listProviderIds().join(', ')}`, 'AI_UNKNOWN_PROVIDER');
    }

    const existing = await prisma.aIProvider.findUnique({ where: { providerId } });
    const apiKey = asTrimmedString(body.apiKey, 400) ?? undefined;
    if (definition.requiresApiKey && !apiKey && !existing?.apiKeyCipher) {
      return fail(res, 400, `${definition.name} requires an API key`, 'AI_PROVIDER_KEY_REQUIRED');
    }

    let baseUrl = asTrimmedString(body.baseUrl, 500) ?? undefined;
    if (body.baseUrl !== undefined && body.baseUrl !== null && !baseUrl) {
      return fail(res, 400, 'baseUrl must be a non-empty string', 'AI_INVALID_BASE_URL');
    }
    if (baseUrl && !isHttpUrl(baseUrl)) {
      return fail(res, 400, 'baseUrl must be an http(s) URL', 'AI_INVALID_BASE_URL');
    }
    const effectiveBaseUrl = baseUrl ?? definition.baseUrl ?? undefined;
    if (definition.requiresBaseUrl && !effectiveBaseUrl) {
      return fail(
        res,
        400,
        `${definition.name} requires a baseUrl${providerId === 'freellmapi' ? ' (or set AI_FREELMAPI_URL)' : ''}`,
        'AI_PROVIDER_BASEURL_REQUIRED'
      );
    }

    let models: string[] | undefined;
    if (body.models !== undefined) {
      const parsed = parseStringArray(body.models, MAX_MODELS, 120);
      if (!parsed) return fail(res, 400, `models must be an array of up to ${MAX_MODELS} strings`, 'AI_INVALID_MODELS');
      models = parsed;
    }
    const effectiveModels = models ?? definition.models;

    let priority: number | undefined;
    if (body.priority !== undefined) {
      if (
        typeof body.priority !== 'number' ||
        !Number.isFinite(body.priority) ||
        body.priority < 0 ||
        body.priority > MAX_PROVIDER_PRIORITY
      ) {
        return fail(res, 400, `priority must be a number between 0 and ${MAX_PROVIDER_PRIORITY}`, 'AI_INVALID_PRIORITY');
      }
      priority = Math.round(body.priority);
    }
    const effectivePriority = priority ?? definition.priority;

    let enabled = true;
    if (body.enabled !== undefined) {
      if (typeof body.enabled !== 'boolean') return fail(res, 400, 'enabled must be a boolean', 'AI_INVALID_ENABLED');
      enabled = body.enabled;
    }

    const shared = {
      name: definition.name,
      kind: definition.kind,
      baseUrl: effectiveBaseUrl ?? null,
      enabled,
      priority: effectivePriority,
      models: effectiveModels,
      limits: definition.limits as unknown as Prisma.InputJsonValue,
      health: providerHealthJson({ status: 'unknown' }),
      ...(apiKey ? { apiKeyCipher: encryptSecret(apiKey) } : {}),
    };

    const row = existing
      ? await prisma.aIProvider.update({ where: { providerId }, data: shared })
      : await prisma.aIProvider.create({ data: { providerId, ...shared } });

    const state = await loadProviderState();
    const view = viewForProvider(state.providers, row.providerId);
    if (!view) return fail(res, 500, 'Provider saved but could not be reloaded', 'AI_PROVIDER_RELOAD_FAILED');
    return res.status(existing ? 200 : 201).json({ provider: view });
  })
);

router.patch(
  '/providers/:id',
  requireRole(...PROVIDER_ADMIN_ROLES),
  handle(async (req, res) => {
    const id = asTrimmedString(req.params.id, 100);
    if (!id) return fail(res, 400, 'Provider id is required', 'AI_INVALID_PROVIDER_ID');

    const row = await prisma.aIProvider.findFirst({ where: { OR: [{ id }, { providerId: id }] } });
    if (!row) return fail(res, 404, 'Provider not found', 'AI_PROVIDER_NOT_FOUND');

    const body = asObject(req.body) ?? {};
    const data: Prisma.AIProviderUpdateInput = {};

    if (body.apiKey !== undefined) {
      const apiKey = asTrimmedString(body.apiKey, 400);
      if (!apiKey) return fail(res, 400, 'apiKey must be a non-empty string', 'AI_INVALID_API_KEY');
      data.apiKeyCipher = encryptSecret(apiKey);
    }
    if (body.baseUrl !== undefined) {
      if (body.baseUrl === null) {
        data.baseUrl = null;
      } else {
        const baseUrl = asTrimmedString(body.baseUrl, 500);
        if (!baseUrl || !isHttpUrl(baseUrl)) return fail(res, 400, 'baseUrl must be an http(s) URL', 'AI_INVALID_BASE_URL');
        data.baseUrl = baseUrl;
      }
    }
    if (body.models !== undefined) {
      const models = parseStringArray(body.models, MAX_MODELS, 120);
      if (!models) return fail(res, 400, `models must be an array of up to ${MAX_MODELS} strings`, 'AI_INVALID_MODELS');
      data.models = models;
    }
    if (body.priority !== undefined) {
      if (
        typeof body.priority !== 'number' ||
        !Number.isFinite(body.priority) ||
        body.priority < 0 ||
        body.priority > MAX_PROVIDER_PRIORITY
      ) {
        return fail(res, 400, `priority must be a number between 0 and ${MAX_PROVIDER_PRIORITY}`, 'AI_INVALID_PRIORITY');
      }
      data.priority = Math.round(body.priority);
    }
    if (body.enabled !== undefined) {
      if (typeof body.enabled !== 'boolean') return fail(res, 400, 'enabled must be a boolean', 'AI_INVALID_ENABLED');
      data.enabled = body.enabled;
    }

    if (Object.keys(data).length === 0) {
      return fail(res, 400, 'No updatable fields provided', 'AI_NO_UPDATE_FIELDS');
    }

    const updated = await prisma.aIProvider.update({ where: { id: row.id }, data });
    const state = await loadProviderState();
    const view = viewForProvider(state.providers, updated.providerId);
    if (!view) return fail(res, 500, 'Provider saved but could not be reloaded', 'AI_PROVIDER_RELOAD_FAILED');
    res.json({ provider: view });
  })
);

router.delete(
  '/providers/:id',
  requireRole(...PROVIDER_ADMIN_ROLES),
  handle(async (req, res) => {
    const id = asTrimmedString(req.params.id, 100);
    if (!id) return fail(res, 400, 'Provider id is required', 'AI_INVALID_PROVIDER_ID');

    const row = await prisma.aIProvider.findFirst({ where: { OR: [{ id }, { providerId: id }] } });
    if (!row) return fail(res, 404, 'Provider not found', 'AI_PROVIDER_NOT_FOUND');

    // Clear the key and disable: never leave usable key material behind.
    await prisma.aIProvider.update({
      where: { id: row.id },
      data: { apiKeyCipher: null, enabled: false },
    });
    res.json({ ok: true });
  })
);

router.post(
  '/providers/:id/test',
  requireRole(...PROVIDER_ADMIN_ROLES),
  handle(async (req, res) => {
    const id = asTrimmedString(req.params.id, 100);
    if (!id) return fail(res, 400, 'Provider id is required', 'AI_INVALID_PROVIDER_ID');

    const row = await prisma.aIProvider.findFirst({ where: { OR: [{ id }, { providerId: id }] } });
    if (!row) return fail(res, 404, 'Provider not found', 'AI_PROVIDER_NOT_FOUND');

    const definition = getProviderDefinition(row.providerId);
    const baseUrl = row.baseUrl || definition?.baseUrl || null;
    if (!baseUrl) return fail(res, 400, 'Provider has no base URL configured', 'AI_PROVIDER_NO_BASE_URL');

    let apiKey: string | undefined;
    if (row.apiKeyCipher) {
      try {
        apiKey = decryptSecret(row.apiKeyCipher);
      } catch {
        return fail(res, 400, 'Stored API key could not be decrypted', 'AI_PROVIDER_KEY_UNREADABLE');
      }
    }
    if (!apiKey && (definition?.requiresApiKey ?? true)) {
      return fail(res, 400, 'Provider has no API key configured', 'AI_PROVIDER_NO_KEY');
    }

    const model = row.models?.[0] || definition?.models[0];
    if (!model) return fail(res, 400, 'Provider has no model configured', 'AI_PROVIDER_NO_MODEL');

    if (apiKey && (apiKey.startsWith('sandbox_') || apiKey.startsWith('sk-sandbox-'))) {
      await persistProviderHealth(row.id, {
        status: 'ok',
        lastCheckAt: new Date().toISOString(),
        latencyMs: 14,
        model: `${model} (sandbox)`,
      });
      return res.json({
        ok: true,
        latencyMs: 14,
        model: `${model} (sandbox)`,
        sandbox: true,
      });
    }

    const startedAt = Date.now();
    try {
      const result = await chatCompletion({
        providerId: row.providerId,
        baseUrl,
        apiKey,
        model,
        messages: [
          { role: 'system', content: 'You are a connectivity check. Reply with exactly: OK' },
          { role: 'user', content: 'ping' },
        ],
        maxTokens: 16,
        temperature: 0,
        timeoutMs: readRouterEnv().timeoutMs,
      });
      markSuccess(row.providerId, result.latencyMs, result.model);
      await persistProviderHealth(row.id, {
        status: 'ok',
        lastCheckAt: new Date().toISOString(),
        latencyMs: result.latencyMs,
        model: result.model,
      });
      res.json({ ok: true, latencyMs: result.latencyMs, model: result.model });
    } catch (error) {
      const gatewayError =
        error instanceof GatewayError
          ? error
          : new GatewayError((error as Error)?.message?.slice(0, 200) || 'Provider test failed', {
              code: 'UNKNOWN',
              retryable: false,
              providerId: row.providerId,
              model,
            });
      markFailure(row.providerId, { error: gatewayError.message, model, retryAfterMs: gatewayError.retryAfterMs });
      await persistProviderHealth(row.id, {
        status: 'error',
        lastCheckAt: new Date().toISOString(),
        error: gatewayError.message.slice(0, 300),
        model,
      });
      res.json({ ok: false, latencyMs: Date.now() - startedAt, model, error: gatewayError.message });
    }
  })
);

/* ───────────────────────────── error handler ───────────────────────────── */

router.use((err: Error & { code?: string; type?: string; status?: number }, _req: AuthRequest, res: Response, _next: NextFunction) => {
  if (err?.type === 'entity.parse.failed' || (err instanceof SyntaxError && /JSON/i.test(err.message))) {
    return fail(res, 400, 'Invalid JSON body', 'AI_BAD_JSON');
  }
  const code = err?.code || 'AI_INTERNAL_ERROR';
  const message = err?.message || 'AI request failed';
  console.error(`[ai/routes] ${code}: ${message.slice(0, 300)}`);

  if (code === 'AI_PROVIDER_UNAVAILABLE') {
    return fail(res, 503, message, code);
  }
  return fail(res, 500, 'AI request failed', code);
});

export default router;
