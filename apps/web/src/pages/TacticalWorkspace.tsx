import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity,
  Award,
  CheckCircle2,
  CloudLightning,
  Compass,
  Cpu,
  ExternalLink,
  FileCheck2,
  GraduationCap,
  Home,
  Layers,
  Lock,
  LogIn,
  LogOut,
  Map as MapIcon,
  Radio,
  Shield,
  ShieldAlert,
  ShieldCheck,
  Vault,
} from 'lucide-react';
import { BRAND_CONFIG } from '@/brand/brandConfig';
import { api, formatUsd } from '@/lib/api';
import { useAuthStore } from '@/stores/authStore';
import { ShortestFixModal } from '@/components/ShortestFixModal';
import { EconomicLab } from '@/components/EconomicLab';
import { FieldLiveConsole } from '@/components/FieldLiveConsole';
import { StormMapConsole } from '@/components/StormMapConsole';
import type {
  AcademyResponse,
  CatalogResponse,
  CredentialGateBlock,
  HardwarePoolItem,
  MicroZoneCluster,
  SpatialAssetItem,
} from '@/types/vanguard';

type NavTab = 'overview' | 'dispatch' | 'storms' | 'academy' | 'economics' | 'field' | 'attestor';

const NAV_ITEMS: Array<{ id: NavTab; label: string; icon: typeof Compass }> = [
  { id: 'overview', label: 'Network & Verticals', icon: Compass },
  { id: 'dispatch', label: 'H3 Batch Dispatch', icon: MapIcon },
  { id: 'storms', label: 'Storms & Spatial Map', icon: CloudLightning },
  { id: 'academy', label: 'Open Academy', icon: GraduationCap },
  { id: 'economics', label: 'Economics & DePIN', icon: Vault },
  { id: 'field', label: 'Field Feed & Live', icon: Radio },
  { id: 'attestor', label: 'Attestor & Security', icon: Shield },
];

export function TacticalWorkspace() {
  const { user, demoLogin, logout, busy: authBusy } = useAuthStore();
  const [activeTab, setActiveTab] = useState<NavTab>('overview');
  const [catalog, setCatalog] = useState<CatalogResponse | null>(null);
  const [academy, setAcademy] = useState<AcademyResponse | null>(null);
  const [clusters, setClusters] = useState<MicroZoneCluster[]>([]);
  const [selectedClusterIdx, setSelectedClusterIdx] = useState(0);
  const [vaultState, setVaultState] = useState<{
    vault: { balanceCents: number; routingBps: number; totalContributedCents: number };
  } | null>(null);
  const [depinAssets, setDepinAssets] = useState<SpatialAssetItem[]>([]);
  const [hardwarePools, setHardwarePools] = useState<HardwarePoolItem[]>([]);
  const [dossiers, setDossiers] = useState<{ packets: any[]; attestLogs: any[] }>({
    packets: [],
    attestLogs: [],
  });
  const [tierFilter, setTierFilter] = useState<0 | 1 | 2 | 3 | 4>(0);
  const [licenseInput, setLicenseInput] = useState('TX-ADJ-410299');
  const [pinInputs, setPinInputs] = useState<Record<string, string>>({});
  const [shortestFix, setShortestFix] = useState<CredentialGateBlock | null>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const [statusBanner, setStatusBanner] = useState<string | null>(null);

  // Anti-spoofing console state
  const [attestVerdict, setAttestVerdict] = useState('MEETS_STRONG_INTEGRITY');
  const [keyboxSerial, setKeyboxSerial] = useState('CLEAN_OEM_KEYBOX_9921');
  const [imuPreset, setImuPreset] = useState<'organic' | 'flat'>('organic');
  const [attestOutcome, setAttestOutcome] = useState<any | null>(null);

  async function loadPublicCatalog() {
    try {
      const { data } = await api.get('/public/vanguard/catalog');
      setCatalog(data);
    } catch {
      // handled gracefully
    }
  }

  async function loadAuthenticatedData() {
    if (!user) return;
    try {
      const [acadRes, dispRes, vaultRes, depinRes, dosRes] = await Promise.all([
        api.get('/vanguard/academy'),
        api.get('/vanguard/dispatch/clusters'),
        api.get('/vanguard/vault/earn-to-learn'),
        api.get('/vanguard/depin/portfolio'),
        api.get('/vanguard/commander/dossiers'),
      ]);
      setAcademy(acadRes.data);
      setClusters(dispRes.data?.clusters ?? []);
      setVaultState(vaultRes.data);
      setDepinAssets(depinRes.data?.assets ?? []);
      setHardwarePools(depinRes.data?.hardwarePools ?? []);
      setDossiers(dosRes.data ?? { packets: [], attestLogs: [] });
    } catch {
      // ignore if session expired
    }
  }

  useEffect(() => {
    void loadPublicCatalog();
  }, []);

  useEffect(() => {
    if (user) {
      void loadAuthenticatedData();
    }
  }, [user]);

  async function completeModule(code: string) {
    setActionBusy(true);
    setStatusBanner(null);
    try {
      const payload = code === 'T4-01' ? { licenseNumber: licenseInput } : {};
      await api.post(`/vanguard/academy/modules/${encodeURIComponent(code)}/complete`, payload);
      setShortestFix(null);
      setStatusBanner(`Completed module ${code} and updated operator credentials.`);
      await loadAuthenticatedData();
    } catch (err: any) {
      setStatusBanner(err?.response?.data?.error || `Could not complete ${code}`);
    } finally {
      setActionBusy(false);
    }
  }

  async function claimTask(taskId: string) {
    setActionBusy(true);
    setStatusBanner(null);
    try {
      await api.post(`/vanguard/dispatch/tasks/${taskId}/claim`, {});
      setStatusBanner('Task claimed inside H3 cluster escrow.');
      await loadAuthenticatedData();
    } catch (err: any) {
      if (err?.response?.status === 403 && err.response.data?.code === 'CREDENTIAL_REQUIRED') {
        setShortestFix(err.response.data as CredentialGateBlock);
      } else {
        setStatusBanner(err?.response?.data?.error || 'Could not claim task.');
      }
    } finally {
      setActionBusy(false);
    }
  }

  async function confirmTaskPin(taskId: string) {
    const pin = (pinInputs[taskId] || '').trim();
    if (!pin) return;
    setActionBusy(true);
    setStatusBanner(null);
    try {
      await api.post(`/vanguard/dispatch/tasks/${taskId}/confirm-pin`, { pin });
      setStatusBanner('Counterparty 4-digit PIN confirmed.');
      await loadAuthenticatedData();
    } catch (err: any) {
      setStatusBanner(err?.response?.data?.error || 'PIN did not match.');
    } finally {
      setActionBusy(false);
    }
  }

  async function settleTask(taskId: string) {
    setActionBusy(true);
    setStatusBanner(null);
    try {
      const { data } = await api.post(`/vanguard/dispatch/tasks/${taskId}/settle`, {
        telemetryPayload: {
          pitchRatio: '7/12',
          shingleWearScore: 4,
          notes: 'Objective field capture verified',
        },
      });
      setStatusBanner(
        `Settled to ledger: ${formatUsd(data.workerDirectCents)} direct + ${formatUsd(data.vaultCutCents)} to Hardware Vault.`,
      );
      await loadAuthenticatedData();
    } catch (err: any) {
      setStatusBanner(err?.response?.data?.error || 'Settlement error.');
    } finally {
      setActionBusy(false);
    }
  }

  async function updateVaultRouting(routingBps: number) {
    setActionBusy(true);
    try {
      await api.put('/vanguard/vault/earn-to-learn/routing', { routingBps });
      await loadAuthenticatedData();
      setStatusBanner(`Updated Earn-to-Learn hardware escrow split to ${(routingBps / 100).toFixed(0)}%.`);
    } finally {
      setActionBusy(false);
    }
  }

  async function redeemKit(sku: string) {
    setActionBusy(true);
    setStatusBanner(null);
    try {
      const { data } = await api.post('/vanguard/vault/earn-to-learn/redeem', { sku });
      setStatusBanner(`Unlocked hardware kit: ${data.unlockedItem?.name || sku}`);
      await loadAuthenticatedData();
    } catch (err: any) {
      setStatusBanner(err?.response?.data?.error || 'Unable to redeem kit yet.');
    } finally {
      setActionBusy(false);
    }
  }

  async function licenseAsset(assetId: string) {
    setActionBusy(true);
    try {
      const { data } = await api.post(`/vanguard/depin/assets/${assetId}/license-query`, {
        buyerName: 'Underwriting Carrier API',
      });
      setStatusBanner(
        `Query licensed at ${formatUsd(data.halfLife.queryPriceCents)} · Worker earned ${formatUsd(data.halfLife.workerRoyaltyCents)}.`,
      );
      await loadAuthenticatedData();
    } finally {
      setActionBusy(false);
    }
  }

  async function investPool(poolId: string) {
    setActionBusy(true);
    try {
      await api.post(`/vanguard/depin/pools/${poolId}/invest`, { shares: 1 });
      setStatusBanner('Allocated 1 fractional share in Hardware Vault Pool.');
      await loadAuthenticatedData();
    } catch (err: any) {
      setStatusBanner(err?.response?.data?.error || 'Could not allocate pool share.');
    } finally {
      setActionBusy(false);
    }
  }

  async function coSignDossier(packetId: string) {
    setActionBusy(true);
    setStatusBanner(null);
    try {
      await api.post(`/vanguard/commander/dossiers/${packetId}/attest`, {
        statement: 'Dual-Sign Desk verified objective field packet completeness (TX Ch. 4102 scope).',
      });
      setStatusBanner('Appended formal RecordKind: ATTEST signature and anchored Merkle Tree Head.');
      await loadAuthenticatedData();
    } catch (err: any) {
      if (err?.response?.status === 403 && err.response.data?.code === 'CREDENTIAL_REQUIRED') {
        setShortestFix(err.response.data as CredentialGateBlock);
      } else {
        setStatusBanner(err?.response?.data?.error || 'Co-sign failed.');
      }
    } finally {
      setActionBusy(false);
    }
  }

  async function runHardwareAttest() {
    setActionBusy(true);
    setAttestOutcome(null);
    try {
      const { data: challenge } = await api.post('/vanguard/attest/challenge', {});
      const samples =
        imuPreset === 'organic'
          ? [0.11, 0.19, 0.06, 0.27, 0.13, 0.22]
          : [1.0, 1.0, 1.0, 1.0, 1.0, 1.0];
      const { data: verify } = await api.post('/vanguard/attest/verify', {
        nonce: challenge.nonce,
        verdict: attestVerdict,
        keyboxSerial,
        imuSamples: samples,
      });
      setAttestOutcome(verify);
    } catch (err: any) {
      setAttestOutcome(err?.response?.data || { passed: false, reason: 'Verification failed' });
    } finally {
      setActionBusy(false);
    }
  }

  const activeCluster = clusters[selectedClusterIdx] || clusters[0] || null;

  return (
    <div className="min-h-screen bg-obsidian-ground text-obsidian-ink flex flex-col lg:flex-row">
      {/* Left Navigation Rail */}
      <aside className="w-full lg:w-64 shrink-0 border-b lg:border-b-0 lg:border-r border-obsidian-border bg-obsidian-surface flex flex-col justify-between">
        <div>
          <div className="flex items-center justify-between px-5 py-5 border-b border-obsidian-border">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-obsidian-amber font-display text-lg font-extrabold text-obsidian-ground">
                V
              </div>
              <div>
                <div className="font-display text-base font-bold tracking-tight text-obsidian-ink">
                  {BRAND_CONFIG.platformName}
                </div>
                <div className="font-mono text-[11px] text-obsidian-muted">
                  {BRAND_CONFIG.mobileAppName}
                </div>
              </div>
            </div>
          </div>

          <nav aria-label="Workspace Sections" className="p-3 space-y-1">
            {NAV_ITEMS.map((item) => {
              const Icon = item.icon;
              const selected = activeTab === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setActiveTab(item.id)}
                  className={`flex w-full items-center gap-3 rounded-lg px-3.5 py-2.5 text-sm font-medium transition ${
                    selected
                      ? 'bg-obsidian-elevated text-obsidian-amber border border-obsidian-border'
                      : 'text-obsidian-muted hover:bg-obsidian-elevated/50 hover:text-obsidian-ink'
                  }`}
                >
                  <Icon className="h-4 w-4 shrink-0" />
                  <span>{item.label}</span>
                </button>
              );
            })}
          </nav>
        </div>

        <div className="p-4 border-t border-obsidian-border space-y-2.5 text-xs">
          <Link
            to="/community"
            className="flex items-center justify-between rounded-lg border border-obsidian-border bg-obsidian-ground px-3.5 py-2.5 font-medium text-obsidian-ink transition hover:border-obsidian-proof"
          >
            <span className="inline-flex items-center gap-2">
              <Home className="h-3.5 w-3.5 text-obsidian-proof" />
              Neighborhood Portal
            </span>
            <ExternalLink className="h-3.5 w-3.5 text-obsidian-muted" />
          </Link>

          <Link
            to="/verify"
            className="flex items-center justify-between rounded-lg border border-obsidian-border bg-obsidian-ground px-3.5 py-2.5 font-medium text-obsidian-ink transition hover:border-obsidian-amber"
          >
            <span className="inline-flex items-center gap-2">
              <FileCheck2 className="h-3.5 w-3.5 text-obsidian-amber" />
              Public Chain Verifier
            </span>
            <ExternalLink className="h-3.5 w-3.5 text-obsidian-muted" />
          </Link>

          {user ? (
            <div className="pt-2 flex items-center justify-between gap-2">
              <div className="truncate">
                <div className="font-semibold text-obsidian-ink truncate">
                  {user.firstName} {user.lastName}
                </div>
                <div className="font-mono text-[11px] text-obsidian-muted truncate">{user.email}</div>
              </div>
              <button
                type="button"
                onClick={logout}
                title="Sign out"
                className="rounded-lg border border-obsidian-border p-2 text-obsidian-muted hover:text-obsidian-ink"
              >
                <LogOut className="h-3.5 w-3.5" />
              </button>
            </div>
          ) : (
            <Link
              to="/login"
              className="flex items-center justify-center gap-2 rounded-lg bg-obsidian-amber px-3 py-2 font-semibold text-obsidian-ground"
            >
              <LogIn className="h-3.5 w-3.5" />
              Operator Sign In
            </Link>
          )}
        </div>
      </aside>

      {/* Main Asymmetric Canvas */}
      <div className="flex-1 flex flex-col min-w-0">
        <header className="border-b border-obsidian-border bg-obsidian-surface/80 px-6 py-5">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h1 className="font-display text-2xl font-bold tracking-tight text-obsidian-ink">
                {NAV_ITEMS.find((n) => n.id === activeTab)?.label}
              </h1>
              <p className="mt-0.5 text-sm text-obsidian-muted">
                {BRAND_CONFIG.surfaces.tactical.name} · Dual-Surface Cryptographic Dispatch
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              {!user ? (
                <button
                  type="button"
                  disabled={authBusy}
                  onClick={() => void demoLogin()}
                  className="inline-flex items-center gap-2 rounded-lg bg-obsidian-proof px-4 py-2 text-sm font-semibold text-obsidian-ground transition hover:brightness-110"
                >
                  <ShieldCheck className="h-4 w-4" />
                  {authBusy ? 'Entering Demo...' : 'Enter Demo Operations'}
                </button>
              ) : (
                <div className="flex flex-wrap items-center gap-3 font-mono text-xs">
                  <div className="rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-1.5 tnum">
                    <span className="text-obsidian-muted">Tier </span>
                    <strong className="text-obsidian-amber">T{academy?.highestTier ?? 1}</strong>
                  </div>
                  <div className="rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-1.5 tnum">
                    <span className="text-obsidian-muted">Modules </span>
                    <strong className="text-obsidian-proof">
                      {academy?.completedCount ?? 0}/{academy?.totalModules ?? 24}
                    </strong>
                  </div>
                  <div className="rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-1.5 tnum">
                    <span className="text-obsidian-muted">Vault </span>
                    <strong className="text-obsidian-ink">
                      {formatUsd(vaultState?.vault?.balanceCents ?? 0)}
                    </strong>
                  </div>
                </div>
              )}
            </div>
          </div>

          {statusBanner && (
            <div
              role="status"
              className="mt-4 flex items-center justify-between rounded-lg border border-obsidian-proof/40 bg-obsidian-proof/10 px-4 py-2.5 text-xs font-medium text-obsidian-proof"
            >
              <span>{statusBanner}</span>
              <button type="button" onClick={() => setStatusBanner(null)} className="underline">
                Dismiss
              </button>
            </div>
          )}
        </header>

        <main className="flex-1 p-6 space-y-6 overflow-y-auto">
          {/* TAB 1: OVERVIEW & VERTICAL MATRIX */}
          {activeTab === 'overview' && (
            <div className="grid grid-cols-1 gap-6 xl:grid-cols-12">
              {/* Career Tiers progression list */}
              <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-5 space-y-4">
                <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
                  <h2 className="font-display text-lg font-bold text-obsidian-ink">
                    Four-Tier Verification Progression
                  </h2>
                  <Award className="h-5 w-5 text-obsidian-amber" />
                </div>

                <div className="space-y-3">
                  {(catalog?.tiers ?? []).map((t) => {
                    const unlocked = (academy?.highestTier ?? 1) >= t.tier;
                    return (
                      <div
                        key={t.tier}
                        className={`rounded-lg border p-4 ${
                          unlocked
                            ? 'border-obsidian-proof/50 bg-obsidian-ground'
                            : 'border-obsidian-border bg-obsidian-ground/50'
                        }`}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="font-display text-sm font-bold text-obsidian-ink">
                            Tier {t.tier} — {t.roleTitle}
                          </span>
                          <span className="font-mono text-xs font-bold text-obsidian-amber tnum">
                            {t.payoutHeadline}
                          </span>
                        </div>
                        <p className="mt-1 text-xs text-obsidian-muted">{t.legalBoundary}</p>
                      </div>
                    );
                  })}
                </div>

                {catalog?.stagingDisclosure && (
                  <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5 font-mono text-xs text-obsidian-muted">
                    {catalog.stagingDisclosure}
                  </div>
                )}
              </section>

              {/* Market Verticals & Buyer-Paid Fee Table */}
              <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-7 space-y-5">
                <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
                  <h2 className="font-display text-lg font-bold text-obsidian-ink">
                    Ten Active Demand Verticals (0 bps Operator Fee)
                  </h2>
                  <Layers className="h-5 w-5 text-obsidian-proof" />
                </div>

                <div className="overflow-x-auto">
                  <table className="w-full text-left text-xs">
                    <thead className="border-b border-obsidian-border font-mono text-obsidian-muted">
                      <tr>
                        <th className="py-2 pr-3">Code</th>
                        <th className="py-2 pr-3">Vertical Channel</th>
                        <th className="py-2 pr-3">Rate Band</th>
                        <th className="py-2">Gate / Trigger</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-obsidian-border/60">
                      {(catalog?.verticals ?? []).map((v) => (
                        <tr key={v.code}>
                          <td className="py-2.5 pr-3 font-mono font-semibold text-obsidian-amber">
                            {v.code}
                          </td>
                          <td className="py-2.5 pr-3">
                            <div className="font-semibold text-obsidian-ink">{v.name}</div>
                            <div className="text-obsidian-muted">{v.surfaceLabel}</div>
                          </td>
                          <td className="py-2.5 pr-3 font-mono font-semibold text-obsidian-proof tnum">
                            {v.payoutRangeLabel}
                          </td>
                          <td className="py-2.5 text-obsidian-muted">
                            {v.requiredCredential || 'Zero-Paywall Floor (I-1)'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            </div>
          )}

          {/* TAB 2: H3 BATCH DISPATCH */}
          {activeTab === 'dispatch' && (
            <div className="space-y-5">
              {!user ? (
                <div className="rounded-xl border border-obsidian-border bg-obsidian-surface p-8 text-center">
                  <p className="text-sm text-obsidian-muted">
                    Sign in or enter the live demo session to claim H3 cluster bounties and settle escrow tasks.
                  </p>
                </div>
              ) : (
                <>
                  <div className="flex flex-wrap gap-3">
                    {clusters.map((cl, idx) => (
                      <button
                        key={cl.h3Index}
                        type="button"
                        onClick={() => setSelectedClusterIdx(idx)}
                        className={`rounded-xl border px-4 py-3 text-left transition ${
                          idx === selectedClusterIdx
                            ? 'border-obsidian-amber bg-obsidian-surface'
                            : 'border-obsidian-border bg-obsidian-surface/60 hover:border-obsidian-muted'
                        }`}
                      >
                        <div className="font-mono text-xs text-obsidian-amber">H3 {cl.h3Index}</div>
                        <div className="mt-1 font-display text-base font-bold text-obsidian-ink tnum">
                          {formatUsd(cl.economics.netCents)} net · {formatUsd(cl.economics.netHourlyCents)}/hr
                        </div>
                        <div className="mt-0.5 text-xs text-obsidian-muted tnum">
                          {cl.taskCount} tasks · {cl.estimatedLoopMiles} mi loop (@ $0.76/mi)
                        </div>
                      </button>
                    ))}
                  </div>

                  {activeCluster && (
                    <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6">
                      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-obsidian-border pb-4">
                        <div>
                          <h2 className="font-display text-lg font-bold text-obsidian-ink">
                            Active Micro-Zone Bundle ({activeCluster.h3Index})
                          </h2>
                          <p className="text-xs text-obsidian-muted tnum">
                            Gross {formatUsd(activeCluster.economics.grossCents)} minus IRS{' '}
                            {formatUsd(activeCluster.economics.vehicleCostCents)} vehicle wear ={' '}
                            <strong className="text-obsidian-proof">
                              {formatUsd(activeCluster.economics.netCents)} Net
                            </strong>
                          </p>
                        </div>
                      </div>

                      <div className="mt-4 divide-y divide-obsidian-border">
                        {activeCluster.tasks.map((task) => {
                          const gated = !task.credentialGate.allowed;
                          return (
                            <div
                              key={task.id}
                              className="py-4 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between"
                            >
                              <div className="space-y-1">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="rounded bg-obsidian-ground px-2 py-0.5 font-mono text-xs text-obsidian-amber">
                                    {task.verticalCode} · T{task.requiredTier}
                                  </span>
                                  <span className="font-semibold text-sm text-obsidian-ink">
                                    {task.title}
                                  </span>
                                  <span className="rounded bg-obsidian-elevated px-2 py-0.5 font-mono text-[11px] text-obsidian-muted">
                                    {task.status}
                                  </span>
                                </div>
                                <p className="text-xs text-obsidian-muted">{task.address}</p>
                                <p className="text-[11px] font-mono text-obsidian-muted">
                                  {task.feeDisclosure}
                                </p>
                              </div>

                              <div className="flex flex-wrap items-center gap-3 shrink-0">
                                <span className="font-mono text-base font-bold text-obsidian-proof tnum">
                                  {formatUsd(task.grossBountyCents)}
                                </span>

                                {task.status === 'AVAILABLE' && (
                                  <button
                                    type="button"
                                    disabled={actionBusy}
                                    onClick={() => void claimTask(task.id)}
                                    className={`inline-flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-xs font-semibold transition ${
                                      gated
                                        ? 'border border-obsidian-amber/60 bg-obsidian-amber/10 text-obsidian-amber hover:bg-obsidian-amber/20'
                                        : 'bg-obsidian-amber text-obsidian-ground hover:brightness-110'
                                    }`}
                                  >
                                    {gated && <Lock className="h-3.5 w-3.5" />}
                                    {gated ? 'Inspect Gate (I-5)' : 'Claim Task'}
                                  </button>
                                )}

                                {task.status === 'CLAIMED' && task.surface === 'helios.local.b2c' && (
                                  <div className="flex items-center gap-2">
                                    <input
                                      type="text"
                                      maxLength={4}
                                      placeholder="4-digit PIN"
                                      aria-label={`Verbal PIN for ${task.title}`}
                                      value={pinInputs[task.id] || ''}
                                      onChange={(e) =>
                                        setPinInputs((prev) => ({ ...prev, [task.id]: e.target.value }))
                                      }
                                      className="w-28 rounded-lg border border-obsidian-border bg-obsidian-ground px-2.5 py-1.5 font-mono text-xs text-obsidian-ink"
                                    />
                                    <button
                                      type="button"
                                      disabled={actionBusy}
                                      onClick={() => void confirmTaskPin(task.id)}
                                      className="rounded-lg bg-obsidian-proof px-3 py-1.5 text-xs font-semibold text-obsidian-ground"
                                    >
                                      Confirm PIN
                                    </button>
                                  </div>
                                )}

                                {(task.status === 'PIN_CONFIRMED' ||
                                  (task.status === 'CLAIMED' && task.surface !== 'helios.local.b2c')) && (
                                  <button
                                    type="button"
                                    disabled={actionBusy}
                                    onClick={() => void settleTask(task.id)}
                                    className="rounded-lg bg-obsidian-proof px-3.5 py-2 text-xs font-semibold text-obsidian-ground"
                                  >
                                    Settle Bounty Now
                                  </button>
                                )}
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </section>
                  )}
                </>
              )}
            </div>
          )}

          {/* TAB 3: OPEN ACADEMY */}
          {activeTab === 'academy' && (
            <div className="space-y-5">
              <div className="flex flex-wrap items-center justify-between gap-4">
                <div className="flex flex-wrap gap-2">
                  {([0, 1, 2, 3, 4] as const).map((tier) => (
                    <button
                      key={tier}
                      type="button"
                      onClick={() => setTierFilter(tier)}
                      className={`rounded-lg px-3.5 py-1.5 font-mono text-xs font-medium transition ${
                        tierFilter === tier
                          ? 'bg-obsidian-amber text-obsidian-ground font-semibold'
                          : 'border border-obsidian-border bg-obsidian-surface text-obsidian-muted hover:text-obsidian-ink'
                      }`}
                    >
                      {tier === 0 ? 'All 24 Modules' : `Tier ${tier}`}
                    </button>
                  ))}
                </div>

                <div className="flex items-center gap-2">
                  <label htmlFor="t4-license" className="text-xs text-obsidian-muted">
                    TX Ch. 4102 / Contractor License (for T4-01):
                  </label>
                  <input
                    id="t4-license"
                    type="text"
                    value={licenseInput}
                    onChange={(e) => setLicenseInput(e.target.value)}
                    className="rounded-lg border border-obsidian-border bg-obsidian-surface px-3 py-1.5 font-mono text-xs text-obsidian-ink"
                  />
                </div>
              </div>

              <div className="overflow-x-auto rounded-xl border border-obsidian-border bg-obsidian-surface">
                <table className="w-full text-left text-xs">
                  <thead className="border-b border-obsidian-border font-mono text-obsidian-muted">
                    <tr>
                      <th className="px-4 py-3">Module</th>
                      <th className="px-4 py-3">Title & Unlocks</th>
                      <th className="px-4 py-3">Credential Granted</th>
                      <th className="px-4 py-3">Cost / Time</th>
                      <th className="px-4 py-3 text-right">Status</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-obsidian-border/60">
                    {(academy?.modules ?? catalog?.modules ?? [])
                      .filter((m) => tierFilter === 0 || m.tier === tierFilter)
                      .map((m) => (
                        <tr key={m.code}>
                          <td className="px-4 py-3 font-mono font-bold text-obsidian-amber">
                            {m.code}
                          </td>
                          <td className="px-4 py-3">
                            <div className="font-semibold text-obsidian-ink">{m.title}</div>
                            <div className="text-obsidian-muted">{m.neutralityBoundary}</div>
                          </td>
                          <td className="px-4 py-3 font-mono text-obsidian-proof">
                            {m.grantsCredential || 'Foundation'}
                          </td>
                          <td className="px-4 py-3 font-mono text-obsidian-muted tnum">
                            $0 · {m.minutes}m
                          </td>
                          <td className="px-4 py-3 text-right">
                            {m.completed ? (
                              <span className="inline-flex items-center gap-1 font-mono text-obsidian-proof">
                                <CheckCircle2 className="h-3.5 w-3.5" />
                                Completed
                              </span>
                            ) : (
                              <button
                                type="button"
                                disabled={!user || actionBusy}
                                onClick={() => void completeModule(m.code)}
                                className="rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-1.5 font-semibold text-obsidian-ink transition hover:border-obsidian-amber disabled:opacity-40"
                              >
                                Complete
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* TAB 4: ECONOMICS, EARN-TO-LEARN VAULT & DEPIN */}
          {activeTab === 'economics' && (
            <div className="space-y-6">
              <EconomicLab />

              {user && (
                <div className="grid grid-cols-1 gap-6 xl:grid-cols-12">
                  {/* Earn-to-Learn Hardware Vault */}
                  <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-6 space-y-4">
                    <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
                      <h2 className="font-display text-lg font-bold text-obsidian-ink">
                        Earn-to-Learn Hardware Vault
                      </h2>
                      <span className="font-mono text-base font-bold text-obsidian-proof tnum">
                        {formatUsd(vaultState?.vault?.balanceCents ?? 0)}
                      </span>
                    </div>

                    <div>
                      <label htmlFor="vault-split" className="block text-xs text-obsidian-muted">
                        Escrow Routing Split:{' '}
                        <strong className="font-mono text-obsidian-ink">
                          {((vaultState?.vault?.routingBps ?? 1000) / 100).toFixed(0)}%
                        </strong>
                      </label>
                      <div className="mt-2 flex gap-2">
                        {[0, 500, 1000, 1500].map((bps) => (
                          <button
                            key={bps}
                            type="button"
                            disabled={actionBusy}
                            onClick={() => void updateVaultRouting(bps)}
                            className={`rounded-lg px-3 py-1.5 font-mono text-xs ${
                              vaultState?.vault?.routingBps === bps
                                ? 'bg-obsidian-amber text-obsidian-ground font-semibold'
                                : 'border border-obsidian-border bg-obsidian-ground text-obsidian-muted'
                            }`}
                          >
                            {(bps / 100).toFixed(0)}%
                          </button>
                        ))}
                      </div>
                    </div>

                    <div className="space-y-2.5 pt-2">
                      {(catalog?.hardwareCatalog ?? []).map((kit) => (
                        <div
                          key={kit.sku}
                          className="flex items-center justify-between gap-3 rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5 text-xs"
                        >
                          <div>
                            <div className="font-semibold text-obsidian-ink">{kit.name}</div>
                            <div className="text-obsidian-muted">
                              Unlocks {kit.unlocksVertical} · Unlock {formatUsd(kit.escrowUnlockCents)}
                            </div>
                          </div>
                          <button
                            type="button"
                            disabled={actionBusy}
                            onClick={() => void redeemKit(kit.sku)}
                            className="rounded-lg bg-obsidian-elevated px-3 py-1.5 font-semibold text-obsidian-amber border border-obsidian-border hover:border-obsidian-amber"
                          >
                            Redeem
                          </button>
                        </div>
                      ))}
                    </div>
                  </section>

                  {/* DePIN Assets & Fractional Hardware Vault Pools */}
                  <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-6 space-y-5">
                    <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
                      <h2 className="font-display text-lg font-bold text-obsidian-ink">
                        DePIN Spatial Portfolio & Hardware Pools
                      </h2>
                      <Cpu className="h-5 w-5 text-obsidian-amber" />
                    </div>

                    <div className="space-y-2.5">
                      {depinAssets.map((asset) => (
                        <div
                          key={asset.id}
                          className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5 text-xs"
                        >
                          <div>
                            <div className="font-semibold text-obsidian-ink">{asset.address}</div>
                            <div className="font-mono text-obsidian-muted tnum">
                              Age {asset.halfLife.ageDays}d · Query{' '}
                              {formatUsd(asset.halfLife.queryPriceCents)} · Royalties{' '}
                              {formatUsd(asset.totalRoyaltiesCents)}
                            </div>
                          </div>
                          <button
                            type="button"
                            disabled={actionBusy}
                            onClick={() => void licenseAsset(asset.id)}
                            className="rounded-lg bg-obsidian-proof px-3 py-1.5 font-semibold text-obsidian-ground"
                          >
                            License Query
                          </button>
                        </div>
                      ))}
                    </div>

                    <div className="space-y-2.5 border-t border-obsidian-border pt-4">
                      {hardwarePools.map((pool) => (
                        <div
                          key={pool.id}
                          className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5 text-xs"
                        >
                          <div>
                            <div className="font-semibold text-obsidian-ink">{pool.name}</div>
                            <div className="font-mono text-obsidian-muted tnum">
                              Shares: {pool.fundedShares}/{pool.totalShares} (Mine: {pool.myShares})
                            </div>
                          </div>
                          <button
                            type="button"
                            disabled={actionBusy}
                            onClick={() => void investPool(pool.id)}
                            className="rounded-lg border border-obsidian-amber px-3 py-1.5 font-semibold text-obsidian-amber hover:bg-obsidian-amber/15"
                          >
                            +1 Share
                          </button>
                        </div>
                      ))}
                    </div>
                  </section>
                </div>
              )}
            </div>
          )}

          {/* STORMS & SPATIAL INTELLIGENCE MAP */}
          {activeTab === 'storms' && (
            <StormMapConsole
              userAuthenticated={Boolean(user)}
              onDispatchedBounties={() => {
                void loadAuthenticatedData();
                setActiveTab('dispatch');
              }}
            />
          )}

          {/* TAB 5: FIELD FEED & LIVE */}
          {activeTab === 'field' && <FieldLiveConsole />}

          {/* TAB 6: TIER 4 ATTESTOR DESK & ANTI-SPOOFING CONSOLE */}
          {activeTab === 'attestor' && (
            <div className="grid grid-cols-1 gap-6 xl:grid-cols-12">
              <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-7 space-y-4">
                <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
                  <h2 className="font-display text-lg font-bold text-obsidian-ink">
                    Tier 4 Dual-Sign Co-Signing Desk (RecordKind: ATTEST)
                  </h2>
                  <FileCheck2 className="h-5 w-5 text-obsidian-proof" />
                </div>

                <div className="space-y-3">
                  {dossiers.packets.length === 0 ? (
                    <p className="text-xs text-obsidian-muted">
                      No sealed field dossiers yet. Sign in with the demo operator session or seal a field packet first.
                    </p>
                  ) : (
                    dossiers.packets.map((pkt) => (
                      <div
                        key={pkt.id}
                        className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-obsidian-border bg-obsidian-ground p-4 text-xs"
                      >
                        <div className="space-y-1">
                          <div className="font-mono font-bold text-obsidian-ink">
                            {pkt.code} — {pkt.title}
                          </div>
                          <div className="font-mono text-obsidian-muted">
                            Head {String(pkt.headHash || '').slice(0, 18)}...
                          </div>
                        </div>
                        <div className="flex items-center gap-2">
                          <Link
                            to={`/verify/${pkt.id}`}
                            className="rounded-lg border border-obsidian-border px-3 py-1.5 font-medium text-obsidian-muted hover:text-obsidian-ink"
                          >
                            Verify Proof
                          </Link>
                          <button
                            type="button"
                            disabled={actionBusy}
                            onClick={() => void coSignDossier(pkt.id)}
                            className="rounded-lg bg-obsidian-amber px-3.5 py-1.5 font-semibold text-obsidian-ground"
                          >
                            Dual-Sign ATTEST
                          </button>
                        </div>
                      </div>
                    ))
                  )}
                </div>
              </section>

              <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-5 space-y-4">
                <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
                  <h2 className="font-display text-lg font-bold text-obsidian-ink">
                    Anti-Spoofing & Hardware Gate
                  </h2>
                  <Activity className="h-5 w-5 text-obsidian-amber" />
                </div>

                <div className="space-y-3 text-xs">
                  <div>
                    <label htmlFor="att-verdict" className="block text-obsidian-muted">
                      Play Integrity Device Verdict
                    </label>
                    <select
                      id="att-verdict"
                      value={attestVerdict}
                      onChange={(e) => setAttestVerdict(e.target.value)}
                      className="mt-1 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-2 font-mono text-obsidian-ink"
                    >
                      <option value="MEETS_STRONG_INTEGRITY">MEETS_STRONG_INTEGRITY</option>
                      <option value="MEETS_BASIC_INTEGRITY">MEETS_BASIC_INTEGRITY</option>
                      <option value="UNTRUSTED_ROOTED">UNTRUSTED_ROOTED</option>
                    </select>
                  </div>

                  <div>
                    <label htmlFor="att-keybox" className="block text-obsidian-muted">
                      Hardware Keybox Serial (CRL Checked)
                    </label>
                    <select
                      id="att-keybox"
                      value={keyboxSerial}
                      onChange={(e) => setKeyboxSerial(e.target.value)}
                      className="mt-1 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-2 font-mono text-obsidian-ink"
                    >
                      <option value="CLEAN_OEM_KEYBOX_9921">CLEAN_OEM_KEYBOX_9921 (Valid OEM)</option>
                      <option value="LEAKED_OEM_KEYBOX_001">LEAKED_OEM_KEYBOX_001 (TrickyStore CRL)</option>
                    </select>
                  </div>

                  <div>
                    <div className="text-obsidian-muted">Gyroscope / IMU Motion Trajectory</div>
                    <div className="mt-1.5 grid grid-cols-2 gap-2">
                      <button
                        type="button"
                        onClick={() => setImuPreset('organic')}
                        className={`rounded-lg border px-3 py-2 font-mono ${
                          imuPreset === 'organic'
                            ? 'border-obsidian-proof bg-obsidian-proof/15 text-obsidian-proof'
                            : 'border-obsidian-border bg-obsidian-ground text-obsidian-muted'
                        }`}
                      >
                        Organic Motion
                      </button>
                      <button
                        type="button"
                        onClick={() => setImuPreset('flat')}
                        className={`rounded-lg border px-3 py-2 font-mono ${
                          imuPreset === 'flat'
                            ? 'border-obsidian-alert bg-obsidian-alert/15 text-obsidian-alert'
                            : 'border-obsidian-border bg-obsidian-ground text-obsidian-muted'
                        }`}
                      >
                        Flat Emulator Trace
                      </button>
                    </div>
                  </div>

                  <button
                    type="button"
                    disabled={!user || actionBusy}
                    onClick={() => void runHardwareAttest()}
                    className="w-full rounded-lg bg-obsidian-amber py-2.5 font-semibold text-obsidian-ground disabled:opacity-40"
                  >
                    Issue Nonce Challenge & Verify Device
                  </button>

                  {attestOutcome && (
                    <div
                      className={`rounded-lg border p-3.5 font-mono text-xs ${
                        attestOutcome.passed
                          ? 'border-obsidian-proof/50 bg-obsidian-proof/10 text-obsidian-proof'
                          : 'border-obsidian-alert/50 bg-obsidian-alert/10 text-obsidian-alert'
                      }`}
                    >
                      <div className="flex items-center gap-2 font-bold">
                        {attestOutcome.passed ? (
                          <ShieldCheck className="h-4 w-4" />
                        ) : (
                          <ShieldAlert className="h-4 w-4" />
                        )}
                        {attestOutcome.passed ? 'HARDWARE PASSED' : 'ATTESTATION FLAGGED'}
                      </div>
                      <p className="mt-1 text-obsidian-ink/80">
                        {attestOutcome.reason || attestOutcome.badgeState}
                      </p>
                    </div>
                  )}
                </div>
              </section>
            </div>
          )}
        </main>
      </div>

      <ShortestFixModal
        block={shortestFix}
        busy={actionBusy}
        onClose={() => setShortestFix(null)}
        onStartModule={(code) => void completeModule(code)}
      />
    </div>
  );
}
