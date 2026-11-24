import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { getCanon } from '../customers/customersApi'
import { analyzeSource, getBatch, getSource, listBatches, listStudioFormats, startBatch } from './studioApi'
import { Icon } from './icons'
import type { ContentBatch, ContentIdea, ContentSourceDetail, CustomerCanon, StudioFormat } from '../../lib/types'

const MAX_PIECES = 40
const BUSY = ['PENDING', 'ANALYZING']

const TYPE_STYLE: Record<ContentIdea['type'], string> = {
  INSIGHT: 'bg-sky-500/15 text-sky-300',
  STORY: 'bg-violet-500/15 text-violet-300',
  TIP: 'bg-emerald-500/15 text-emerald-300',
  CONTRARIAN: 'bg-rose-500/15 text-rose-300',
  HOW_TO: 'bg-amber-500/15 text-amber-300',
  STAT: 'bg-cyan-500/15 text-cyan-300',
}

/**
 * The generator for one source: pick the full source and/or ideas (best hook first), pick formats,
 * and write a batch of drafts. Every draft goes to Approvals; Smart Schedule only suggests times.
 */
export function ContentGenerator({ companyId, companyName, sourceId, onBack }: {
  companyId: number
  companyName: string
  sourceId: number
  onBack: () => void
}) {
  const qc = useQueryClient()
  const source = useQuery<ContentSourceDetail>({
    queryKey: ['studio', 'source', sourceId],
    queryFn: () => getSource(sourceId),
    refetchInterval: (q) => (q.state.data && BUSY.includes(q.state.data.source.status) ? 2000 : false),
  })
  const formats = useQuery<StudioFormat[]>({ queryKey: ['studio', 'formats'], queryFn: listStudioFormats, staleTime: Infinity })
  const canon = useQuery<CustomerCanon>({ queryKey: ['customers', companyId, 'canon'], queryFn: () => getCanon(companyId), retry: false })

  const [fullSource, setFullSource] = useState(true)
  const [ideaIds, setIdeaIds] = useState<number[]>([])
  const [chosen, setChosen] = useState<string[]>([])
  const [drafts, setDrafts] = useState(1)
  const [translate, setTranslate] = useState(false)
  const [smartSchedule, setSmartSchedule] = useState(true)
  const [count, setCount] = useState(8)
  const [ideasOpen, setIdeasOpen] = useState(true)
  const [batchId, setBatchId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { setIdeaIds([]); setFullSource(true) }, [sourceId])

  const analyze = useMutation({
    mutationFn: () => analyzeSource(sourceId, count),
    onSuccess: (d) => {
      qc.setQueryData(['studio', 'source', sourceId], d)
      qc.invalidateQueries({ queryKey: ['studio', companyId, 'sources'] })
      setIdeaIds([])
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not find ideas')),
  })
  const generate = useMutation({
    mutationFn: () => startBatch(companyId, {
      sourceId, includeFullSource: fullSource, ideaIds, formats: chosen, draftsPerFormat: drafts, translate, smartSchedule,
    }),
    onSuccess: (b) => {
      setBatchId(b.id)
      qc.invalidateQueries({ queryKey: ['studio', companyId, 'batches'] })
      qc.invalidateQueries({ queryKey: ['studio', companyId, 'sources'] })
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not start the batch')),
  })

  const d = source.data
  const ideas = d?.ideas ?? []
  const busy = d ? BUSY.includes(d.source.status) || analyze.isPending : false
  const materials = ideaIds.length + (fullSource ? 1 : 0)
  const total = materials * chosen.length * drafts
  const tooMany = total > MAX_PIECES
  const kind = d?.source.kind === 'RECORDING' ? 'recording' : d?.source.kind === 'LINK' ? 'page' : 'transcript'

  return (
    <div className="pb-28">
      <button type="button" className="mb-3 flex items-center gap-1 text-xs text-gray-400 hover:text-white" onClick={onBack}>
        <Icon name="left" className="h-3 w-3" /> Content Library
      </button>
      <h2 className="text-xl font-semibold text-white">Advanced Content Generator</h2>
      <p className="mt-2 text-sm text-gray-400">Select ideas and formats to generate all your content at once.</p>
      <p className="mt-3 text-xs text-gray-400">
        Writing for <span className="text-gray-200">{companyName}</span>
        {canon.data && (canon.data.approved
          ? <> · brand canon v{canon.data.approved.versionNo}</>
          : <> · <Link to={`/customers/${companyId}`} className="text-amber-300 underline">no approved brand canon yet</Link></>)}
      </p>

      {source.isLoading && <GlassCard className="mt-5"><p className="text-sm text-gray-400">Loading source…</p></GlassCard>}
      {source.isError && <GlassCard className="mt-5"><p className="text-sm text-red-300">{apiErrorMessage(source.error, 'Could not load the source')}</p></GlassCard>}

      {d && (
        <div className="mt-5 grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_300px]">
          <div>
            <label className={`flex cursor-pointer gap-3 rounded-xl border p-4 ${fullSource ? 'border-white/30 bg-white/[0.06]' : 'border-white/10 bg-white/[0.02]'}`}>
              <input type="checkbox" className="mt-1" checked={fullSource} onChange={(e) => setFullSource(e.target.checked)} />
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="font-semibold text-white">Full {kind}</span>
                  <span className="rounded-full border border-white/20 px-1.5 text-[10px] text-gray-300">Raw source</span>
                </div>
                <p className="mt-1 text-xs text-gray-300">
                  Generate directly from the whole {kind} ({d.source.characters.toLocaleString()} characters) without picking ideas first.
                </p>
                <p className="mt-2 line-clamp-2 text-xs text-gray-500">{d.source.preview}</p>
              </div>
            </label>

            <div className="mb-4 mt-6 flex flex-wrap items-center gap-3">
              <button type="button" className="flex items-center gap-2 text-xs font-semibold uppercase tracking-[0.15em] text-gray-300"
                      onClick={() => setIdeasOpen(!ideasOpen)} aria-expanded={ideasOpen}>
                <Icon name="down" className={`h-3.5 w-3.5 transition ${ideasOpen ? 'rotate-180' : ''}`} />
                <Icon name="bulb" className="h-3.5 w-3.5" /> Viral ideas ({ideaIds.length}/{ideas.length})
              </button>
              <span className="flex-1" />
              {ideas.length > 0 && (
                <button type="button" className="text-xs font-medium text-gray-200 hover:text-white"
                        onClick={() => setIdeaIds(ideaIds.length === ideas.length ? [] : ideas.map((i) => i.id))}>
                  {ideaIds.length === ideas.length ? 'Clear all' : 'Select all'}
                </button>
              )}
              <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={null}>
                <label className="flex items-center gap-1.5 text-xs text-gray-400">
                  Count
                  <input className="input h-7 w-14 px-2 py-0 text-xs" type="number" min={1} max={20} value={count}
                         onChange={(e) => setCount(Math.min(20, Math.max(1, Number(e.target.value) || 1)))} />
                </label>
                <button type="button" className="text-xs font-medium text-gray-200 hover:text-white disabled:opacity-40" disabled={busy}
                        onClick={() => { setError(null); analyze.mutate() }}>
                  {busy ? 'Finding ideas…' : ideas.length ? 'Re-analyze' : 'Find ideas'}
                </button>
              </RequireRole>
            </div>

            {ideasOpen && (
              <div className="space-y-2.5">
                {busy && ideas.length === 0 && (
                  <IdeaNote>Finding the strongest ideas in this {kind}… You can already generate from the full {kind}.</IdeaNote>
                )}
                {!busy && ideas.length === 0 && (
                  <IdeaNote>
                    {d.source.status === 'TOO_SHORT' ? `This ${kind} is too short to find ideas in. You can still generate from the full ${kind}.`
                      : d.source.status === 'FAILED' ? `${d.source.statusNote ?? 'The idea search failed.'} Try Re-analyze, or generate from the full ${kind}.`
                        : 'No ideas yet. Click Find ideas to pull the strongest angles out of this source.'}
                  </IdeaNote>
                )}
                {ideas.map((idea) => (
                  <IdeaCard key={idea.id} idea={idea} selected={ideaIds.includes(idea.id)}
                            onToggle={(on) => setIdeaIds(on ? [...ideaIds, idea.id] : ideaIds.filter((id) => id !== idea.id))} />
                ))}
              </div>
            )}

            <BatchProgress companyId={companyId} batchId={batchId} />
          </div>

          <aside className="space-y-5 xl:sticky xl:top-4">
            <div className="rounded-xl border border-white/15 p-4">
              <div className="flex items-center gap-2">
                <h4 className="text-sm font-semibold text-white">Smart Schedule</h4>
                <span className="rounded-full border border-white/30 px-1.5 text-[9px] text-gray-200">Recommended</span>
                <button type="button" role="switch" aria-checked={smartSchedule} aria-label="Smart Schedule"
                        onClick={() => setSmartSchedule(!smartSchedule)}
                        className={`relative ml-auto h-4 w-8 shrink-0 rounded-full transition ${smartSchedule ? 'bg-brand' : 'bg-white/15'}`}>
                  <span className={`absolute top-0.5 h-3 w-3 rounded-full bg-white transition ${smartSchedule ? 'left-[18px]' : 'left-0.5'}`} />
                </button>
              </div>
              <p className="mt-2 text-xs text-gray-400">
                Suggests a posting time for each post over the next two weeks, one per platform a day, in the customer's
                timezone. Nothing is scheduled until you approve and publish.
              </p>
            </div>
            <FormatPicker formats={formats.data ?? []} loading={formats.isLoading} chosen={chosen} onChange={setChosen}
                          drafts={drafts} onDrafts={setDrafts} translate={translate} onTranslate={setTranslate} />
          </aside>
        </div>
      )}

      {d && (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-white/10 bg-ink/95 px-6 py-3 backdrop-blur md:left-60">
          <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-sm">
            <Stat value={`${materials} ${materials === 1 ? 'source' : 'sources'}`} label="selected" />
            <Stat value={`${chosen.length} format${chosen.length === 1 ? '' : 's'}`} label={`${drafts} draft${drafts === 1 ? '' : 's'} each`} />
            <div className="text-center">
              <div className={`text-base font-semibold ${tooMany ? 'text-red-300' : 'text-white'}`}>{total}</div>
              <div className="text-[11px] text-gray-400">total pieces{translate ? ' (+ Spanish)' : ''}</div>
            </div>
            {tooMany && <span className="text-xs text-red-300">One batch can make at most {MAX_PIECES}.</span>}
            {error && <span className="text-xs text-red-300">{error}</span>}
            <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={null}>
              <div className="ml-auto flex flex-col items-center gap-1">
                <button type="button" className="btn-primary" disabled={total === 0 || tooMany || generate.isPending}
                        onClick={() => { setError(null); generate.mutate() }}>
                  {generate.isPending ? 'Starting…' : <>Generate batch <Icon name="sparkles" className="h-3.5 w-3.5" /></>}
                </button>
                <span className="text-[10px] text-gray-500">Drafts wait in Approvals</span>
              </div>
            </RequireRole>
          </div>
        </div>
      )}
    </div>
  )
}

function Stat({ value, label }: { value: string; label: string }) {
  return (
    <div>
      <div className="font-medium text-white">{value}</div>
      <div className="text-[11px] text-gray-400">{label}</div>
    </div>
  )
}

function IdeaNote({ children }: { children: React.ReactNode }) {
  return <div className="rounded-xl border border-dashed border-white/15 p-4 text-xs text-gray-400">{children}</div>
}

function scoreStyle(score: number): string {
  if (score >= 75) return 'bg-emerald-500/15 text-emerald-300'
  if (score >= 50) return 'bg-amber-500/15 text-amber-300'
  return 'bg-white/10 text-gray-400'
}

function IdeaCard({ idea, selected, onToggle }: { idea: ContentIdea; selected: boolean; onToggle: (on: boolean) => void }) {
  return (
    <label className={`block cursor-pointer rounded-xl border p-4 transition ${selected ? 'border-brand bg-brand/10' : 'border-white/10 bg-white/[0.02] hover:border-white/20'}`}>
      <div className="flex items-start gap-3">
        <input type="checkbox" className="mt-1" checked={selected} onChange={(e) => onToggle(e.target.checked)} />
        <div className="min-w-0 flex-1 space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${TYPE_STYLE[idea.type] ?? 'bg-white/10 text-gray-300'}`}>
              {idea.type.replace('_', ' ')}
            </span>
            {idea.viralityScore != null && (
              <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${scoreStyle(idea.viralityScore)}`}
                    title="How strong the hook is for this customer's audience. An estimate, not a promise.">
                Hook {idea.viralityScore}/100
              </span>
            )}
          </div>
          <div className="font-semibold text-white">{idea.title}</div>
          {idea.summary && <p className="text-xs text-gray-400">{idea.summary}</p>}
          {idea.viralityReason && <p className="text-xs text-gray-500">Why it could travel: {idea.viralityReason}</p>}
          {idea.hooks.length > 0 && (
            <div className="flex flex-wrap gap-1.5">
              {idea.hooks.map((h) => (
                <span key={h} className="rounded-md border border-white/10 bg-black/20 px-2 py-0.5 text-[11px] text-gray-300">{h}</span>
              ))}
            </div>
          )}
          {idea.quote && <p className="border-l border-white/15 pl-2.5 text-[11px] italic text-gray-500">“{idea.quote}”</p>}
        </div>
      </div>
    </label>
  )
}

function FormatPicker({ formats, loading, chosen, onChange, drafts, onDrafts, translate, onTranslate }: {
  formats: StudioFormat[]
  loading: boolean
  chosen: string[]
  onChange: (keys: string[]) => void
  drafts: number
  onDrafts: (n: number) => void
  translate: boolean
  onTranslate: (on: boolean) => void
}) {
  const groups = useMemo(() => {
    const map = new Map<string, StudioFormat[]>()
    formats.forEach((f) => map.set(f.platformLabel, [...(map.get(f.platformLabel) ?? []), f]))
    return [...map.entries()]
  }, [formats])
  const [open, setOpen] = useState<string | null>(null)

  return (
    <div>
      <h4 className="text-xs font-semibold uppercase tracking-[0.15em] text-gray-300">Select formats ({chosen.length})</h4>
      <p className="mb-2 mt-1 text-xs text-gray-400">Each format has its own AI agent, written for that platform.</p>
      {loading && <p className="text-sm text-gray-400">Loading formats…</p>}
      <div className="divide-y divide-white/10 border-y border-white/10">
        {groups.map(([platform, list]) => {
          const picked = list.filter((f) => chosen.includes(f.key)).length
          const all = picked === list.length
          return (
            <div key={platform}>
              <button type="button" className="flex h-9 w-full items-center gap-2 text-sm text-gray-200" aria-expanded={open === platform}
                      onClick={() => setOpen(open === platform ? null : platform)}>
                {platform}
                {picked > 0 && <span className="text-xs text-brand-glow">{picked}</span>}
                <Icon name="down" className={`ml-auto h-3.5 w-3.5 text-gray-500 transition ${open === platform ? 'rotate-180' : ''}`} />
              </button>
              {open === platform && (
                <div className="space-y-1.5 pb-3 pl-1">
                  <button type="button" className="text-[11px] text-gray-200 hover:underline"
                          onClick={() => onChange(all ? chosen.filter((k) => !list.some((f) => f.key === k))
                            : [...chosen, ...list.map((f) => f.key).filter((k) => !chosen.includes(k))])}>
                    {all ? 'Clear all' : 'Select all'}
                  </button>
                  {list.map((f) => (
                    <label key={f.key} className="flex cursor-pointer items-center gap-2 rounded px-1 py-0.5 text-xs hover:bg-white/5">
                      <input type="checkbox" checked={chosen.includes(f.key)}
                             onChange={(e) => onChange(e.target.checked ? [...chosen, f.key] : chosen.filter((k) => k !== f.key))} />
                      <span className="text-gray-200">{f.label}</span>
                      <span className="ml-auto text-[10px] text-gray-500">{f.publishable ? 'via GoHighLevel' : 'copy & paste'}</span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          )
        })}
      </div>
      <div className="mt-4 grid grid-cols-2 gap-3">
        <div>
          <label className="label">Drafts each</label>
          <select className="input" value={drafts} onChange={(e) => onDrafts(Number(e.target.value))}>
            {[1, 2, 3].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </div>
        <label className="mt-6 flex items-center gap-2 text-xs text-gray-300">
          <input type="checkbox" checked={translate} onChange={(e) => onTranslate(e.target.checked)} /> Also in Spanish
        </label>
      </div>
    </div>
  )
}

function BatchProgress({ companyId, batchId }: { companyId: number; batchId: number | null }) {
  const recent = useQuery<ContentBatch[]>({ queryKey: ['studio', companyId, 'batches'], queryFn: () => listBatches(companyId) })
  const current = useQuery<ContentBatch>({
    queryKey: ['studio', 'batch', batchId],
    queryFn: () => getBatch(batchId as number),
    enabled: batchId != null,
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' || !q.state.data ? 1500 : false),
  })
  const shown = current.data ?? recent.data?.[0]
  if (!shown) return null
  const done = shown.completed + shown.failed
  const pct = shown.totalPieces ? Math.round((100 * done) / shown.totalPieces) : 0
  return (
    <GlassCard className="mt-5">
      <div className="flex items-center justify-between text-sm">
        <span className="font-semibold text-white">
          {shown.status === 'RUNNING' ? 'Writing drafts…' : shown.id === batchId ? 'Batch finished' : 'Last batch'}
        </span>
        <span className="text-xs text-gray-400">{done} / {shown.totalPieces}{shown.failed ? ` · ${shown.failed} failed` : ''}</span>
      </div>
      <div className="mt-2 h-2 overflow-hidden rounded bg-white/10">
        <div className="h-full bg-brand transition-all" style={{ width: `${pct}%` }} />
      </div>
      {shown.note && <p className="mt-2 text-xs text-amber-300">{shown.note}</p>}
      {shown.status !== 'RUNNING' && shown.completed > 0 && (
        <Link to={`/approvals?companyId=${companyId}`} className="mt-3 inline-block text-sm text-gray-200 underline">
          Review {shown.completed} draft{shown.completed === 1 ? '' : 's'} in Approvals
        </Link>
      )}
    </GlassCard>
  )
}
