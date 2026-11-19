import { useState } from 'react';
import { Briefcase, Calculator, Shield, TrendingUp } from 'lucide-react';

export interface CareerTrack {
  id: string;
  title: string;
  tierLabel: string;
  hourlyBenchmarkUsd: number;
  description: string;
}

export const SOVEREIGN_CAREER_TRACKS: CareerTrack[] = [
  {
    id: 'community_sentinel',
    title: 'Community Sentinel',
    tierLabel: 'Tier 1 · Entry DePIN & Safety',
    hourlyBenchmarkUsd: 26,
    description: 'Non-confrontational neighborhood telemetry, Blanket Search Party support & Zero-Harm STAY_CLEAR compliance.',
  },
  {
    id: 'certified_sovereign_tech',
    title: 'Certified Sovereign Technician (CST)',
    tierLabel: 'Tier 2 · Hardware & Home Shield',
    hourlyBenchmarkUsd: 42,
    description: 'Installs & audits cloud-independent Vanguard Home Shield nodes, LoRa gateways & local RT-DETR v2 exterior sensors.',
  },
  {
    id: 'licensed_cso',
    title: 'State-Licensed Community Security Officer',
    tierLabel: 'Tier 3 · Licensed Response',
    hourlyBenchmarkUsd: 55,
    description: 'State-licensed patrol & verified law-enforcement welfare-sitrep liaison with cryptographic chain-of-custody.',
  },
  {
    id: 'field_evidence_auditor',
    title: 'Verified Field Evidence Auditor',
    tierLabel: 'Fieldwright · AUCTUM.atlas / Claims / EUDR',
    hourlyBenchmarkUsd: 54.97,
    description: 'Captures storm C2SP roof/property packets, EUDR farm polygons, or municipal inspections with $0 worker floor fees.',
  },
  {
    id: 'care_wealth_navigator',
    title: 'Sovereign Care & Wealth Navigator',
    tierLabel: 'Bucket 1 & 4 · Dignity + Fiduciary',
    hourlyBenchmarkUsd: 62,
    description: 'Supports families onboarding LuminAAC, Family Shield Elder Care, and Wealth Symphony SR 11-7 planning workspaces.',
  },
];

export const DEPIN_ROYALTY_PER_MILE_USD = 0.76;

export function computeEcosystemProjection(
  trackId: string,
  hoursPerWeek: number,
  depinMilesPerWeek: number
) {
  const track =
    SOVEREIGN_CAREER_TRACKS.find((t) => t.id === trackId) ?? SOVEREIGN_CAREER_TRACKS[3];
  const activeWeeklyUsd = hoursPerWeek * track.hourlyBenchmarkUsd;
  const depinWeeklyUsd = depinMilesPerWeek * DEPIN_ROYALTY_PER_MILE_USD;
  const totalWeeklyUsd = activeWeeklyUsd + depinWeeklyUsd;
  const monthlyUsd = totalWeeklyUsd * 4.33;
  const annualUsd = totalWeeklyUsd * 52;
  const depin120DayRoyaltyUsd = depinMilesPerWeek * (120 / 7) * DEPIN_ROYALTY_PER_MILE_USD;

  return {
    track,
    activeWeeklyUsd,
    depinWeeklyUsd,
    totalWeeklyUsd,
    monthlyUsd,
    annualUsd,
    depin120DayRoyaltyUsd,
  };
}

export function EcosystemRoiCalculator() {
  const [trackId, setTrackId] = useState<string>('field_evidence_auditor');
  const [hoursPerWeek, setHoursPerWeek] = useState<number>(20);
  const [depinMilesPerWeek, setDepinMilesPerWeek] = useState<number>(120);

  const proj = computeEcosystemProjection(trackId, hoursPerWeek, depinMilesPerWeek);

  return (
    <section
      aria-label="Interactive Ecosystem ROI and Sovereign Career Calculator"
      className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 space-y-5"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="inline-flex items-center gap-1.5 font-mono text-xs uppercase tracking-wider text-obsidian-amber">
            <Calculator className="h-3.5 w-3.5" />
            Interactive Ecosystem ROI &amp; Sovereign Career Calculator
          </div>
          <h2 className="mt-1 font-display text-lg font-bold text-obsidian-ink">
            Model Your DePIN Passive Telemetry Royalties &amp; Sovereign Career Track
          </h2>
          <p className="mt-1 text-xs text-obsidian-muted">
            USD-settled 120-day DePIN spatial-telemetry royalties (${DEPIN_ROYALTY_PER_MILE_USD.toFixed(2)}/mi benchmark) with zero worker floor fees.
          </p>
        </div>
        <span className="inline-flex items-center gap-1.5 rounded-full border border-obsidian-border bg-obsidian-ground px-3 py-1 font-mono text-xs text-obsidian-proof">
          <Shield className="h-3.5 w-3.5" />
          $0 Worker Floor Fees
        </span>
      </div>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <div className="space-y-4 rounded-lg border border-obsidian-border bg-obsidian-ground p-4">
          <div>
            <label htmlFor="career-track" className="block text-xs font-semibold text-obsidian-ink">
              Select Sovereign Career Path
            </label>
            <select
              id="career-track"
              value={trackId}
              onChange={(e) => setTrackId(e.target.value)}
              className="mt-1.5 w-full rounded-lg border border-obsidian-border bg-obsidian-surface px-3 py-2 text-sm text-obsidian-ink"
            >
              {SOVEREIGN_CAREER_TRACKS.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.title} — ${t.hourlyBenchmarkUsd.toFixed(2)}/hr ({t.tierLabel})
                </option>
              ))}
            </select>
            <p className="mt-1.5 text-xs text-obsidian-muted">{proj.track.description}</p>
          </div>

          <div>
            <div className="flex justify-between text-xs">
              <label htmlFor="hours-slider" className="font-semibold text-obsidian-ink">
                Active Career Hours / Week
              </label>
              <span className="font-mono text-obsidian-amber">{hoursPerWeek} hrs/wk</span>
            </div>
            <input
              id="hours-slider"
              type="range"
              min={0}
              max={45}
              step={1}
              value={hoursPerWeek}
              onChange={(e) => setHoursPerWeek(Number(e.target.value))}
              className="mt-1.5 w-full accent-amber-400"
            />
          </div>

          <div>
            <div className="flex justify-between text-xs">
              <label htmlFor="miles-slider" className="font-semibold text-obsidian-ink">
                DePIN Verified Spatial-Telemetry Miles / Week
              </label>
              <span className="font-mono text-obsidian-proof">{depinMilesPerWeek} mi/wk</span>
            </div>
            <input
              id="miles-slider"
              type="range"
              min={0}
              max={500}
              step={10}
              value={depinMilesPerWeek}
              onChange={(e) => setDepinMilesPerWeek(Number(e.target.value))}
              className="mt-1.5 w-full accent-emerald-400"
            />
          </div>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-4">
            <div className="flex items-center gap-1.5 text-xs text-obsidian-muted">
              <Briefcase className="h-3.5 w-3.5 text-obsidian-amber" />
              Projected Monthly Total
            </div>
            <div className="mt-2 font-mono text-2xl font-bold text-obsidian-ink">
              ${Math.round(proj.monthlyUsd).toLocaleString()}
            </div>
            <div className="mt-1 font-mono text-[11px] text-obsidian-muted">
              ${Math.round(proj.annualUsd).toLocaleString()} / yr annualized
            </div>
          </div>

          <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-4">
            <div className="flex items-center gap-1.5 text-xs text-obsidian-muted">
              <TrendingUp className="h-3.5 w-3.5 text-obsidian-proof" />
              120-Day DePIN Royalty Pool
            </div>
            <div className="mt-2 font-mono text-2xl font-bold text-obsidian-proof">
              ${Math.round(proj.depin120DayRoyaltyUsd).toLocaleString()}
            </div>
            <div className="mt-1 font-mono text-[11px] text-obsidian-muted">
              ${proj.depinWeeklyUsd.toFixed(2)} / wk passive USD
            </div>
          </div>

          <div className="col-span-2 rounded-lg border border-obsidian-border bg-obsidian-ground p-4 text-xs text-obsidian-muted">
            <strong className="text-obsidian-ink">Transparent Benchmark Notice:</strong> Figures are illustrative economic modeling benchmarks based on Vanguard&apos;s $0.76/mi DePIN spatial-telemetry rate and $54.97/hr verified loop benchmark with zero platform floor fees. Actual earnings depend on local market demand, certification tier, and verified completion quality.
          </div>
        </div>
      </div>
    </section>
  );
}
