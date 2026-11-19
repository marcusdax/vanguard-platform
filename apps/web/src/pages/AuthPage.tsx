import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowRight, KeyRound, ShieldCheck } from 'lucide-react';
import { BRAND_CONFIG } from '@/brand/brandConfig';
import { useAuthStore } from '@/stores/authStore';

export function AuthPage({ mode = 'login' }: { mode?: 'login' | 'register' }) {
  const navigate = useNavigate();
  const { login, register, demoLogin, busy, error, clearError } = useAuthStore();
  const [isRegister, setIsRegister] = useState(mode === 'register');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const [companyName, setCompanyName] = useState('');

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    clearError();
    let ok = false;
    if (isRegister) {
      ok = await register({ email, password, firstName, lastName, companyName });
    } else {
      ok = await login(email, password);
    }
    if (ok) navigate('/');
  }

  async function handleDemo() {
    clearError();
    const ok = await demoLogin();
    if (ok) navigate('/');
  }

  return (
    <div className="tactical-grid-bg flex min-h-screen items-center justify-center bg-obsidian-ground px-4 py-12 text-obsidian-ink">
      <div className="w-full max-w-md rounded-2xl border border-obsidian-border bg-obsidian-surface p-7 shadow-2xl">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-obsidian-amber text-obsidian-ground font-display font-bold">
              V
            </div>
            <span className="font-display text-lg font-bold text-obsidian-ink">
              {BRAND_CONFIG.platformName}
            </span>
          </div>
          <Link to="/community" className="text-xs font-medium text-obsidian-amber hover:underline">
            Neighborhood Portal
          </Link>
        </div>

        <h1 className="mt-6 font-display text-2xl font-bold text-obsidian-ink">
          {isRegister ? 'Create Operator Organization' : 'Sign in to Tactical Operations'}
        </h1>
        <p className="mt-1.5 text-sm text-obsidian-muted">
          Access H3 batch dispatch, Earn-to-Learn hardware escrow, DePIN assets, and Tier 4 attestation.
        </p>

        <button
          type="button"
          disabled={busy}
          onClick={() => void handleDemo()}
          className="mt-5 flex w-full items-center justify-center gap-2 rounded-xl border border-obsidian-proof/50 bg-obsidian-proof/15 px-4 py-3 text-sm font-semibold text-obsidian-proof transition hover:bg-obsidian-proof/25 disabled:opacity-60"
        >
          <ShieldCheck className="h-4 w-4" />
          One-Click Demo Operations ({BRAND_CONFIG.demoEmail})
        </button>

        <form onSubmit={handleSubmit} className="mt-6 space-y-4">
          {isRegister && (
            <>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="auth-first" className="block text-xs font-medium text-obsidian-muted">
                    First Name
                  </label>
                  <input
                    id="auth-first"
                    type="text"
                    required
                    value={firstName}
                    onChange={(e) => setFirstName(e.target.value)}
                    className="mt-1 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-2 text-sm text-obsidian-ink"
                  />
                </div>
                <div>
                  <label htmlFor="auth-last" className="block text-xs font-medium text-obsidian-muted">
                    Last Name
                  </label>
                  <input
                    id="auth-last"
                    type="text"
                    required
                    value={lastName}
                    onChange={(e) => setLastName(e.target.value)}
                    className="mt-1 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-2 text-sm text-obsidian-ink"
                  />
                </div>
              </div>
              <div>
                <label htmlFor="auth-org" className="block text-xs font-medium text-obsidian-muted">
                  Crew or Company Name
                </label>
                <input
                  id="auth-org"
                  type="text"
                  required
                  value={companyName}
                  onChange={(e) => setCompanyName(e.target.value)}
                  placeholder="Vanguard Field Unit"
                  className="mt-1 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-2 text-sm text-obsidian-ink"
                />
              </div>
            </>
          )}

          <div>
            <label htmlFor="auth-email" className="block text-xs font-medium text-obsidian-muted">
              Work Email
            </label>
            <input
              id="auth-email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={BRAND_CONFIG.demoEmail}
              className="mt-1 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3.5 py-2 text-sm text-obsidian-ink"
            />
          </div>

          <div>
            <label htmlFor="auth-pass" className="block text-xs font-medium text-obsidian-muted">
              Password
            </label>
            <input
              id="auth-pass"
              type="password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Minimum 8 characters"
              className="mt-1 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3.5 py-2 text-sm text-obsidian-ink"
            />
          </div>

          {error && (
            <div role="alert" className="rounded-lg border border-obsidian-alert/40 bg-obsidian-alert/10 px-3 py-2 text-xs text-obsidian-alert">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={busy}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-obsidian-amber px-4 py-2.5 text-sm font-semibold text-obsidian-ground transition hover:brightness-110 disabled:opacity-60"
          >
            <KeyRound className="h-4 w-4" />
            {busy ? 'Authenticating...' : isRegister ? 'Create Organization' : 'Sign In'}
            <ArrowRight className="h-4 w-4" />
          </button>
        </form>

        <div className="mt-5 border-t border-obsidian-border pt-4 text-center text-xs text-obsidian-muted">
          {isRegister ? 'Already registered? ' : 'New operator unit? '}
          <button
            type="button"
            onClick={() => {
              clearError();
              setIsRegister(!isRegister);
            }}
            className="font-semibold text-obsidian-amber hover:underline"
          >
            {isRegister ? 'Sign in here' : 'Register an organization'}
          </button>
        </div>
      </div>
    </div>
  );
}
