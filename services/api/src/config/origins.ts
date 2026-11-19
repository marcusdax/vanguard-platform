/**
 * Which browser origins may call the API.
 *
 * Always: the configured FRONTEND_URL list. With ALLOW_TUNNEL_CORS=true, also
 * HTTPS origins on the quick-tunnel domains used for demos (Cloudflare and
 * ngrok) — not every website on the internet.
 */
const TUNNEL_HOST = /^[a-z0-9-]+\.(trycloudflare\.com|ngrok-free\.app|ngrok\.app|ngrok\.io)$/i;

export function isAllowedOrigin(origin: string | undefined, allowed: readonly string[], allowTunnels: boolean): boolean {
  if (!origin) return true; // same-origin, curl, server-to-server: no Origin header
  if (allowed.includes(origin)) return true;
  if (!allowTunnels) return false;
  try {
    const u = new URL(origin);
    return u.protocol === 'https:' && !u.port && TUNNEL_HOST.test(u.hostname);
  } catch {
    return false;
  }
}

/** Origin option usable by both the `cors` middleware and Socket.IO. */
export function originChecker(allowed: readonly string[], allowTunnels: boolean) {
  return (origin: string | undefined, callback: (err: Error | null, allow?: boolean) => void) =>
    callback(null, isAllowedOrigin(origin, allowed, allowTunnels));
}
