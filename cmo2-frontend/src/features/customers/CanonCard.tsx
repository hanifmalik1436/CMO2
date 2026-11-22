import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, type Resolver } from 'react-hook-form'
import { z } from 'zod'
import { GlassCard } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { approveCanonDraft, discardCanonDraft, getCanon, saveCanonDraft } from './customersApi'
import type { CanonVersion, CustomerCanon } from '../../lib/types'

const MAX_CANON = 20_000

// Mirrors SaveCanonDraftRequest's Bean Validation on the backend.
const schema = z.object({
  content: z.string().trim().min(1, 'Write the canon first').max(MAX_CANON, `At most ${MAX_CANON.toLocaleString()} characters`),
  changeNote: z.string().max(500).optional(),
})
type FormValues = z.infer<typeof schema>

const zodResolver: Resolver<FormValues> = async (values) => {
  const parsed = schema.safeParse(values)
  if (parsed.success) return { values: parsed.data, errors: {} }
  const errors: Record<string, { type: string; message: string }> = {}
  for (const issue of parsed.error.issues) {
    const key = String(issue.path[0])
    errors[key] ??= { type: issue.code, message: issue.message }
  }
  return { values: {}, errors }
}

const PLACEHOLDER = `Positioning: who they help and how, in one or two sentences.
Brand voice: e.g. calm, dignified, plain English, no pressure.
Always include: required lines such as a disclaimer.
Never say: banned phrases and claims.
Example posts the owner likes: 2–3, pasted in full.`

/**
 * The customer's brand canon: what every AI call for them must stay true to. Staff draft it; only
 * the owner or an admin can approve it, and only the approved version reaches the AI.
 */
export function CanonCard({ companyId }: { companyId: number }) {
  const qc = useQueryClient()
  const key = ['customers', companyId, 'canon']
  const { data, isLoading, isError, error } = useQuery<CustomerCanon>({ queryKey: key, queryFn: () => getCanon(companyId) })
  const [serverError, setServerError] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(false)

  const { register, handleSubmit, reset, watch, formState: { errors, isDirty } } = useForm<FormValues>({
    resolver: zodResolver,
    defaultValues: { content: '', changeNote: '' },
  })
  useEffect(() => {
    if (!data) return
    const start = data.draft ?? data.approved
    reset({ content: start?.content ?? '', changeNote: data.draft?.changeNote ?? '' })
  }, [data, reset])

  const onDone = (c: CustomerCanon) => {
    qc.setQueryData(key, c)
    qc.invalidateQueries({ queryKey: ['customers', companyId, 'identity'] })
    setServerError(null)
  }
  const onFail = (fallback: string) => (err: unknown) => setServerError(apiErrorMessage(err, fallback))
  const save = useMutation({
    mutationFn: (v: FormValues) => saveCanonDraft(companyId, v.content.trim(), v.changeNote?.trim() || undefined),
    onSuccess: onDone, onError: onFail('Could not save the draft'),
  })
  const approve = useMutation({ mutationFn: () => approveCanonDraft(companyId), onSuccess: onDone, onError: onFail('Could not approve') })
  const discard = useMutation({ mutationFn: () => discardCanonDraft(companyId), onSuccess: onDone, onError: onFail('Could not discard') })
  const busy = save.isPending || approve.isPending || discard.isPending

  const content = watch('content') ?? ''
  const changes = useMemo(
    () => (data?.draft ? lineChanges(data.approved?.content ?? '', data.draft.content) : null),
    [data],
  )

  return (
    <GlassCard>
      <h3 className="font-semibold text-white">Brand canon</h3>
      <p className="mt-1 text-xs text-gray-400">
        Who this business is and how it must sound, in full. The approved version goes into every AI call for this
        customer, so months of history can't pull the content away from it. Nothing changes it automatically.
      </p>
      {isLoading && <p className="mt-4 text-sm text-gray-400">Loading canon…</p>}
      {isError && <p className="mt-4 text-sm text-red-300">{apiErrorMessage(error, 'Could not load the canon')}</p>}
      {data && (
        <div className="mt-4 space-y-4">
          <div className="flex flex-wrap gap-3 text-xs">
            <span className="rounded-full bg-white/5 px-2 py-1 text-gray-300">
              {data.approved
                ? `AI uses version ${data.approved.versionNo}, approved ${fmt(data.approved.approvedAt)}`
                : 'No approved canon yet: the AI only has the profile below'}
            </span>
            {data.draft && (
              <span className="rounded-full bg-amber-500/15 px-2 py-1 text-amber-200">
                Draft waiting for approval, saved {fmt(data.draft.updatedAt)}
              </span>
            )}
          </div>

          <RequireRole
            allow={['OWNER', 'ADMIN', 'STRATEGIST']}
            fallback={data.approved
              ? <pre className="max-h-[320px] overflow-auto whitespace-pre-wrap rounded-lg bg-black/30 p-3 text-xs text-gray-300">{data.approved.content}</pre>
              : <p className="text-sm text-gray-400">Only the owner, an admin or a strategist can write the canon.</p>}
          >
            <form className="space-y-3" onSubmit={handleSubmit((v) => save.mutate(v))}>
              <textarea className="input min-h-[260px] font-mono text-xs" placeholder={PLACEHOLDER} {...register('content')} />
              <div className="flex justify-between text-[11px] text-gray-500">
                <span>{errors.content?.message && <span className="text-red-300">{errors.content.message}</span>}</span>
                <span>{content.length.toLocaleString()} / {MAX_CANON.toLocaleString()}</span>
              </div>
              <input className="input" placeholder="What changed (optional)" {...register('changeNote')} />
              <div className="flex flex-wrap items-center gap-3">
                <button type="submit" className="btn-primary" disabled={busy || !isDirty}>
                  {save.isPending ? 'Saving…' : 'Save draft'}
                </button>
                {data.draft && (
                  <>
                    <RequireRole allow={['OWNER', 'ADMIN']} fallback={<span className="text-xs text-gray-400">The owner or an admin approves it.</span>}>
                      <button type="button" className="btn-primary" disabled={busy || isDirty} onClick={() => approve.mutate()}
                              title={isDirty ? 'Save the draft first' : undefined}>
                        {approve.isPending ? 'Approving…' : 'Approve draft'}
                      </button>
                    </RequireRole>
                    <button type="button" className="btn-ghost" disabled={busy} onClick={() => discard.mutate()}>
                      Discard draft
                    </button>
                  </>
                )}
                {serverError && <span className="text-sm text-red-300">{serverError}</span>}
              </div>
            </form>
          </RequireRole>

          {changes && (
            <div>
              <p className="text-xs text-gray-400">
                {data.approved ? `Draft against version ${data.approved.versionNo}:` : 'Draft (nothing approved yet):'}
              </p>
              {changes.length === 0
                ? <p className="mt-2 text-xs text-gray-500">No changed lines.</p>
                : (
                  <pre className="mt-2 max-h-[240px] overflow-auto whitespace-pre-wrap rounded-lg bg-black/30 p-3 text-xs">
                    {changes.map((c, i) => (
                      <div key={i} className={c.added ? 'text-emerald-300' : 'text-red-300'}>{c.added ? '+ ' : '− '}{c.line}</div>
                    ))}
                  </pre>
                )}
            </div>
          )}

          {data.history.length > 0 && (
            <div>
              <button className="text-xs text-gray-400 underline" onClick={() => setShowHistory(!showHistory)}>
                {showHistory ? 'Hide' : 'Show'} versions ({data.history.length})
              </button>
              {showHistory && (
                <ul className="mt-2 divide-y divide-white/5 text-xs">
                  {data.history.map((v) => (
                    <HistoryRow key={v.id} v={v} onLoad={() => reset({ content: v.content, changeNote: `Back to version ${v.versionNo}` }, { keepDefaultValues: true })} />
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
      )}
    </GlassCard>
  )
}

function HistoryRow({ v, onLoad }: { v: CanonVersion; onLoad: () => void }) {
  return (
    <li className="flex items-center justify-between gap-3 py-2">
      <span className="text-gray-300">
        {v.versionNo ? `Version ${v.versionNo}` : 'Draft'} · {v.status.toLowerCase()} · {fmt(v.approvedAt ?? v.updatedAt)}
        {v.changeNote && <span className="text-gray-500"> · {v.changeNote}</span>}
      </span>
      {v.status === 'RETIRED' && (
        <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={null}>
          <button className="text-gray-400 underline hover:text-white" onClick={onLoad}>Load into editor</button>
        </RequireRole>
      )}
    </li>
  )
}

/** Lines only in one version: enough to review a canon edit before approving it. */
function lineChanges(before: string, after: string): { line: string; added: boolean }[] {
  const was = new Set(before.split('\n').map((l) => l.trimEnd()))
  const now = new Set(after.split('\n').map((l) => l.trimEnd()))
  const removed = [...was].filter((l) => l && !now.has(l)).map((line) => ({ line, added: false }))
  const added = [...now].filter((l) => l && !was.has(l)).map((line) => ({ line, added: true }))
  return [...removed, ...added]
}

function fmt(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : '—'
}
