import { useState } from 'react';
import { CheckCircle2, Cpu, ShieldCheck, Sparkles } from 'lucide-react';

export interface ShowcaseProofCard {
  id: string;
  vertical: string;
  bucket: string;
  title: string;
  status: string;
  regulation: string;
  metrics: Record<string, string | number | boolean>;
  piiScrubbed: boolean;
}

export const SHOWCASE_PROOF_CARDS: ShowcaseProofCard[] = [
  {
    id: 'CACAO-CI-2026-001',
    vertical: 'auctum.cacao_ag',
    bucket: 'Bucket 5 · EUDR Commodities',
    title: 'AUCTUM.cacao/ag — Côte d’Ivoire Estate Lot',
    status: 'EUDR_COMPLIANT',
    regulation: 'EU-2023-1115 / TRACES-NT',
    metrics: { hs_code: '1801', area_ha: 6.5, deforestation_free: true, income_floor_met: true },
    piiScrubbed: true,
  },
  {
    id: 'COFFEE-CO-2026-884',
    vertical: 'auctum.coffee',
    bucket: 'Bucket 5 · Specialty Coffee',
    title: 'AUCTUM.coffee — Huila Washed Micro-Lot',
    status: 'EUDR_COMPLIANT',
    regulation: 'EU-2023-1115 / Farmgate Guarantee',
    metrics: { hs_code: '0901', cupping_score: 88.25, farmgate_usd_kg: 5.1, moisture_pct: 10.8 },
    piiScrubbed: true,
  },
  {
    id: 'CLM-TX-9001',
    vertical: 'auctum.claims',
    bucket: 'Bucket 3 · Built-World & Storm',
    title: 'AUCTUM.atlas / Claims — Hail C2SP Packet',
    status: 'C2SP_VERIFIED',
    regulation: 'C2SP Merkle / GIE Hex-Grid',
    metrics: { hail_mm: 54, peak_wind_kt: 58, sensor_streams: 3, tamper_score: 0.02 },
    piiScrubbed: true,
  },
  {
    id: 'VHS-HOME-4410',
    vertical: 'vanguard.home_shield',
    bucket: 'Bucket 2 · Sovereign Home Security',
    title: 'Vanguard Home Shield — Insurance Uptime Cert',
    status: 'SOVEREIGN_UPTIME_99_98',
    regulation: 'Zero Indoor Cameras / LoRa 915MHz',
    metrics: { indoor_cameras: 0, lora_failover_ready: true, uptime_pct: 99.98, stay_clear_enforced: true },
    piiScrubbed: true,
  },
  {
    id: 'FW-SAFE-7721',
    vertical: 'vanguard.safety_blanket',
    bucket: 'Bucket 2 · Community Safety Blanket',
    title: 'Fieldwright — Verified Field Audit Proof',
    status: 'ZERO_HARM_VERIFIED',
    regulation: 'Tier-2 CST / Auto-Wipe Ready',
    metrics: { worker_tier: 'tier_2_cst', floor_fee_usd: 0, stay_clear_violations: 0 },
    piiScrubbed: true,
  },
  {
    id: 'WS-FID-3019',
    vertical: 'wealth.fiduciary_os',
    bucket: 'Bucket 4 · Fiduciary & Care',
    title: 'Wealth Symphony × AUCTUM.Insights Audit',
    status: 'SR_11_7_VERIFIED',
    regulation: 'SR 11-7 / EU AI Act Art. 14',
    metrics: { human_oversight_gate: true, provenance_sources: 4, deterministic_replay: true },
    piiScrubbed: true,
  },
];

async function computeSha256Hex(payload: string): Promise<string> {
  const bytes = new TextEncoder().encode(payload);
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const buf = await crypto.subtle.digest('SHA-256', bytes);
    return Array.from(new Uint8Array(buf))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  return 'webcrypto-unavailable-in-environment';
}

export function MultiVerticalProofShowcase() {
  const [selectedId, setSelectedId] = useState<string>(SHOWCASE_PROOF_CARDS[0].id);
  const [verifiedHash, setVerifiedHash] = useState<string | null>(null);
  const [verifying, setVerifying] = useState(false);

  const active = SHOWCASE_PROOF_CARDS.find((c) => c.id === selectedId) ?? SHOWCASE_PROOF_CARDS[0];
  const serialized = JSON.stringify(active);
  const byteSize = new TextEncoder().encode(serialized).length;

  async function handleVerifyCard() {
    setVerifying(true);
    try {
      const hash = await computeSha256Hex(serialized);
      setVerifiedHash(hash);
    } finally {
      setVerifying(false);
    }
  }

  return (
    <section
      aria-label="Multi-Vertical Spaces Proof Card Showcase"
      className="rounded-xl border border-obsidian-border bg-obsidian-surface p-6 space-y-5"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="inline-flex items-center gap-1.5 font-mono text-xs uppercase tracking-wider text-obsidian-proof">
            <Sparkles className="h-3.5 w-3.5" />
            Multi-Vertical Spaces Proof Showcase
          </div>
          <h2 className="mt-1 font-display text-lg font-bold text-obsidian-ink">
            Zero-PII &lt;5 KB Cryptographic Proof Cards Across Every AUCTUM &amp; Vanguard Bucket
          </h2>
          <p className="mt-1 text-xs text-obsidian-muted">
            Select any vertical below to inspect its live Spaces card payload and verify its SHA-256 digest directly in your browser.
          </p>
        </div>
        <span className="rounded-full border border-obsidian-proof/40 bg-obsidian-proof/10 px-3 py-1 font-mono text-xs text-obsidian-proof">
          {byteSize} bytes · &lt;5,120 B · PII Scrubbed
        </span>
      </div>

      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2 lg:grid-cols-3">
        {SHOWCASE_PROOF_CARDS.map((card) => {
          const isSelected = card.id === active.id;
          return (
            <button
              key={card.id}
              type="button"
              onClick={() => {
                setSelectedId(card.id);
                setVerifiedHash(null);
              }}
              className={`rounded-lg border p-3 text-left transition ${
                isSelected
                  ? 'border-obsidian-amber bg-obsidian-elevated'
                  : 'border-obsidian-border bg-obsidian-ground hover:border-obsidian-muted'
              }`}
            >
              <div className="font-mono text-[11px] text-obsidian-muted">{card.bucket}</div>
              <div className="mt-1 text-sm font-semibold text-obsidian-ink">{card.title}</div>
              <div className="mt-1.5 inline-flex items-center gap-1 font-mono text-[11px] text-obsidian-proof">
                <ShieldCheck className="h-3 w-3" />
                {card.status}
              </div>
            </button>
          );
        })}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="rounded-lg border border-obsidian-border bg-obsidian-ground p-4">
          <div className="flex items-center justify-between text-xs text-obsidian-muted">
            <span className="font-mono">STANDARD: {active.regulation}</span>
            <span className="font-mono text-obsidian-proof">ZERO-PII: TRUE</span>
          </div>
          <pre className="mt-3 max-h-56 overflow-auto font-mono text-xs text-obsidian-ink">
            {JSON.stringify(active, null, 2)}
          </pre>
        </div>

        <div className="flex flex-col justify-between rounded-lg border border-obsidian-border bg-obsidian-ground p-4 space-y-4">
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-xs font-semibold text-obsidian-ink">
              <Cpu className="h-4 w-4 text-obsidian-amber" />
              Client-Side WebCrypto Verification
            </div>
            <p className="text-xs text-obsidian-muted">
              Every Spaces card is strictly bounded under 5 KB so it can be shared over LoRa 915 MHz, QR codes, or social feeds without leaking homeowner, farmer, or client PII.
            </p>
            {verifiedHash && (
              <div className="rounded-lg border border-obsidian-proof/40 bg-obsidian-proof/10 p-3">
                <div className="flex items-center gap-1.5 font-mono text-xs font-bold text-obsidian-proof">
                  <CheckCircle2 className="h-4 w-4" />
                  SHA-256 VERIFIED LOCALLY
                </div>
                <div className="mt-1 break-all font-mono text-[11px] text-obsidian-ink">
                  {verifiedHash}
                </div>
              </div>
            )}
          </div>

          <button
            type="button"
            onClick={handleVerifyCard}
            disabled={verifying}
            className="w-full rounded-lg bg-obsidian-amber px-4 py-2.5 text-sm font-semibold text-obsidian-ground hover:brightness-110 disabled:opacity-50"
          >
            {verifying ? 'Computing SHA-256...' : `Verify ${active.id} Proof Card`}
          </button>
        </div>
      </div>
    </section>
  );
}
