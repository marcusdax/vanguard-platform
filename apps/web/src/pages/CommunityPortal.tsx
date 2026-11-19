import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, Home, ShieldCheck, ShoppingBag, Trees } from 'lucide-react';
import { BRAND_CONFIG } from '@/brand/brandConfig';
import { api, formatUsd } from '@/lib/api';
import { useAuthStore } from '@/stores/authStore';

type BookingKind = 'GROCERY_CARRY_IN' | 'LAWN_MOW_QUARTER_ACRE';

interface CreatedBooking {
  task: {
    id: string;
    title: string;
    address: string;
    pin: string;
    grossBountyCents: number;
  };
  quote: {
    workerReceivesCents: number;
    feeCents: number;
    totalBuyerChargeCents: number;
    disclosureCopy: string;
  };
  pinHint?: string;
}

export function CommunityPortal() {
  const { user, demoLogin, busy: authBusy } = useAuthStore();
  const [selectedKind, setSelectedKind] = useState<BookingKind>('GROCERY_CARRY_IN');
  const [requesterName, setRequesterName] = useState('');
  const [address, setAddress] = useState('');
  const [familyProxyName, setFamilyProxyName] = useState('');
  const [consentOptIn, setConsentOptIn] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState<CreatedBooking | null>(null);

  const selectedRateCents = selectedKind === 'LAWN_MOW_QUARTER_ACRE' ? 4200 : 1800;
  const escrowFeeCents = Math.round(selectedRateCents * 0.05);
  const totalChargeCents = selectedRateCents + escrowFeeCents;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setErrorMsg(null);
    if (!user) {
      const ok = await demoLogin();
      if (!ok) {
        setErrorMsg('Please sign in first or retry connection.');
        return;
      }
    }
    if (!requesterName.trim() || !address.trim()) {
      setErrorMsg('Please enter both your name and street address.');
      return;
    }

    setSubmitting(true);
    try {
      const { data } = await api.post('/vanguard/community/bookings', {
        kind: selectedKind,
        requesterName: requesterName.trim(),
        address: address.trim(),
        familyProxyName: familyProxyName.trim() || undefined,
        consentOptInTelemetry: consentOptIn,
      });
      setConfirmed(data);
    } catch (err: any) {
      setErrorMsg(err?.response?.data?.error || 'Could not complete booking.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="surface-community min-h-screen bg-linen-ground text-linen-ink">
      <header className="border-b border-linen-border bg-linen-surface">
        <div className="mx-auto flex max-w-4xl flex-wrap items-center justify-between gap-4 px-5 py-5">
          <div className="flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-linen-emerald text-white">
              <Home className="h-6 w-6" />
            </div>
            <div>
              <h1 className="font-display text-2xl font-bold tracking-tight text-linen-ink">
                {BRAND_CONFIG.surfaces.community.name}
              </h1>
              <p className="text-sm text-linen-muted">{BRAND_CONFIG.surfaces.community.tagline}</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <Link
              to="/verify"
              className="rounded-xl border-2 border-linen-border px-4 py-2.5 text-sm font-semibold text-linen-ink transition hover:border-linen-emerald"
            >
              Verify a Receipt
            </Link>
            <Link
              to="/"
              className="inline-flex items-center gap-2 rounded-xl bg-linen-ink px-4 py-2.5 text-sm font-semibold text-white transition hover:opacity-90"
            >
              <ArrowLeft className="h-4 w-4" />
              Operator Mode
            </Link>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-4xl px-5 py-8">
        {!user && (
          <div className="mb-6 flex flex-wrap items-center justify-between gap-4 rounded-2xl border-2 border-linen-emerald/40 bg-linen-surface p-5">
            <p className="text-base font-medium text-linen-ink">
              Want to book right away with the demo neighborhood session?
            </p>
            <button
              type="button"
              disabled={authBusy}
              onClick={() => void demoLogin()}
              className="silver-target rounded-xl bg-linen-emerald px-6 py-3 text-base font-bold text-white transition hover:brightness-110"
            >
              {authBusy ? 'Signing In...' : 'Enable One-Tap Demo Session'}
            </button>
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-8 rounded-2xl border-2 border-linen-border bg-linen-surface p-6 sm:p-8 shadow-sm">
          <fieldset>
            <legend className="font-display text-xl font-bold text-linen-ink">
              1. Choose the help you need today
            </legend>
            <p className="mt-1 text-base text-linen-muted">
              Flat prices are shown up front. Your local helper keeps 100% of the service rate.
            </p>

            <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
              <button
                type="button"
                onClick={() => setSelectedKind('GROCERY_CARRY_IN')}
                className={`silver-target flex items-start gap-4 rounded-2xl border-2 p-5 text-left transition ${
                  selectedKind === 'GROCERY_CARRY_IN'
                    ? 'border-linen-emerald bg-linen-emerald/10'
                    : 'border-linen-border bg-linen-ground/50 hover:border-linen-emerald/50'
                }`}
              >
                <ShoppingBag className="mt-0.5 h-7 w-7 shrink-0 text-linen-emerald" />
                <div>
                  <div className="text-lg font-bold text-linen-ink">Grocery Carry-In & Check</div>
                  <div className="mt-1 text-base font-semibold text-linen-emerald tnum">
                    $18.00 flat rate (+$0.90 fee)
                  </div>
                  <p className="mt-1.5 text-sm text-linen-muted">
                    Bring bags inside to your kitchen counter and confirm arrival with your 4-digit PIN.
                  </p>
                </div>
              </button>

              <button
                type="button"
                onClick={() => setSelectedKind('LAWN_MOW_QUARTER_ACRE')}
                className={`silver-target flex items-start gap-4 rounded-2xl border-2 p-5 text-left transition ${
                  selectedKind === 'LAWN_MOW_QUARTER_ACRE'
                    ? 'border-linen-emerald bg-linen-emerald/10'
                    : 'border-linen-border bg-linen-ground/50 hover:border-linen-emerald/50'
                }`}
              >
                <Trees className="mt-0.5 h-7 w-7 shrink-0 text-linen-emerald" />
                <div>
                  <div className="text-lg font-bold text-linen-ink">Quarter-Acre Lawn Care</div>
                  <div className="mt-1 text-base font-semibold text-linen-emerald tnum">
                    $42.00 flat rate (+$2.10 fee)
                  </div>
                  <p className="mt-1.5 text-sm text-linen-muted">
                    Front and back mow, sidewalk edge, blow clean-up, and photo completion receipt.
                  </p>
                </div>
              </button>
            </div>
          </fieldset>

          <div className="grid grid-cols-1 gap-6 sm:grid-cols-2">
            <div>
              <label htmlFor="comm-name" className="block text-base font-bold text-linen-ink">
                Your Full Name
              </label>
              <p id="comm-name-hint" className="mt-0.5 text-sm text-linen-muted">
                Name shown to your verified local helper at arrival.
              </p>
              <input
                id="comm-name"
                type="text"
                required
                aria-describedby="comm-name-hint"
                value={requesterName}
                onChange={(e) => setRequesterName(e.target.value)}
                placeholder="e.g. Mrs. Margaret Miller"
                className="silver-target mt-2 w-full rounded-xl border-2 border-linen-border bg-white px-4 text-lg text-linen-ink"
              />
            </div>

            <div>
              <label htmlFor="comm-proxy" className="block text-base font-bold text-linen-ink">
                Family Member or Caregiver (Optional)
              </label>
              <p id="comm-proxy-hint" className="mt-0.5 text-sm text-linen-muted">
                Receives a digital receipt copy once complete.
              </p>
              <input
                id="comm-proxy"
                type="text"
                aria-describedby="comm-proxy-hint"
                value={familyProxyName}
                onChange={(e) => setFamilyProxyName(e.target.value)}
                placeholder="e.g. David Miller"
                className="silver-target mt-2 w-full rounded-xl border-2 border-linen-border bg-white px-4 text-lg text-linen-ink"
              />
            </div>
          </div>

          <div>
            <label htmlFor="comm-address" className="block text-base font-bold text-linen-ink">
              Home Street Address
            </label>
            <p id="comm-address-hint" className="mt-0.5 text-sm text-linen-muted">
              Only shared inside your local neighborhood dispatch cluster.
            </p>
            <input
              id="comm-address"
              type="text"
              required
              aria-describedby="comm-address-hint"
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              placeholder="412 Prairie Timber Rd, Burleson, TX"
              className="silver-target mt-2 w-full rounded-xl border-2 border-linen-border bg-white px-4 text-lg text-linen-ink"
            />
          </div>

          <div className="rounded-xl border-2 border-linen-border bg-linen-ground/60 p-4">
            <label className="flex cursor-pointer items-start gap-3">
              <input
                type="checkbox"
                checked={consentOptIn}
                onChange={(e) => setConsentOptIn(e.target.checked)}
                className="mt-1 h-6 w-6 accent-linen-emerald"
              />
              <span className="text-base text-linen-ink">
                <strong>Share exterior yard condition photos for 25% Homeowner Data Royalty.</strong>{' '}
                <span className="text-linen-muted">
                  Optional — never medical information, and faces or license plates stay blurred on-device.
                </span>
              </span>
            </label>
          </div>

          {errorMsg && (
            <div role="alert" className="rounded-xl border-2 border-red-600 bg-red-50 p-4 text-base font-semibold text-red-900">
              {errorMsg}
            </div>
          )}

          <div className="flex flex-wrap items-center justify-between gap-4 border-t-2 border-linen-border pt-6">
            <div>
              <div className="text-sm font-medium text-linen-muted">Total Flat Charge</div>
              <div className="font-display text-3xl font-bold text-linen-ink tnum">
                {formatUsd(totalChargeCents)}
              </div>
              <div className="text-sm text-linen-muted tnum">
                Includes {formatUsd(selectedRateCents)} to helper + {formatUsd(escrowFeeCents)} neighborhood escrow
              </div>
            </div>

            <button
              type="submit"
              disabled={submitting}
              className="silver-target rounded-2xl bg-linen-emerald px-8 py-4 text-xl font-bold text-white shadow-sm transition hover:brightness-110 disabled:opacity-60"
            >
              {submitting ? 'Booking Helper...' : 'Request Helper Now'}
            </button>
          </div>
        </form>

        <div aria-live="polite" className="mt-6">
          {confirmed && (
            <section className="rounded-2xl border-2 border-linen-emerald bg-linen-surface p-6 sm:p-8 shadow-md">
              <div className="flex items-start gap-4">
                <CheckCircle2 className="h-9 w-9 shrink-0 text-linen-emerald" />
                <div className="w-full">
                  <h2 className="font-display text-2xl font-bold text-linen-ink">
                    Helper Request Confirmed
                  </h2>
                  <p className="mt-1 text-base text-linen-muted">{confirmed.task.title}</p>

                  <div className="mt-5 rounded-2xl border-2 border-linen-emerald/40 bg-linen-ground p-6 text-center">
                    <div className="inline-flex items-center gap-2 text-sm font-bold text-linen-emerald">
                      <ShieldCheck className="h-5 w-5" />
                      Your 4-Digit Verbal Arrival PIN
                    </div>
                    <div className="mt-2 font-mono text-5xl font-bold tracking-[0.25em] text-linen-ink tnum">
                      {confirmed.task.pin}
                    </div>
                    <p className="mt-2 text-base text-linen-muted">
                      {confirmed.pinHint || 'Read this PIN aloud when your helper arrives so payment escrow unlocks safely.'}
                    </p>
                  </div>

                  <p className="mt-4 text-sm text-linen-muted">{confirmed.quote.disclosureCopy}</p>
                </div>
              </div>
            </section>
          )}
        </div>
      </main>
    </div>
  );
}
