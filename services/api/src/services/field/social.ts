/**
 * Pure helpers & Space trust calculations modeled on AUCTUM.COFFEE (`marcusdax/AUCTUM`).
 * Rule from AUCTUM.COFFEE: Keeping/replying is social signal only — only verified
 * evidence packets & shipped/sealed field work move Space Trust.
 */

export function slugifySpaceName(raw: string): string {
  const base = raw
    .toLowerCase()
    .trim()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48);
  return base || `space-${Date.now().toString(36)}`;
}

export interface SpaceTrustSummary {
  score: number;
  band: 'Verified' | 'Building' | 'New';
  sealedPackets: number;
}

export function computeSpaceTrust(sealedPackets: number, attachedJobs: number): SpaceTrustSummary {
  // Base 52 for active territory desk, +12 per verified sealed packet, +5 per active job, capped 99
  const raw = 52 + sealedPackets * 12 + attachedJobs * 5;
  const score = Math.min(99, Math.max(0, Math.round(raw)));
  const band: SpaceTrustSummary['band'] =
    sealedPackets >= 2 ? 'Verified' : sealedPackets >= 1 || attachedJobs >= 1 ? 'Building' : 'New';
  return { score, band, sealedPackets };
}

export interface SanitizedMediaItem {
  url: string;
  fileName: string;
  mimeType: string;
  size: number;
  fileType: 'IMAGE' | 'VIDEO' | 'DOCUMENT';
}

export function sanitizeMediaUrls(input: unknown): SanitizedMediaItem[] {
  if (!Array.isArray(input)) return [];
  const out: SanitizedMediaItem[] = [];
  for (const item of input.slice(0, 8)) {
    if (!item || typeof item !== 'object') continue;
    const obj = item as Record<string, unknown>;
    const url = typeof obj.url === 'string' ? obj.url.trim() : '';
    if (!url || (!url.startsWith('/uploads/') && !url.startsWith('https://') && !url.startsWith('http://'))) {
      continue;
    }
    const mimeType = typeof obj.mimeType === 'string' ? obj.mimeType : 'image/jpeg';
    const fileType: SanitizedMediaItem['fileType'] =
      mimeType.startsWith('video/')
        ? 'VIDEO'
        : mimeType.startsWith('image/')
          ? 'IMAGE'
          : 'DOCUMENT';
    out.push({
      url: url.slice(0, 500),
      fileName: typeof obj.fileName === 'string' ? obj.fileName.slice(0, 140) : 'field-media',
      mimeType: mimeType.slice(0, 80),
      size: typeof obj.size === 'number' && Number.isFinite(obj.size) ? Math.max(0, Math.floor(obj.size)) : 0,
      fileType,
    });
  }
  return out;
}
