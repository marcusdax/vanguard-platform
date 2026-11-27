import { useEffect, useState } from 'react';
import { Camera, MapPin, MessageSquarePlus, Radio, Sparkles, Upload } from 'lucide-react';
import { api } from '@/lib/api';

interface FieldSpaceSummary {
  id: string;
  slug: string;
  name: string;
  tagline: string;
  district: string;
  roleLabel: string;
  followerCount: number;
  following: boolean;
  postCount: number;
}

interface FieldSocialPostItem {
  id: string;
  kind: string;
  body: string;
  mediaUrls?: string[];
  createdAt: string;
  author?: { firstName: string; lastName: string; role: string } | null;
  space?: { id: string; name: string; district: string } | null;
  keepCount?: number;
}

interface LiveSessionItem {
  id: string;
  title: string;
  streamKey: string;
  status: string;
  hlsUrl?: string | null;
  webrtcUrl?: string | null;
  startedAt: string;
  host?: { firstName: string; lastName: string } | null;
}

export function FieldLiveConsole() {
  const [spaces, setSpaces] = useState<FieldSpaceSummary[]>([]);
  const [posts, setPosts] = useState<FieldSocialPostItem[]>([]);
  const [liveSessions, setLiveSessions] = useState<LiveSessionItem[]>([]);
  const [selectedSpaceId, setSelectedSpaceId] = useState<string>('');
  const [postBody, setPostBody] = useState('');
  const [uploadedUrls, setUploadedUrls] = useState<string[]>([]);
  const [visionSummary, setVisionSummary] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function refreshAll() {
    try {
      const [sRes, pRes, lRes] = await Promise.all([
        api.get('/field/social/spaces'),
        api.get('/field/social/posts'),
        api.get('/field/social/live/active'),
      ]);
      const spaceList = sRes.data?.data ?? [];
      setSpaces(spaceList);
      if (!selectedSpaceId && spaceList.length > 0) {
        setSelectedSpaceId(spaceList[0].id);
      }
      setPosts(pRes.data?.data ?? []);
      setLiveSessions(lRes.data?.data ?? []);
    } catch {
      // ignore initial partial errors
    }
  }

  useEffect(() => {
    void refreshAll();
  }, []);

  async function handleFileUpload(files: FileList | null) {
    if (!files || files.length === 0) return;
    setBusy(true);
    try {
      const fd = new FormData();
      Array.from(files).forEach((f) => fd.append('files', f));
      const { data } = await api.post('/field/social/upload', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      const urls = (data?.files ?? []).map((x: { url: string }) => x.url);
      setUploadedUrls((prev) => [...prev, ...urls]);
    } finally {
      setBusy(false);
    }
  }

  async function handlePublishPost(e: React.FormEvent) {
    e.preventDefault();
    if (!postBody.trim()) return;
    setBusy(true);
    try {
      await api.post('/field/social/posts', {
        spaceId: selectedSpaceId || undefined,
        kind: 'NOTE',
        body: postBody.trim(),
        mediaUrls: uploadedUrls,
      });
      setPostBody('');
      setUploadedUrls([]);
      await refreshAll();
    } finally {
      setBusy(false);
    }
  }

  async function runVisionInspection(fileUrl?: string) {
    setBusy(true);
    try {
      const { data } = await api.post('/field/social/analyze-photo', {
        fileUrl,
        fileName: 'slope-inspection.jpg',
        notes: 'Check shingle hail strikes and ridge cap condition',
      });
      const analysis = data?.data ?? data?.analysis ?? data;
      const damage = analysis?.damageClass ?? 'ASSESSED';
      const strikes = analysis?.hailStrikeCount ?? 0;
      const conf =
        typeof analysis?.confidence === 'number'
          ? ` (${Math.round(analysis.confidence * 100)}% conf)`
          : '';
      setVisionSummary(`Vanguard Vision Sidecar: ${damage} · ${strikes} strikes flagged${conf}`);
    } catch (err: any) {
      setVisionSummary(err?.response?.data?.error || 'Vision inspection request queued.');
    } finally {
      setBusy(false);
    }
  }

  async function toggleStartLive() {
    setBusy(true);
    try {
      await api.post('/field/social/live/start', {
        spaceId: selectedSpaceId || undefined,
        title: 'Live Parcel Slope & Gutter Capture Stream',
      });
      await refreshAll();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-12">
      {/* Left column: H3 Spaces & Live MediaMTX Streams */}
      <div className="space-y-5 xl:col-span-5">
        <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5">
          <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
            <h2 className="font-display text-base font-bold text-obsidian-ink">
              Operational Zone Spaces
            </h2>
            <span className="font-mono text-xs text-obsidian-muted tnum">
              {spaces.length} active
            </span>
          </div>
          <div className="mt-4 space-y-3">
            {spaces.map((sp) => (
              <button
                key={sp.id}
                type="button"
                onClick={() => setSelectedSpaceId(sp.id)}
                className={`w-full rounded-lg border p-3.5 text-left transition ${
                  selectedSpaceId === sp.id
                    ? 'border-obsidian-amber bg-obsidian-ground'
                    : 'border-obsidian-border bg-obsidian-ground/50 hover:border-obsidian-muted'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="font-semibold text-sm text-obsidian-ink">{sp.name}</span>
                  <span className="rounded bg-obsidian-elevated px-2 py-0.5 font-mono text-[11px] text-obsidian-amber">
                    {sp.roleLabel}
                  </span>
                </div>
                <p className="mt-1 text-xs text-obsidian-muted">{sp.tagline}</p>
                <div className="mt-2 flex items-center gap-3 font-mono text-[11px] text-obsidian-muted tnum">
                  <span className="inline-flex items-center gap-1">
                    <MapPin className="h-3 w-3" />
                    {sp.district}
                  </span>
                  <span>{sp.postCount} dispatches</span>
                </div>
              </button>
            ))}
          </div>
        </section>

        <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5">
          <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
            <div className="flex items-center gap-2">
              <Radio className="h-4 w-4 text-obsidian-proof" />
              <h2 className="font-display text-base font-bold text-obsidian-ink">
                MediaMTX WebRTC / HLS Feed
              </h2>
            </div>
            <button
              type="button"
              disabled={busy}
              onClick={toggleStartLive}
              className="rounded-lg bg-obsidian-proof/15 px-3 py-1.5 text-xs font-semibold text-obsidian-proof transition hover:bg-obsidian-proof/25"
            >
              Open Live Channel
            </button>
          </div>
          <div className="mt-4 space-y-2.5">
            {liveSessions.length === 0 ? (
              <p className="text-xs text-obsidian-muted">
                No active RTSP/WebRTC broadcast right now. Click Open Live Channel to provision a stream key.
              </p>
            ) : (
              liveSessions.map((sess) => (
                <div
                  key={sess.id}
                  className="rounded-lg border border-obsidian-proof/40 bg-obsidian-ground p-3 text-xs"
                >
                  <div className="flex items-center justify-between font-semibold text-obsidian-ink">
                    <span>{sess.title}</span>
                    <span className="font-mono text-obsidian-proof">LIVE</span>
                  </div>
                  <div className="mt-1 font-mono text-[11px] text-obsidian-muted">
                    Key: {sess.streamKey}
                  </div>
                </div>
              ))
            )}
          </div>
        </section>
      </div>

      {/* Right column: Field Capture Composer + Vision Sidecar + Chronological Feed */}
      <div className="space-y-5 xl:col-span-7">
        <form
          onSubmit={handlePublishPost}
          className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5"
        >
          <label htmlFor="field-post-input" className="block font-display text-base font-bold text-obsidian-ink">
            Post Field Dispatch Update
          </label>
          <textarea
            id="field-post-input"
            rows={3}
            value={postBody}
            onChange={(e) => setPostBody(e.target.value)}
            placeholder="Log test-square counts, parcel perimeter observations, or lot handoff notes..."
            className="mt-3 w-full rounded-lg border border-obsidian-border bg-obsidian-ground px-3.5 py-2.5 text-sm text-obsidian-ink placeholder:text-obsidian-muted/60"
          />
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
            <div className="flex flex-wrap items-center gap-2">
              <label className="inline-flex cursor-pointer items-center gap-1.5 rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-1.5 text-xs font-medium text-obsidian-ink hover:border-obsidian-amber">
                <Upload className="h-3.5 w-3.5" />
                Attach Media
                <input
                  type="file"
                  multiple
                  accept="image/*,video/*"
                  className="sr-only"
                  onChange={(e) => void handleFileUpload(e.target.files)}
                />
              </label>
              <button
                type="button"
                disabled={busy}
                onClick={() => void runVisionInspection(uploadedUrls[0])}
                className="inline-flex items-center gap-1.5 rounded-lg border border-obsidian-border bg-obsidian-ground px-3 py-1.5 text-xs font-medium text-obsidian-amber hover:border-obsidian-amber"
              >
                <Sparkles className="h-3.5 w-3.5" />
                Run Vision CV Audit
              </button>
            </div>
            <button
              type="submit"
              disabled={busy || !postBody.trim()}
              className="inline-flex items-center gap-1.5 rounded-lg bg-obsidian-amber px-4 py-1.5 text-xs font-semibold text-obsidian-ground disabled:opacity-50"
            >
              <MessageSquarePlus className="h-3.5 w-3.5" />
              Publish Note
            </button>
          </div>
          {uploadedUrls.length > 0 && (
            <div className="mt-2 font-mono text-xs text-obsidian-proof">
              Attached {uploadedUrls.length} file(s) ready for chain seal
            </div>
          )}
          {visionSummary && (
            <div className="mt-2 rounded-lg border border-obsidian-amber/40 bg-obsidian-amber/10 px-3 py-2 font-mono text-xs text-obsidian-amber">
              {visionSummary}
            </div>
          )}
        </form>

        <section className="rounded-xl border border-obsidian-border bg-obsidian-surface p-5">
          <div className="flex items-center justify-between border-b border-obsidian-border pb-3">
            <h2 className="font-display text-base font-bold text-obsidian-ink">
              Chronological Field Ledger Feed
            </h2>
            <span className="font-mono text-xs text-obsidian-muted tnum">{posts.length} posts</span>
          </div>
          <div className="mt-4 space-y-3">
            {posts.length === 0 ? (
              <div className="flex items-center gap-2 text-xs text-obsidian-muted">
                <Camera className="h-4 w-4" />
                No dispatches posted in this zone yet.
              </div>
            ) : (
              posts.map((post) => (
                <article
                  key={post.id}
                  className="rounded-lg border border-obsidian-border bg-obsidian-ground p-4"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
                    <span className="font-semibold text-obsidian-ink">
                      {post.author
                        ? `${post.author.firstName} ${post.author.lastName}`
                        : 'Field Operator'}
                    </span>
                    <span className="font-mono text-obsidian-muted">
                      {post.space?.name || 'Zone Dispatch'} · {new Date(post.createdAt).toLocaleString()}
                    </span>
                  </div>
                  <p className="mt-2 text-sm leading-relaxed text-obsidian-ink/90">{post.body}</p>
                </article>
              ))
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
