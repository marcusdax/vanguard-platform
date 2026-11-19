/**
 * Free-tier provider catalog + DB overrides.
 *
 * The catalog describes every OpenAI-compatible provider RoofPro can talk to
 * without a paid plan. `AIProvider` rows override catalog defaults (base URL,
 * models, priority, enabled) and hold the encrypted key. `AI_BOOTSTRAP_KEYS`
 * (JSON: {"groq":"gsk_…"}) seeds rows idempotently on first use; a DB row always
 * wins over a bootstrap key so deleting a key in the admin UI is durable.
 */
import type { AIProvider, Prisma } from '@prisma/client';
import { decryptSecret, encryptSecret, maskSecret } from './crypto';
import type { RateLimits } from './rateLimit';

export type ProviderKind = 'remote' | 'gateway' | 'local';

export interface ProviderDefinition {
  providerId: string;
  name: string;
  kind: ProviderKind;
  /** Fixed base URL; null means an admin must supply one. */
  baseUrl: string | null;
  models: string[];
  priority: number;
  limits: RateLimits;
  requiresApiKey: boolean;
  requiresBaseUrl: boolean;
  headers?: Record<string, string>;
  description?: string;
}

export type ProviderHealthStatus = 'ok' | 'error' | 'unknown' | 'unconfigured' | 'cooldown' | 'disabled';

export interface ProviderHealth {
  status: ProviderHealthStatus;
  lastCheckAt?: string;
  latencyMs?: number;
  error?: string;
  model?: string;
}

export interface ProviderRuntime {
  /** API-facing id: DB row id when present, otherwise the providerId. */
  id: string;
  dbId?: string;
  providerId: string;
  name: string;
  kind: ProviderKind;
  baseUrl: string | null;
  apiKey?: string;
  apiKeyMasked: string | null;
  enabled: boolean;
  priority: number;
  models: string[];
  limits: RateLimits;
  requiresApiKey: boolean;
  requiresBaseUrl: boolean;
  headers?: Record<string, string>;
  configured: boolean;
  keySource: 'db' | 'bootstrap' | 'none';
  keyError?: string;
  health: ProviderHealth;
  description?: string;
}

/** Contract §5.6 ProviderView — never contains ciphertext. */
export interface ProviderView {
  id: string;
  providerId: string;
  name: string;
  kind: ProviderKind;
  baseUrl?: string;
  enabled: boolean;
  priority: number;
  models: string[];
  configured: boolean;
  apiKeyMasked?: string | null;
  cooldownUntil?: string | null;
  health: ProviderHealth;
  keySource: 'db' | 'bootstrap' | 'none';
  description?: string;
}

export interface ProviderState {
  providers: ProviderRuntime[];
  catalog: ProviderDefinition[];
  bootstrapKeys: Record<string, string>;
  dbAvailable: boolean;
}

function readEnv(name: string): string {
  return (process.env[name] || '').trim();
}

/** External FreeLLMAPI gateway URL, e.g. http://localhost:3001/v1 (optional). */
export function getFreellmapiUrl(): string | null {
  const url = readEnv('AI_FREELMAPI_URL');
  return url ? url : null;
}

/**
 * The free-tier catalog. Built lazily on every call so env changes (mostly
 * AI_FREELMAPI_URL) are reflected without process restarts.
 */
export function buildCatalog(): ProviderDefinition[] {
  const freellmapiUrl = getFreellmapiUrl();
  return [
    {
      providerId: 'freellmapi',
      name: 'FreeLLMAPI Gateway',
      kind: 'gateway',
      baseUrl: freellmapiUrl,
      models: ['auto'],
      priority: 0,
      limits: {},
      requiresApiKey: false,
      requiresBaseUrl: true,
      description:
        'Optional external FreeLLMAPI instance that aggregates many free providers behind one /v1 endpoint. Tried first when enabled.',
    },
    {
      providerId: 'groq',
      name: 'Groq',
      kind: 'remote',
      baseUrl: 'https://api.groq.com/openai/v1',
      models: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant'],
      priority: 10,
      limits: { rpm: 30, rpd: 14_400, tpm: 6_000, tpd: 500_000 },
      requiresApiKey: true,
      requiresBaseUrl: false,
      description: 'Very fast Llama 3.3 70B / 8B inference on a generous free tier.',
    },
    {
      providerId: 'google',
      name: 'Google AI Studio (Gemini)',
      kind: 'remote',
      baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
      models: ['gemini-2.0-flash', 'gemini-2.0-flash-lite', 'gemini-1.5-flash'],
      priority: 20,
      limits: { rpm: 15, rpd: 1_500, tpm: 1_000_000 },
      requiresApiKey: true,
      requiresBaseUrl: false,
      description: 'Gemini free tier via the OpenAI-compatible endpoint.',
    },
    {
      providerId: 'cerebras',
      name: 'Cerebras',
      kind: 'remote',
      baseUrl: 'https://api.cerebras.ai/v1',
      models: ['llama-3.3-70b', 'llama3.1-8b'],
      priority: 30,
      limits: { rpm: 30, rpd: 14_400 },
      requiresApiKey: true,
      requiresBaseUrl: false,
      description: 'Wafer-scale inference, fast Llama models on a free tier.',
    },
    {
      providerId: 'mistral',
      name: 'Mistral AI',
      kind: 'remote',
      baseUrl: 'https://api.mistral.ai/v1',
      models: ['mistral-small-latest', 'open-mistral-nemo'],
      priority: 40,
      limits: { rpm: 60, rpd: 1_000 },
      requiresApiKey: true,
      requiresBaseUrl: false,
      description: 'Mistral small / Nemo models on the free experiment tier.',
    },
    {
      providerId: 'openrouter',
      name: 'OpenRouter',
      kind: 'remote',
      baseUrl: 'https://openrouter.ai/api/v1',
      models: ['meta-llama/llama-3.3-70b-instruct:free', 'google/gemini-2.0-flash-exp:free'],
      priority: 50,
      limits: { rpm: 20, rpd: 50 },
      requiresApiKey: true,
      requiresBaseUrl: false,
      headers: { 'HTTP-Referer': 'https://roofprocrm.com', 'X-Title': 'RoofPro CRM' },
      description: 'Router for many providers; free models only, tight daily limit.',
    },
    {
      providerId: 'nvidia',
      name: 'NVIDIA NIM',
      kind: 'remote',
      baseUrl: 'https://integrate.api.nvidia.com/v1',
      models: ['meta/llama-3.3-70b-instruct', 'nvidia/llama-3.1-nemotron-70b-instruct'],
      priority: 60,
      limits: { rpm: 40, rpd: 1_000 },
      requiresApiKey: true,
      requiresBaseUrl: false,
      description: 'NVIDIA hosted inference microservices (free developer tier).',
    },
    {
      providerId: 'custom',
      name: 'Custom OpenAI-compatible',
      kind: 'remote',
      baseUrl: null,
      models: [],
      priority: 90,
      limits: {},
      requiresApiKey: true,
      requiresBaseUrl: true,
      description: 'Any OpenAI-compatible /chat/completions endpoint you host or control.',
    },
  ];
}

export function getProviderDefinition(providerId: string): ProviderDefinition | undefined {
  return buildCatalog().find((def) => def.providerId === providerId);
}

export function listProviderIds(): string[] {
  return buildCatalog().map((def) => def.providerId);
}

/** Parse `AI_BOOTSTRAP_KEYS` JSON defensively; malformed JSON simply seeds nothing. */
export function parseBootstrapKeys(): Record<string, string> {
  const raw = readEnv('AI_BOOTSTRAP_KEYS');
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    const entries: Record<string, string> = {};
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      if (typeof value === 'string' && value.trim()) entries[key.trim()] = value.trim();
    }
    return entries;
  } catch {
    // Never log the raw value — it may contain secrets.
    console.warn('[ai/registry] AI_BOOTSTRAP_KEYS is not valid JSON; ignoring bootstrap keys');
    return {};
  }
}

function mergeLimits(base: RateLimits, override: unknown): RateLimits {
  const merged: RateLimits = { ...base };
  if (override && typeof override === 'object' && !Array.isArray(override)) {
    const obj = override as Record<string, unknown>;
    for (const key of ['rpm', 'rpd', 'tpm', 'tpd'] as const) {
      const value = obj[key];
      if (typeof value === 'number' && Number.isFinite(value) && value > 0) merged[key] = Math.floor(value);
    }
  }
  return merged;
}

function parseJsonObject(value: unknown): Record<string, unknown> {
  if (value && typeof value === 'object' && !Array.isArray(value)) return value as Record<string, unknown>;
  return {};
}

function parseHealth(value: unknown): ProviderHealth | null {
  const obj = parseJsonObject(value);
  if (Object.keys(obj).length === 0) return null;
  const status = obj.status;
  const valid: ProviderHealthStatus[] = ['ok', 'error', 'unknown', 'unconfigured', 'cooldown', 'disabled'];
  return {
    status: typeof status === 'string' && (valid as string[]).includes(status) ? (status as ProviderHealthStatus) : 'unknown',
    ...(typeof obj.lastCheckAt === 'string' ? { lastCheckAt: obj.lastCheckAt } : {}),
    ...(typeof obj.latencyMs === 'number' ? { latencyMs: obj.latencyMs } : {}),
    ...(typeof obj.error === 'string' ? { error: obj.error.slice(0, 300) } : {}),
    ...(typeof obj.model === 'string' ? { model: obj.model } : {}),
  };
}

function toRuntime(
  providerId: string,
  definition: ProviderDefinition | undefined,
  row: AIProvider | undefined,
  bootstrapKeys: Record<string, string>
): ProviderRuntime {
  const kind: ProviderKind = ((row?.kind as ProviderKind) || definition?.kind || 'remote') as ProviderKind;
  const baseUrl = (row?.baseUrl || definition?.baseUrl || null) as string | null;
  const models =
    row && Array.isArray(row.models) && row.models.length > 0
      ? [...row.models]
      : definition?.models ?? [];
  const priority = typeof row?.priority === 'number' ? row.priority : definition?.priority ?? 100;
  const enabled = row ? row.enabled : true;
  const limits = mergeLimits(definition?.limits ?? {}, row?.limits);
  const requiresApiKey = definition?.requiresApiKey ?? kind !== 'gateway';
  const requiresBaseUrl = definition?.requiresBaseUrl ?? true;

  let apiKey: string | undefined;
  let keySource: ProviderRuntime['keySource'] = 'none';
  let keyError: string | undefined;

  if (row?.apiKeyCipher) {
    try {
      apiKey = decryptSecret(row.apiKeyCipher);
      keySource = 'db';
    } catch (error) {
      keyError = (error as Error).message;
    }
  } else if (!row && bootstrapKeys[providerId]) {
    // Only used when seeding could not create the row (e.g. transient DB issue).
    apiKey = bootstrapKeys[providerId];
    keySource = 'bootstrap';
  }

  const configured =
    Boolean(baseUrl) && (!requiresApiKey || Boolean(apiKey)) && (!requiresBaseUrl || Boolean(baseUrl));

  const persistedHealth = parseHealth(row?.health);
  const health: ProviderHealth = persistedHealth
    ? { ...persistedHealth }
    : { status: configured ? 'unknown' : 'unconfigured' };
  if (!enabled) health.status = 'disabled';

  return {
    id: row?.id ?? providerId,
    dbId: row?.id,
    providerId,
    name: row?.name || definition?.name || providerId,
    kind,
    baseUrl,
    ...(apiKey ? { apiKey } : {}),
    apiKeyMasked: maskSecret(apiKey),
    enabled,
    priority,
    models,
    limits,
    requiresApiKey,
    requiresBaseUrl,
    ...(definition?.headers ? { headers: definition.headers } : {}),
    configured,
    keySource,
    ...(keyError ? { keyError } : {}),
    health,
    ...(definition?.description ? { description: definition.description } : {}),
  };
}

/**
 * Seed provider rows from AI_BOOTSTRAP_KEYS exactly once per process.
 * Existing DB rows are never modified, so admin edits (and deletions) win.
 */
let bootstrapPromise: Promise<void> | null = null;

async function seedBootstrapProviders(): Promise<void> {
  const bootstrapKeys = parseBootstrapKeys();
  if (Object.keys(bootstrapKeys).length === 0) return;
  const { prisma } = await import('../prisma');
  for (const [providerId, apiKey] of Object.entries(bootstrapKeys)) {
    const definition = getProviderDefinition(providerId);
    if (!definition || !definition.requiresApiKey) {
      console.warn(`[ai/registry] AI_BOOTSTRAP_KEYS contains unknown/non-key provider "${providerId}"; ignoring`);
      continue;
    }
    const existing = await prisma.aIProvider.findUnique({ where: { providerId } });
    if (existing) continue;
    await prisma.aIProvider.create({
      data: {
        providerId,
        name: definition.name,
        kind: definition.kind,
        baseUrl: definition.baseUrl,
        apiKeyCipher: encryptSecret(apiKey),
        enabled: true,
        priority: definition.priority,
        models: definition.models,
        limits: definition.limits as unknown as Prisma.InputJsonValue,
        health: { status: 'unknown' } as unknown as Prisma.InputJsonValue,
      },
    });
    console.info(`[ai/registry] Seeded provider "${providerId}" from AI_BOOTSTRAP_KEYS`);
  }
}

export function ensureBootstrapProviders(): Promise<void> {
  if (!bootstrapPromise) {
    bootstrapPromise = seedBootstrapProviders().catch((error) => {
      // Do not surface bootstrap failures to requests; routes degrade gracefully.
      console.warn('[ai/registry] Could not seed bootstrap providers:', (error as Error).message);
    });
  }
  return bootstrapPromise;
}

/** Test hook: allow a later call to retry bootstrap seeding. */
export function resetBootstrapState(): void {
  bootstrapPromise = null;
}

/**
 * Load the effective provider list: catalog defaults merged with DB rows.
 * Never throws on DB failure — returns catalog-only state so /ai/status and
 * local fallback keep working.
 */
export async function loadProviderState(): Promise<ProviderState> {
  await ensureBootstrapProviders();

  const catalog = buildCatalog();
  const bootstrapKeys = parseBootstrapKeys();
  let rows: AIProvider[] = [];
  let dbAvailable = false;

  try {
    const { prisma } = await import('../prisma');
    rows = await prisma.aIProvider.findMany({ orderBy: [{ priority: 'asc' }, { providerId: 'asc' }] });
    dbAvailable = true;
  } catch (error) {
    console.warn('[ai/registry] Could not load AIProvider rows:', (error as Error).message);
  }

  const rowsByProvider = new Map<string, AIProvider>();
  for (const row of rows) rowsByProvider.set(row.providerId, row);

  const providers: ProviderRuntime[] = [];
  for (const definition of catalog) {
    // `custom` has no fixed identity/base URL; only show it once an admin adds it.
    if (definition.providerId === 'custom' && !rowsByProvider.has('custom')) continue;
    providers.push(toRuntime(definition.providerId, definition, rowsByProvider.get(definition.providerId), bootstrapKeys));
  }
  // Defensive: DB rows for providerIds no longer in the catalog still render.
  for (const row of rows) {
    if (catalog.some((definition) => definition.providerId === row.providerId)) continue;
    providers.push(toRuntime(row.providerId, undefined, row, bootstrapKeys));
  }

  providers.sort((a, b) => a.priority - b.priority || a.providerId.localeCompare(b.providerId));
  return { providers, catalog, bootstrapKeys, dbAvailable };
}

/** True when the runtime can actually be called (enabled + key + base URL). */
export function isProviderReady(provider: ProviderRuntime): boolean {
  return (
    provider.enabled &&
    provider.configured &&
    Boolean(provider.baseUrl) &&
    provider.kind !== 'local' &&
    !provider.keyError
  );
}

/** Map a runtime row to the API-facing view. Cooldown comes from in-memory state. */
export function toProviderView(
  provider: ProviderRuntime,
  opts: { cooldownUntil?: string | null; healthOverride?: ProviderHealth } = {}
): ProviderView {
  const health: ProviderHealth = opts.healthOverride
    ? { ...opts.healthOverride }
    : { ...provider.health };
  if (opts.cooldownUntil) health.status = 'cooldown';
  else if (!provider.enabled && health.status !== 'error') health.status = 'disabled';
  return {
    id: provider.id,
    providerId: provider.providerId,
    name: provider.name,
    kind: provider.kind,
    ...(provider.baseUrl ? { baseUrl: provider.baseUrl } : {}),
    enabled: provider.enabled,
    priority: provider.priority,
    models: provider.models,
    configured: provider.configured,
    apiKeyMasked: provider.apiKeyMasked,
    cooldownUntil: opts.cooldownUntil ?? null,
    health,
    keySource: provider.keySource,
    ...(provider.description ? { description: provider.description } : {}),
  };
}

/** Persist health/priority metadata for a DB-backed provider (best effort). */
export async function persistProviderHealth(
  dbId: string,
  health: ProviderHealth
): Promise<void> {
  try {
    const { prisma } = await import('../prisma');
    await prisma.aIProvider.update({
      where: { id: dbId },
      data: { health: health as unknown as Prisma.InputJsonValue },
    });
  } catch (error) {
    console.warn('[ai/registry] Could not persist provider health:', (error as Error).message);
  }
}
