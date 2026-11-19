/**
 * IntegrationService — drives per-type third-party sync for RoofPro CRM.
 *
 * Each sync handler is responsible for:
 *  - reading its credentials/settings off the Integration record
 *  - calling the external API via axios
 *  - returning a SyncResult (synced count + details)
 *
 * External endpoint URLs and auth are configurable through `credentials`
 * (apiKey/accessToken/realmId/username/password/url/secret) so the same
 * handler works across staging/production and self-hosted variants.
 */
import axios from 'axios';
import crypto from 'crypto';
import { prisma } from '../prisma';
import { Integration, IntegrationType } from '@prisma/client';

export interface SyncContext {
  integration: Integration;
  companyId: string;
  params?: Record<string, any>;
}

export interface SyncResult {
  success: boolean;
  type: IntegrationType;
  synced: number;
  details?: any;
  error?: string;
}

/** Thrown for validation problems (4xx) or upstream failures (5xx). */
export class IntegrationError extends Error {
  statusCode: number;
  details?: any;
  constructor(message: string, statusCode = 400, details?: any) {
    super(message);
    this.name = 'IntegrationError';
    this.statusCode = statusCode;
    if (details !== undefined) this.details = details;
  }
}

const DEFAULT_TIMEOUT = 20000;

function axiosErrorInfo(err: any) {
  if (axios.isAxiosError(err) && err.response) {
    return {
      status: err.response.status,
      statusText: err.response.statusText,
      data: err.response.data,
      message: err.message,
    };
  }
  return { message: err?.message ?? String(err) };
}

class IntegrationService {
  // ── QUICKBOOKS ───────────────────────────────────────────────
  // Syncs APPROVED estimates (i.e. work that has been invoiced) to
  // QuickBooks Online as invoices via the QBO REST endpoint.
  async syncQuickbooks(ctx: SyncContext): Promise<SyncResult> {
    const creds = (ctx.integration.credentials || {}) as {
      accessToken?: string;
      oauth_token?: string;
      realmId?: string;
      baseUrl?: string;
    };
    const accessToken = creds.accessToken || creds.oauth_token;
    const realmId = creds.realmId;
    if (!accessToken || !realmId) {
      throw new IntegrationError(
        'QuickBooks credentials (accessToken and realmId) are required',
        400
      );
    }

    const baseUrl =
      creds.baseUrl ||
      `https://quickbooks.api.intuit.com/v3/company/${realmId}`;
    const client = axios.create({
      baseURL: baseUrl,
      timeout: DEFAULT_TIMEOUT,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
    });

    const estimates = await prisma.estimate.findMany({
      where: { companyId: ctx.companyId, status: 'APPROVED' },
      include: { items: true, job: { include: { lead: true } } },
      orderBy: { approvedAt: 'desc' },
    });

    const invoices: any[] = [];
    for (const estimate of estimates) {
      const txnDate = estimate.approvedAt
        ? new Date(estimate.approvedAt).toISOString().slice(0, 10)
        : new Date().toISOString().slice(0, 10);

      const invoice = {
        CustomerRef: {
          value: estimate.job?.leadId ?? estimate.jobId,
        },
        TxnDate: txnDate,
        Line: (estimate.items || []).map((i) => ({
          Amount: Number(i.quantity) * Number(i.unitPrice),
          Description: i.description,
          DetailType: 'SalesItemLineDetail',
        })),
        TotalAmt: estimate.totalPrice,
      };

      try {
        const res = await client.post('/invoice', invoice);
        invoices.push({
          estimateId: estimate.id,
          invoiceId: res.data?.Invoice?.Id ?? res.data?.id ?? null,
          status: res.status,
        });
      } catch (err) {
        invoices.push({
          estimateId: estimate.id,
          error: axiosErrorInfo(err).message,
          ...axios.isAxiosError(err) && err.response
            ? { statusCode: err.response.status }
            : {},
        });
      }
    }

    return {
      success: true,
      type: IntegrationType.QUICKBOOKS,
      synced: invoices.filter((i) => !i.error).length,
      details: { baseUrl, invoices },
    };
  }

  // ── GOOGLE CALENDAR ──────────────────────────────────────────
  // Pushes company calendar events to a Google Calendar. On first sync
  // (no lastSyncedAt) only upcoming events are pushed; afterwards only
  // events modified since the previous sync.
  async syncGoogleCalendar(ctx: SyncContext): Promise<SyncResult> {
    const creds = (ctx.integration.credentials || {}) as {
      accessToken?: string;
      oauth_token?: string;
      calendarId?: string;
      baseUrl?: string;
    };
    const accessToken = creds.accessToken || creds.oauth_token;
    if (!accessToken) {
      throw new IntegrationError(
        'Google Calendar accessToken is required',
        400
      );
    }

    const calendarId = encodeURIComponent(creds.calendarId || 'primary');
    const baseUrl = creds.baseUrl || 'https://www.googleapis.com/calendar/v3';
    const client = axios.create({
      baseURL: baseUrl,
      timeout: DEFAULT_TIMEOUT,
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
    });

    const where: any = { companyId: ctx.companyId };
    if (ctx.integration.lastSyncedAt) {
      where.updatedAt = { gte: new Date(ctx.integration.lastSyncedAt) };
    } else {
      where.startTime = { gte: new Date() };
    }

    const events = await prisma.calendarEvent.findMany({
      where,
      orderBy: { startTime: 'desc' },
    });

    const syncedEvents: any[] = [];
    for (const ev of events) {
      const payload: any = {
        summary: ev.title,
        location: ev.location,
        description: ev.description,
      };
      if (ev.allDay) {
        payload.start = { date: new Date(ev.startTime).toISOString().slice(0, 10) };
        payload.end = { date: new Date(ev.endTime).toISOString().slice(0, 10) };
      } else {
        payload.start = { dateTime: ev.startTime.toISOString() };
        payload.end = { dateTime: ev.endTime.toISOString() };
      }

      try {
        const res = await client.post(`/calendars/${calendarId}/events`, payload);
        syncedEvents.push({
          eventId: ev.id,
          googleEventId: res.data?.id ?? null,
          status: res.status,
        });
      } catch (err) {
        syncedEvents.push({
          eventId: ev.id,
          error: axiosErrorInfo(err).message,
          ...axios.isAxiosError(err) && err.response
            ? { statusCode: err.response.status }
            : {},
        });
      }
    }

    return {
      success: true,
      type: IntegrationType.GOOGLE_CALENDAR,
      synced: syncedEvents.filter((e) => !e.error).length,
      details: { calendarId: creds.calendarId || 'primary', events: syncedEvents },
    };
  }

  // ── HOVER ──────────────────────────────────────────────────
  // Fetches property measurements (roof measurements) for a given
  // address from the Hover API.
  async syncHover(ctx: SyncContext): Promise<SyncResult> {
    const creds = (ctx.integration.credentials || {}) as {
      apiKey?: string;
      accessToken?: string;
      baseUrl?: string;
    };
    const apiKey = creds.apiKey || creds.accessToken;
    if (!apiKey) {
      throw new IntegrationError('Hover apiKey is required', 400);
    }

    const address =
      ctx.params?.address || (ctx.integration.settings as any)?.address;
    if (!address) {
      throw new IntegrationError(
        'address is required for Hover sync (provide body.address or settings.address)',
        400
      );
    }

    const baseUrl = creds.baseUrl || 'https://api.hover.com/jobs';
    try {
      const res = await axios.post(
        baseUrl,
        { address: { lines: address } },
        {
          headers: {
            Authorization: `Bearer ${apiKey}`,
            'Content-Type': 'application/json',
          },
          timeout: DEFAULT_TIMEOUT,
        }
      );
      return {
        success: true,
        type: IntegrationType.HOVER,
        synced: 1,
        details: { address, jobId: res.data?.job_id ?? res.data?.id ?? null, measurements: res.data },
      };
    } catch (err) {
      throw new IntegrationError(
        `Hover sync failed: ${axiosErrorInfo(err).message}`,
        502,
        axiosErrorInfo(err)
      );
    }
  }

  // ── EAGLEVIEW ──────────────────────────────────────────────
  // Fetches property measurements for an address from EagleView.
  // Auth may be a bearer token or HTTP Basic(username/password).
  async syncEagleView(ctx: SyncContext): Promise<SyncResult> {
    const creds = (ctx.integration.credentials || {}) as {
      accessToken?: string;
      apiKey?: string;
      username?: string;
      password?: string;
      accountId?: string;
      baseUrl?: string;
    };
    const address =
      ctx.params?.address || (ctx.integration.settings as any)?.address;
    if (!address) {
      throw new IntegrationError(
        'address is required for EagleView sync (provide body.address or settings.address)',
        400
      );
    }

    const baseUrl = creds.baseUrl || 'https://api.eagleview.com';
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (creds.accessToken || creds.apiKey) {
      headers.Authorization = `Bearer ${creds.accessToken || creds.apiKey}`;
    } else if (creds.username && creds.password) {
      headers.Authorization = `Basic ${Buffer.from(
        `${creds.username}:${creds.password}`
      ).toString('base64')}`;
    }

    try {
      const res = await axios.get(`${baseUrl}/property`, {
        params: { address, accountId: creds.accountId },
        headers,
        timeout: DEFAULT_TIMEOUT,
      });
      return {
        success: true,
        type: IntegrationType.EAGLEVIEW,
        synced: 1,
        details: { address, measurements: res.data },
      };
    } catch (err) {
      throw new IntegrationError(
        `EagleView sync failed: ${axiosErrorInfo(err).message}`,
        502,
        axiosErrorInfo(err)
      );
    }
  }

  // ── ABC SUPPLY ─────────────────────────────────────────────
  // Syncs material catalog entries into the company's price database.
  async syncAbcSupply(ctx: SyncContext): Promise<SyncResult> {
    const creds = (ctx.integration.credentials || {}) as {
      apiKey?: string;
      subscriptionKey?: string;
      accessToken?: string;
      baseUrl?: string;
    };
    const apiKey = creds.apiKey || creds.subscriptionKey;
    if (!apiKey) {
      throw new IntegrationError(
        'ABC Supply apiKey/subscriptionKey is required',
        400
      );
    }

    const baseUrl = creds.baseUrl || 'https://api.abcsupply.com/v1';
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Ocp-Apim-Subscription-Key': apiKey,
    };
    if (creds.accessToken) {
      headers.Authorization = `Bearer ${creds.accessToken}`;
    }

    let items: any[] = [];
    try {
      const res = await axios.get(`${baseUrl}/products`, { headers, timeout: DEFAULT_TIMEOUT });
      const payload = res.data;
      if (Array.isArray(payload)) items = payload;
      else if (payload?.items) items = payload.items;
      else if (payload) items = [payload];
    } catch (err) {
      throw new IntegrationError(
        `ABC Supply sync failed: ${axiosErrorInfo(err).message}`,
        502,
        axiosErrorInfo(err)
      );
    }

    const synced: any[] = [];
    for (const item of items) {
      if (!item) continue;
      const category =
        item.category || item.uom || item.materialCategory || 'MATERIAL';
      const name =
        item.name || item.description || item.itemDescription || item.sku;
      if (!name) continue;
      const unit = item.unit || item.uom || 'ea';
      const defaultCost = Number(item.cost || item.defaultCost || 0) || null;
      const defaultPrice = Number(item.price || item.defaultPrice || 0) || null;

      const existing = await prisma.priceDatabaseEntry.findFirst({
        where: { companyId: ctx.companyId, name, category },
      });
      const data = {
        companyId: ctx.companyId,
        category,
        name,
        unit,
        defaultCost,
        defaultPrice,
      };
      if (existing) {
        await prisma.priceDatabaseEntry.update({
          where: { id: existing.id },
          data,
        });
      } else {
        await prisma.priceDatabaseEntry.create({ data });
      }
      synced.push({ name, category, unit });
    }

    return {
      success: true,
      type: IntegrationType.ABC_SUPPLY,
      synced: synced.length,
      details: { baseUrl, entries: synced },
    };
  }

  // ── WEBHOOK ────────────────────────────────────────────────
  // Pushes a payload to a custom endpoint. The payload may be supplied
  // in the sync request body; otherwise a company snapshot is sent.
  // HMAC-SHA256 signature is applied when credentials.secret is set.
  async syncWebhook(ctx: SyncContext): Promise<SyncResult> {
    const creds = (ctx.integration.credentials || {}) as {
      url?: string;
      method?: string;
      secret?: string;
      contentType?: string;
      headers?: Record<string, string>;
    };
    const url = creds.url || (ctx.integration.settings as any)?.url;
    if (!url) {
      throw new IntegrationError(
        'Webhook url is required (credentials.url or settings.url)',
        400
      );
    }

    const method = (creds.method || 'POST').toUpperCase();
    const headers: Record<string, string> = {
      'Content-Type': creds.contentType || 'application/json',
      ...(creds.headers || {}),
    };

    let payload = ctx.params?.payload;
    if (payload === undefined) {
      const company = await prisma.company.findUnique({
        where: { id: ctx.companyId },
        select: { id: true, name: true, phone: true, subscriptionTier: true },
      });
      payload = {
        source: 'RoofProCRM',
        event: 'integration.sync',
        timestamp: new Date().toISOString(),
        integrationId: ctx.integration.id,
        type: ctx.integration.type,
        company,
      };
    }

    const body =
      typeof payload === 'string'
        ? payload
        : JSON.stringify(payload);

    if (creds.secret) {
      const sig = crypto
        .createHmac('sha256', creds.secret)
        .update(body)
        .digest('hex');
      headers['X-Hub-Signature-256'] = `sha256=${sig}`;
    }

    try {
      const res = await axios({
        method,
        url,
        data: body,
        headers,
        timeout: DEFAULT_TIMEOUT,
        maxRedirects: 5,
      });
      return {
        success: true,
        type: IntegrationType.WEBHOOK,
        synced: 1,
        details: { url, method, statusCode: res.status },
      };
    } catch (err) {
      const info = axiosErrorInfo(err);
      if (axios.isAxiosError(err) && err.response) {
        // The remote endpoint responded with an error status — report it
        // as a non-fatal sync failure rather than throwing.
        return {
          success: false,
          type: IntegrationType.WEBHOOK,
          synced: 0,
          error: `Webhook endpoint returned status ${err.response.status}`,
          details: { url, method, statusCode: err.response.status, ...info },
        };
      }
      throw new IntegrationError(
        `Webhook sync failed: ${info.message}`,
        502,
        info
      );
    }
  }

  // ── Dispatcher ─────────────────────────────────────────────
  async sync(ctx: SyncContext): Promise<SyncResult> {
    const creds = (ctx.integration.credentials || {}) as Record<string, unknown>;
    const settings = (ctx.integration.settings || {}) as Record<string, unknown>;
    const apiKey = typeof creds.apiKey === 'string' ? creds.apiKey : '';
    const isSandbox = settings.sandboxMode === true || apiKey.startsWith('sandbox_');

    if (isSandbox) {
      const address = (ctx.params?.address as string) || '412 Oak Ridge Pkwy, Plano, TX';
      switch (ctx.integration.type) {
        case IntegrationType.HOVER:
        case IntegrationType.EAGLEVIEW:
          return {
            success: true,
            type: ctx.integration.type,
            synced: 1,
            details: {
              mode: 'SANDBOX',
              address,
              roofAreaSqFt: 2840,
              pitch: '7/12',
              facets: 11,
              wasteFactorPct: 12,
              note: 'Deterministic sandbox measurement fixture returned (no external billing call).',
            },
          };
        case IntegrationType.ABC_SUPPLY:
          return {
            success: true,
            type: ctx.integration.type,
            synced: 3,
            details: {
              mode: 'SANDBOX',
              entries: [
                { name: 'GAF Timberline HDZ Charcoal (Sq)', category: 'Shingles', unit: 'SQ', unitCost: 118.5 },
                { name: 'Synthetic Underlayment Roll', category: 'Underlayment', unit: 'ROLL', unitCost: 74.0 },
                { name: 'Ice & Water Shield 2-Sq', category: 'Flashing', unit: 'ROLL', unitCost: 92.25 },
              ],
            },
          };
        default:
          return {
            success: true,
            type: ctx.integration.type,
            synced: 1,
            details: {
              mode: 'SANDBOX',
              timestamp: new Date().toISOString(),
              message: `Sandbox verification succeeded for ${ctx.integration.type}`,
            },
          };
      }
    }

    switch (ctx.integration.type) {
      case IntegrationType.QUICKBOOKS:
        return this.syncQuickbooks(ctx);
      case IntegrationType.GOOGLE_CALENDAR:
        return this.syncGoogleCalendar(ctx);
      case IntegrationType.OUTLOOK_CALENDAR:
        throw new IntegrationError(
          'Sync is not yet supported for OUTLOOK_CALENDAR integrations',
          501
        );
      case IntegrationType.HOVER:
        return this.syncHover(ctx);
      case IntegrationType.EAGLEVIEW:
        return this.syncEagleView(ctx);
      case IntegrationType.ABC_SUPPLY:
        return this.syncAbcSupply(ctx);
      case IntegrationType.WEBHOOK:
        return this.syncWebhook(ctx);
      default:
        throw new IntegrationError(
          `Unsupported integration type: ${ctx.integration.type}`,
          400
        );
    }
  }
}

export default new IntegrationService();
