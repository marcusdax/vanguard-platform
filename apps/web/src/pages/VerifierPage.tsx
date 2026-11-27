import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, FileCheck2, Search, ShieldAlert, ShieldCheck } from 'lucide-react';
import { api } from '@/lib/api';
import { MultiVerticalProofShowcase } from '@/components/MultiVerticalProofShowcase';
import { EcosystemRoiCalculator } from '@/components/EcosystemRoiCalculator';

declare global {
  interface Window {
    VanguardVerifier?: {
      verifyBundle: (bundle: any) => Promise<{
        chainValid: boolean;
        chain: { valid: boolean; entries?: number; headHash?: string; reason?: string };
        merkleValid: boolean;
        verified: boolean;
      }>;
      version: string;
    };
  }
}

export function VerifierPage() {
  const { packetId: routePacketId } = useParams<{ packetId?: string }>();
  const navigate = useNavigate();
  const [inputId, setInputId] = useState(routePacketId || '');
  const [bundle, setBundle] = useState<any | null>(null);
  const [clientResult, setClientResult] = useState<any | null>(null);
  const [scriptLoaded, setScriptLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  useEffect(() => {
    if (window.VanguardVerifier) {
      setScriptLoaded(true);
      return;
    }
    const script = document.createElement('script');
    script.src = '/verifier/vanguard-verify.min.js';
    script.async = true;
    script.onload = () => setScriptLoaded(true);
    document.head.appendChild(script);
  }, []);

  async function inspectPacket(idToFetch: string) {
    const clean = idToFetch.trim();
    if (!clean) return;
    setLoading(true);
    setErrorMsg(null);
    setClientResult(null);
    try {
      const { data } = await api.get(`/public/vanguard/verify/${encodeURIComponent(clean)}`);
      setBundle(data);
      if (window.VanguardVerifier) {
        const res = await window.VanguardVerifier.verifyBundle(data);
        setClientResult(res);
      }
    } catch (err: any) {
      setBundle(null);
      setErrorMsg(err?.response?.data?.error || 'Could not retrieve verification bundle.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    if (routePacketId && scriptLoaded) {
      setInputId(routePacketId);
      void inspectPacket(routePacketId);
    }
  }, [routePacketId, scriptLoaded]);

  function handleSearch(e: React.FormEvent) {
    e.preventDefault();
    if (!inputId.trim()) return;
    navigate(`/verify/${encodeURIComponent(inputId.trim())}`);
    void inspectPacket(inputId);
  }

  return (
    <div className="min-h-screen bg-obsidian-ground text-obsidian-ink">
      <header className="border-b border-obsidian-border bg-obsidian-surface">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-4 px-5 py-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-obsidian-proof/15 text-obsidian-proof">
              <FileCheck2 className="h-5 w-5" />
            </div>
            <div>
              <h1 className="font-display text-lg font-bold text-obsidian-ink">
                Vanguard Independent Evidence Verifier
              </h1>
              <p className="font-mono text-xs text-obsidian-muted">
                Dependency-free client WebCrypto engine (&lt; 5 KB) · SHA-256 chain + RFC 6962
              </p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <Link
              to="/community"
              className="rounded-lg border border-obsidian-border px-3.5 py-2 text-xs font-medium text-obsidian-muted hover:text-obsidian-ink"
            >
              Neighborhood Portal
            </Link>
            <Link
              to="/"
              className="inline-flex items-center gap-1.5 rounded-lg bg-obsidian-elevated px-3.5 py-2 text-xs font-semibold text-obsidian-ink border border-obsidian-border hover:border-obsidian-amber"
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              Tactical Workspace
            </Link>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-5 py-8 space-y-6">
        <form
          onSubmit={handleSearch}
          className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5"
        >
          <label htmlFor="packet-lookup" className="block text-sm font-semibold text-obsidian-ink">
            Inspect Sealed Packet ID or Subject Code
          </label>
          <div className="mt-2.5 flex flex-wrap gap-3">
            <div className="relative flex-1 min-w-[240px]">
              <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-obsidian-muted" />
              <input
                id="packet-lookup"
                type="text"
                value={inputId}
                onChange={(e) => setInputId(e.target.value)}
                placeholder="Enter packet ID from Tactical Attestor Desk or field share link"
                className="w-full rounded-lg border border-obsidian-border bg-obsidian-ground py-2.5 pl-10 pr-4 font-mono text-sm text-obsidian-ink"
              />
            </div>
            <button
              type="submit"
              disabled={loading || !scriptLoaded}
              className="rounded-lg bg-obsidian-amber px-5 py-2.5 text-sm font-semibold text-obsidian-ground hover:brightness-110 disabled:opacity-50"
            >
              {loading ? 'Verifying...' : 'Verify Bundle Client-Side'}
            </button>
          </div>
        </form>

        {errorMsg && (
          <div role="alert" className="flex items-center gap-3 rounded-xl border border-obsidian-alert/40 bg-obsidian-alert/10 p-4 text-sm text-obsidian-ink">
            <ShieldAlert className="h-5 w-5 text-obsidian-alert" />
            <span>{errorMsg}</span>
          </div>
        )}

        {bundle && (
          <div className="space-y-6">
            <section className="grid grid-cols-1 gap-4 sm:grid-cols-3">
              <div className="rounded-xl border border-obsidian-border bg-obsidian-surface p-4">
                <div className="text-xs text-obsidian-muted">Client WebCrypto Chain Check</div>
                <div className="mt-2 flex items-center gap-2 font-mono text-sm font-bold text-obsidian-proof">
                  {clientResult?.chainValid ? (
                    <>
                      <CheckCircle2 className="h-4 w-4" />
                      VALID ({clientResult.chain?.entries} records)
                    </>
                  ) : (
                    <span className="text-obsidian-alert">
                      {clientResult ? `FAILED (${clientResult.chain?.reason})` : 'Checking...'}
                    </span>
                  )}
                </div>
              </div>
              <div className="rounded-xl border border-obsidian-border bg-obsidian-surface p-4">
                <div className="text-xs text-obsidian-muted">RFC 6962 Inclusion Proof</div>
                <div className="mt-2 flex items-center gap-2 font-mono text-sm font-bold text-obsidian-proof">
                  {clientResult?.merkleValid ? (
                    <>
                      <ShieldCheck className="h-4 w-4" />
                      MERKLE ROOT VERIFIED
                    </>
                  ) : (
                    <span className="text-obsidian-amber">No anchored leaf or unverified</span>
                  )}
                </div>
              </div>
              <div className="rounded-xl border border-obsidian-border bg-obsidian-surface p-4">
                <div className="text-xs text-obsidian-muted">Packet Reference</div>
                <div className="mt-2 font-mono text-sm font-semibold text-obsidian-ink truncate">
                  {bundle.packet?.code || bundle.packet?.id}
                </div>
              </div>
            </section>

            <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5">
              <h2 className="font-display text-base font-bold text-obsidian-ink">
                Canonical Record Ledger ({bundle.records?.length ?? 0} entries)
              </h2>
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left font-mono text-xs">
                  <thead className="border-b border-obsidian-border text-obsidian-muted">
                    <tr>
                      <th className="py-2 pr-4">Seq</th>
                      <th className="py-2 pr-4">Kind</th>
                      <th className="py-2 pr-4">Summary</th>
                      <th className="py-2">Record SHA-256</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-obsidian-border/60">
                    {(bundle.records ?? []).map((rec: any) => (
                      <tr key={rec.seq}>
                        <td className="py-2.5 pr-4 tnum text-obsidian-amber">{rec.seq}</td>
                        <td className="py-2.5 pr-4 text-obsidian-proof">{rec.kind}</td>
                        <td className="py-2.5 pr-4 font-sans text-obsidian-ink">{rec.summary}</td>
                        <td className="py-2.5 text-obsidian-muted">{String(rec.hash || '').slice(0, 20)}...</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </div>
        )}

        <MultiVerticalProofShowcase />
        <EcosystemRoiCalculator />
      </main>
    </div>
  );
}
