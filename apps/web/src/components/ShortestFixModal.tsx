import { useEffect, useRef } from 'react';
import { Award, Clock, ShieldAlert, X } from 'lucide-react';
import type { CredentialGateBlock } from '@/types/vanguard';

interface ShortestFixModalProps {
  block: CredentialGateBlock | null;
  busy?: boolean;
  onClose: () => void;
  onStartModule: (moduleId: string) => void;
}

export function ShortestFixModal({
  block,
  busy = false,
  onClose,
  onStartModule,
}: ShortestFixModalProps) {
  const dialogRef = useRef<HTMLDialogElement | null>(null);

  useEffect(() => {
    const dlg = dialogRef.current;
    if (!dlg) return;
    if (block) {
      if (!dlg.open) {
        dlg.returnValue = '';
        dlg.showModal();
      }
    } else if (dlg.open) {
      dlg.close();
    }
  }, [block]);

  return (
    <dialog
      ref={dialogRef}
      className="vanguard-dialog w-full max-w-lg"
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === dialogRef.current) onClose();
      }}
    >
      {block && (
        <div className="p-6">
          <div className="flex items-start justify-between gap-4 border-b border-obsidian-border pb-4">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-obsidian-amber/15 text-obsidian-amber">
                <ShieldAlert className="h-5 w-5" />
              </div>
              <div>
                <h2 className="font-display text-lg font-bold text-obsidian-ink">
                  Credential Required for Dispatch Claim
                </h2>
                <p className="mt-0.5 font-mono text-xs text-obsidian-muted">
                  Invariant {block.invariant} · {block.missingCredential}
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label="Close requirement modal"
              className="rounded-lg p-1.5 text-obsidian-muted transition hover:bg-obsidian-elevated hover:text-obsidian-ink"
            >
              <X className="h-4 w-4" />
            </button>
          </div>

          <p className="mt-4 text-sm leading-relaxed text-obsidian-ink/90">{block.reason}</p>

          <div className="mt-5 rounded-xl border border-obsidian-border bg-obsidian-ground p-4">
            <div className="flex items-center justify-between gap-3">
              <span className="font-mono text-xs font-semibold text-obsidian-proof">
                Shortest Fix Path · Module {block.shortestFix.moduleId}
              </span>
              <span className="inline-flex items-center gap-1 rounded-md bg-obsidian-proof/15 px-2 py-0.5 font-mono text-xs font-semibold text-obsidian-proof">
                $0.00 Open Curriculum
              </span>
            </div>
            <p className="mt-2 font-medium text-obsidian-ink">{block.shortestFix.lessonTitle}</p>
            <div className="mt-2 flex items-center gap-4 text-xs text-obsidian-muted">
              <span className="inline-flex items-center gap-1.5 tnum">
                <Clock className="h-3.5 w-3.5" />
                {block.shortestFix.minutes} min self-paced
              </span>
              <span className="inline-flex items-center gap-1.5">
                <Award className="h-3.5 w-3.5 text-obsidian-amber" />
                Grants {block.missingCredential}
              </span>
            </div>
          </div>

          <div className="mt-6 flex flex-wrap items-center justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              className="rounded-lg border border-obsidian-border px-4 py-2 text-sm font-medium text-obsidian-muted transition hover:border-obsidian-ink/40 hover:text-obsidian-ink"
            >
              Return to Dispatch
            </button>
            <button
              type="button"
              disabled={busy}
              onClick={() => onStartModule(block.shortestFix.moduleId)}
              className="rounded-lg bg-obsidian-amber px-4 py-2 text-sm font-semibold text-obsidian-ground transition hover:brightness-110 disabled:opacity-60"
            >
              {busy ? 'Completing Module...' : `Complete ${block.shortestFix.moduleId} Now`}
            </button>
          </div>
        </div>
      )}
    </dialog>
  );
}
