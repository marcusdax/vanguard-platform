import { useMemo, useState } from 'react';
import { Calculator, Cpu, Fingerprint, ShieldCheck } from 'lucide-react';
import { formatUsd } from '@/lib/api';

async function sha256HexBrowser(input: string): Promise<string> {
  const bytes = new TextEncoder().encode(input);
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

export function EconomicLab() {
  const [irsMode, setIrsMode] = useState<'h1' | 'h2'>('h2');
  const [taskCount, setTaskCount] = useState(4);
  const [avgBountyUsd, setAvgBountyUsd] = useState(25);
  const [routeMiles, setRouteMiles] = useState(5.0);
  const [ageDays, setAgeDays] = useState(60);

  const [sandboxSummary, setSandboxSummary] = useState('4-slope hail capture verified on Prairie Timber Rd');
  const [sandboxHash, setSandboxHash] = useState<string>('');

  const irsRateCents = irsMode === 'h2' ? 76.0 : 72.5;

  const clusterCalc = useMemo(() => {
    const grossCents = Math.round(taskCount * avgBountyUsd * 100);
    const vehicleCostCents = Math.round(routeMiles * irsRateCents);
    const netCents = grossCents - vehicleCostCents;
    const totalMinutes = taskCount * 22.5 + routeMiles * 3;
    const netHourlyCents = totalMinutes > 0 ? Math.round((netCents * 60) / totalMinutes) : 0;
    return { grossCents, vehicleCostCents, netCents, netHourlyCents, totalMinutes: Math.round(totalMinutes) };
  }, [taskCount, avgBountyUsd, routeMiles, irsRateCents]);

  const halfLife = useMemo(() => {
    const decayFactor = Math.pow(0.5, ageDays / 120);
    const queryPriceCents = Math.max(250, Math.round(1600 * decayFactor));
    const workerRoyaltyCents = Math.round(queryPriceCents * 0.25);
    const homeownerRoyaltyCents = Math.round(queryPriceCents * 0.1);
    const treasuryBurnCents = queryPriceCents - workerRoyaltyCents - homeownerRoyaltyCents;
    const stale = ageDays >= 90;
    const refreshMultiplier =
      ageDays < 90
        ? 1.0
        : Number((1.0 + ((Math.min(ageDays, 365) - 90) / 275) * 0.85).toFixed(2));
    return {
      queryPriceCents,
      workerRoyaltyCents,
      homeownerRoyaltyCents,
      treasuryBurnCents,
      stale,
      refreshMultiplier,
    };
  }, [ageDays]);

  async function computeLiveHash() {
    const canonical = JSON.stringify({
      actorId: 'op-1',
      at: '2026-01-15T12:00:00.000Z',
      kind: 'CAPTURE',
      payload: { captureStandardMet: 'true', mediaCount: '4' },
      prevHash: '0'.repeat(64),
      seq: 1,
      summary: sandboxSummary.normalize('NFC'),
    });
    const hash = await sha256HexBrowser(canonical);
    setSandboxHash(hash);
  }

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-12">
        {/* Left: Micro-zone vehicle economics simulator */}
        <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-7">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-obsidian-border pb-4">
            <div className="flex items-center gap-2.5">
              <Calculator className="h-5 w-5 text-obsidian-amber" />
              <h2 className="font-display text-lg font-bold text-obsidian-ink">
                H3 Micro-Zone Unit Economics Simulator
              </h2>
            </div>
            <div className="inline-flex rounded-lg border border-obsidian-border bg-obsidian-ground p-0.5 text-xs">
              <button
                type="button"
                onClick={() => setIrsMode('h1')}
                className={`rounded-md px-3 py-1 font-mono transition ${
                  irsMode === 'h1'
                    ? 'bg-obsidian-amber text-obsidian-ground font-semibold'
                    : 'text-obsidian-muted hover:text-obsidian-ink'
                }`}
              >
                IRS H1 2026 ($0.725/mi)
              </button>
              <button
                type="button"
                onClick={() => setIrsMode('h2')}
                className={`rounded-md px-3 py-1 font-mono transition ${
                  irsMode === 'h2'
                    ? 'bg-obsidian-amber text-obsidian-ground font-semibold'
                    : 'text-obsidian-muted hover:text-obsidian-ink'
                }`}
              >
                IRS H2 2026 ($0.760/mi)
              </button>
            </div>
          </div>

          <div className="mt-5 grid grid-cols-1 gap-5 sm:grid-cols-3">
            <div>
              <label htmlFor="eco-tasks" className="block text-xs font-medium text-obsidian-muted">
                Stacked Tasks in H3 Cell ({taskCount})
              </label>
              <input
                id="eco-tasks"
                type="range"
                min={1}
                max={8}
                step={1}
                value={taskCount}
                onChange={(e) => setTaskCount(Number(e.target.value))}
                className="mt-2 w-full accent-obsidian-amber"
              />
            </div>
            <div>
              <label htmlFor="eco-bounty" className="block text-xs font-medium text-obsidian-muted">
                Mean Task Bounty (${avgBountyUsd})
              </label>
              <input
                id="eco-bounty"
                type="range"
                min={15}
                max={125}
                step={5}
                value={avgBountyUsd}
                onChange={(e) => setAvgBountyUsd(Number(e.target.value))}
                className="mt-2 w-full accent-obsidian-amber"
              />
            </div>
            <div>
              <label htmlFor="eco-miles" className="block text-xs font-medium text-obsidian-muted">
                Loop Driving Miles ({routeMiles.toFixed(1)} mi)
              </label>
              <input
                id="eco-miles"
                type="range"
                min={1}
                max={35}
                step={0.5}
                value={routeMiles}
                onChange={(e) => setRouteMiles(Number(e.target.value))}
                className="mt-2 w-full accent-obsidian-amber"
              />
            </div>
          </div>

          <div className="mt-6 grid grid-cols-2 gap-4 sm:grid-cols-4">
            <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5">
              <div className="text-xs text-obsidian-muted">Gross Loop Payout</div>
              <div className="mt-1 font-mono text-lg font-bold text-obsidian-ink tnum">
                {formatUsd(clusterCalc.grossCents)}
              </div>
            </div>
            <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5">
              <div className="text-xs text-obsidian-muted">IRS Vehicle Wear</div>
              <div className="mt-1 font-mono text-lg font-bold text-obsidian-alert tnum">
                -{formatUsd(clusterCalc.vehicleCostCents)}
              </div>
            </div>
            <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5">
              <div className="text-xs text-obsidian-muted">Operator Net Take</div>
              <div className="mt-1 font-mono text-lg font-bold text-obsidian-proof tnum">
                {formatUsd(clusterCalc.netCents)}
              </div>
            </div>
            <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-3.5">
              <div className="text-xs text-obsidian-muted">Effective Net / Hr</div>
              <div className="mt-1 font-mono text-lg font-bold text-obsidian-amber tnum">
                {formatUsd(clusterCalc.netHourlyCents)}/hr
              </div>
            </div>
          </div>
          <p className="mt-3 text-xs text-obsidian-muted">
            Zero bps deducted from operator floor payouts (Invariant I-1 / Check C-12). Estimated loop window:{' '}
            <span className="font-mono text-obsidian-ink tnum">{clusterCalc.totalMinutes} min</span>.
          </p>
        </section>

        {/* Right: 120-day DePIN Half-Life Model */}
        <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 xl:col-span-5">
          <div className="flex items-center justify-between gap-2 border-b border-obsidian-border pb-4">
            <div className="flex items-center gap-2.5">
              <Cpu className="h-5 w-5 text-obsidian-proof" />
              <h2 className="font-display text-lg font-bold text-obsidian-ink">
                120-Day Spatial Half-Life
              </h2>
            </div>
            <span
              className={`rounded-md px-2 py-0.5 font-mono text-xs font-semibold ${
                halfLife.stale
                  ? 'bg-obsidian-amber/15 text-obsidian-amber'
                  : 'bg-obsidian-proof/15 text-obsidian-proof'
              }`}
            >
              {halfLife.stale ? 'STALE_REFRESH_TRIGGERED' : 'FRESH_WINDOW'}
            </span>
          </div>

          <div className="mt-5">
            <label htmlFor="hl-age" className="flex items-center justify-between text-xs text-obsidian-muted">
              <span>Parcel Capture Age</span>
              <span className="font-mono font-semibold text-obsidian-ink tnum">{ageDays} days</span>
            </label>
            <input
              id="hl-age"
              type="range"
              min={0}
              max={365}
              step={5}
              value={ageDays}
              onChange={(e) => setAgeDays(Number(e.target.value))}
              className="mt-2 w-full accent-obsidian-proof"
            />
          </div>

          <div className="mt-5 space-y-2.5 text-sm">
            <div className="flex items-center justify-between rounded-lg bg-obsidian-ground px-3.5 py-2.5">
              <span className="text-obsidian-muted">Carrier Query License Price</span>
              <span className="font-mono font-bold text-obsidian-ink tnum">
                {formatUsd(halfLife.queryPriceCents)}
              </span>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-obsidian-ground px-3.5 py-2.5">
              <span className="text-obsidian-muted">Capturer Royalty (25%)</span>
              <span className="font-mono font-semibold text-obsidian-proof tnum">
                {formatUsd(halfLife.workerRoyaltyCents)}
              </span>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-obsidian-ground px-3.5 py-2.5">
              <span className="text-obsidian-muted">Opt-In Parcel Owner Share (10%)</span>
              <span className="font-mono font-semibold text-obsidian-amber tnum">
                {formatUsd(halfLife.homeownerRoyaltyCents)}
              </span>
            </div>
            <div className="flex items-center justify-between rounded-lg bg-obsidian-ground px-3.5 py-2.5">
              <span className="text-obsidian-muted">
                Protocol Treasury / Burn (65% · Refresh {halfLife.refreshMultiplier.toFixed(2)}x)
              </span>
              <span className="font-mono text-obsidian-muted tnum">
                {formatUsd(halfLife.treasuryBurnCents)}
              </span>
            </div>
          </div>
        </section>
      </div>

      {/* Bottom full-width WebCrypto Canonical Hash Sandbox */}
      <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <Fingerprint className="h-5 w-5 text-obsidian-amber" />
            <h2 className="font-display text-base font-bold text-obsidian-ink">
              Browser WebCrypto Canonical Preimage Sandbox
            </h2>
          </div>
          <button
            type="button"
            onClick={computeLiveHash}
            className="inline-flex items-center gap-2 rounded-lg bg-obsidian-elevated px-3.5 py-2 text-xs font-semibold text-obsidian-ink border border-obsidian-border transition hover:border-obsidian-amber"
          >
            <ShieldCheck className="h-4 w-4 text-obsidian-proof" />
            Compute Deterministic SHA-256
          </button>
        </div>
        <div className="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
          <div>
            <label htmlFor="sandbox-summary" className="block text-xs text-obsidian-muted">
              Record Summary (UTF-8 NFC normalized before digest)
            </label>
            <input
              id="sandbox-summary"
              type="text"
              value={sandboxSummary}
              onChange={(e) => setSandboxSummary(e.target.value)}
              className="mt-1.5 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3.5 py-2 text-sm text-obsidian-ink"
            />
          </div>
          <div>
            <div className="text-xs text-obsidian-muted">Output Digest (Matches Kotlin core:crypto & Node engine)</div>
            <div className="mt-1.5 min-h-[38px] break-all rounded-lg border border-obsidian-border bg-obsidian-ground px-3.5 py-2 font-mono text-xs text-obsidian-proof">
              {sandboxHash || 'Press "Compute Deterministic SHA-256" to hash in-browser via crypto.subtle'}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
