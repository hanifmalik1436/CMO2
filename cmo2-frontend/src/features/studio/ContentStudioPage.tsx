import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import {
  analyzeSource, createSource, getBatch, getSource, listBatches, listSources, listStudioFormats, startBatch,
} from './studioApi'
import type {
  Company, ContentBatch, ContentIdea, ContentSourceDetail, ContentSourceKind, ContentSourceSummary, StudioFormat,
} from '../../lib/types'

const MAX_PIECES = 40

const TYPE_STYLE: Record<ContentIdea['type'], string> = {
  INSIGHT: 'bg-sky-500/15 text-sky-300',
  STORY: 'bg-violet-500/15 text-violet-300',
  TIP: 'bg-emerald-500/15 text-emerald-300',
  CONTRARIAN: 'bg-rose-500/15 text-rose-300',
  HOW_TO: 'bg-amber-500/15 text-amber-300',
  STAT: 'bg-cyan-500/15 text-cyan-300',
}

/**
 * Content Studio: turn a transcript, notes or an article into drafts for each chosen platform and
 * post type. Ideas come from the strategy AI; each draft from that format's own agent. Every draft
 * lands in Approvals.
 */
export default function ContentStudioPage() {
  const [params, setParams] = useSearchParams()
  const companies = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const active = (companies.data ?? []).filter((c) => c.status === 'ACTIVE')
  const companyId = Number(params.get('companyId')) || active[0]?.id

  return (
    <div className="pb-28">
      <PageHeader
        title="Content Studio"
        subtitle="Turn a transcript or notes into drafts for every platform. Every draft waits in Approvals."
      />
      {companies.isLoading && <GlassCard><p className="text-sm text-gray-400">Loading customers…</p></GlassCard>}
      {companies.isError && (
        <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(companies.error, 'Could not load customers')}</p></GlassCard>
      )}
      {companies.data && active.length === 0 && (
        <GlassCard><p className="text-sm text-gray-400">No customers yet. Enable one on the Customers page first.</p></GlassCard>
      )}
      {companyId && (
        <Studio
          key={companyId}
          companyId={companyId}
          companies={active}
          onCompany={(id) => setParams({ companyId: String(id) }, { replace: true })}
        />
      )}
    </div>
  )
}

function Studio({ companyId, companies, onCompany }: {
  companyId: number
  companies: Company[]
  onCompany: (id: number) => void
}) {
  const qc = useQueryClient()
  const sources = useQuery<ContentSourceSummary[]>({
    queryKey: ['studio', companyId, 'sources'],
    queryFn: () => listSources(companyId),
  })
  const formats = useQuery<StudioFormat[]>({ queryKey: ['studio', 'formats'], queryFn: listStudioFormats, staleTime: Infinity })
  const [sourceId, setSourceId] = useState<number | null>(null)
  const [adding, setAdding] = useState(false)
  const currentId = sourceId ?? sources.data?.[0]?.id ?? null

  const source = useQuery<ContentSourceDetail>({
    queryKey: ['studio', 'source', currentId],
    queryFn: () => getSource(currentId as number),
    enabled: currentId != null,
  })

  const [fullSource, setFullSource] = useState(true)
  const [ideaIds, setIdeaIds] = useState<number[]>([])
  const [chosenFormats, setChosenFormats] = useState<string[]>([])
  const [drafts, setDrafts] = useState(1)
  const [translate, setTranslate] = useState(false)
  const [count, setCount] = useState(8)
  const [batchId, setBatchId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setIdeaIds([])
    setFullSource(true)
  }, [currentId])

  const analyze = useMutation({
    mutationFn: () => analyzeSource(currentId as number, count),
    onSuccess: (d) => {
      qc.setQueryData(['studio', 'source', currentId], d)
      qc.invalidateQueries({ queryKey: ['studio', companyId, 'sources'] })
      setIdeaIds([])
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not find ideas')),
  })

  const generate = useMutation({
    mutationFn: () => startBatch(companyId, {
      sourceId: currentId as number,
      includeFullSource: fullSource,
      ideaIds,
      formats: chosenFormats,
      draftsPerFormat: drafts,
      translate,
    }),
    onSuccess: (b) => {
      setBatchId(b.id)
      qc.invalidateQueries({ queryKey: ['studio', companyId, 'batches'] })
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not start the batch')),
  })

  const ideas = source.data?.ideas ?? []
  const materials = ideaIds.length + (fullSource ? 1 : 0)
  const total = materials * chosenFormats.length * drafts
  const tooMany = total > MAX_PIECES

  return (
    <>
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <div>
          <label className="label">Customer</label>
          <select className="input" value={companyId} onChange={(e) => onCompany(Number(e.target.value))}>
            {companies.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>
        <div className="min-w-[260px] flex-1">
          <label className="label">Source</label>
          <select
            className="input"
            value={currentId ?? ''}
            onChange={(e) => setSourceId(Number(e.target.value))}
            disabled={!sources.data?.length}
          >
            {!sources.data?.length && <option value="">No sources yet</option>}
            {(sources.data ?? []).map((s) => (
              <option key={s.id} value={s.id}>{s.title} · {s.kind.toLowerCase()} · {s.ideaCount} ideas</option>
            ))}
          </select>
        </div>
        <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={null}>
          <button className="btn-ghost" onClick={() => setAdding((v) => !v)}>{adding ? 'Cancel' : '+ New source'}</button>
        </RequireRole>
      </div>

      {adding && (
        <NewSource
          companyId={companyId}
          onCreated={(id) => {
            setAdding(false)
            setSourceId(id)
            qc.invalidateQueries({ queryKey: ['studio', companyId, 'sources'] })
          }}
        />
      )}

      {sources.isLoading && <GlassCard><p className="text-sm text-gray-400">Loading sources…</p></GlassCard>}
      {sources.isError && <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(sources.error, 'Could not load sources')}</p></GlassCard>}
      {sources.data && sources.data.length === 0 && !adding && (
        <GlassCard>
          <p className="py-6 text-center text-sm text-gray-400">
            Add a source to start: paste a podcast or video transcript, meeting notes or an article.
          </p>
        </GlassCard>
      )}

      {source.data && (
        <div className="grid gap-6 xl:grid-cols-3">
          <div className="space-y-4 xl:col-span-2">
            <label className={`block cursor-pointer rounded-xl border p-4 ${fullSource ? 'border-brand/60 bg-brand/10' : 'border-white/10 bg-white/5'}`}>
              <div className="flex items-start gap-3">
                <input type="checkbox" className="mt-1" checked={fullSource} onChange={(e) => setFullSource(e.target.checked)} />
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="font-semibold text-white">Full {source.data.source.kind.toLowerCase()}</span>
                    <span className="rounded bg-white/10 px-1.5 py-0.5 text-[10px] uppercase text-gray-300">Raw source</span>
                  </div>
                  <p className="mt-1 text-xs text-gray-400">
                    Write directly from the whole {source.data.source.kind.toLowerCase()} ({source.data.source.characters.toLocaleString()} characters),
                    without picking ideas first.
                  </p>
                  <p className="mt-2 line-clamp-2 text-xs text-gray-500">{source.data.source.preview}</p>
                </div>
              </div>
            </label>

            <div className="flex flex-wrap items-center gap-3">
              <span className="text-xs font-semibold uppercase tracking-wide text-gray-400">
                Ideas ({ideaIds.length}/{ideas.length})
              </span>
              <div className="ml-auto flex items-center gap-3 text-xs">
                {ideas.length > 0 && (
                  <button
                    className="text-gray-300 hover:text-white"
                    onClick={() => setIdeaIds(ideaIds.length === ideas.length ? [] : ideas.map((i) => i.id))}
                  >
                    {ideaIds.length === ideas.length ? 'Clear all' : 'Select all'}
                  </button>
                )}
                <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={null}>
                  <label className="flex items-center gap-1 text-gray-400">
                    Count
                    <input
                      className="input h-8 w-16 py-0 text-xs"
                      type="number"
                      min={1}
                      max={20}
                      value={count}
                      onChange={(e) => setCount(Math.min(20, Math.max(1, Number(e.target.value) || 1)))}
                    />
                  </label>
                  <button className="btn-ghost h-8 py-0 text-xs" disabled={analyze.isPending} onClick={() => { setError(null); analyze.mutate() }}>
                    {analyze.isPending ? 'Finding ideas…' : ideas.length ? 'Re-analyze' : 'Find ideas'}
                  </button>
                </RequireRole>
              </div>
            </div>

            {ideas.length === 0 && !analyze.isPending && (
              <GlassCard><p className="text-sm text-gray-400">No ideas yet. Click <b>Find ideas</b> to pull the strongest angles out of this source.</p></GlassCard>
            )}
            {ideas.map((idea) => (
              <IdeaCard
                key={idea.id}
                idea={idea}
                selected={ideaIds.includes(idea.id)}
                onToggle={(on) => setIdeaIds(on ? [...ideaIds, idea.id] : ideaIds.filter((id) => id !== idea.id))}
              />
            ))}

            <BatchProgress companyId={companyId} batchId={batchId} />
          </div>

          <FormatPicker
            formats={formats.data ?? []}
            loading={formats.isLoading}
            chosen={chosenFormats}
            onChange={setChosenFormats}
            drafts={drafts}
            onDrafts={setDrafts}
            translate={translate}
            onTranslate={setTranslate}
          />
        </div>
      )}

      {source.data && (
        <div className="fixed inset-x-0 bottom-0 z-20 border-t border-white/10 bg-ink/95 px-6 py-3 backdrop-blur md:left-60">
          <div className="flex flex-wrap items-center gap-6 text-sm">
            <div><span className="font-semibold text-white">{materials}</span> <span className="text-gray-400">{materials === 1 ? 'source' : 'sources'} selected</span></div>
            <div><span className="font-semibold text-white">{chosenFormats.length}</span> <span className="text-gray-400">formats · {drafts} draft{drafts === 1 ? '' : 's'} each</span></div>
            <div><span className={`font-semibold ${tooMany ? 'text-red-300' : 'text-white'}`}>{total}</span> <span className="text-gray-400">pieces{translate ? ' (+ Spanish copies)' : ''}</span></div>
            {tooMany && <span className="text-xs text-red-300">One batch can make at most {MAX_PIECES}.</span>}
            {error && <span className="text-xs text-red-300">{error}</span>}
            <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={null}>
              <button
                className="btn-primary ml-auto"
                disabled={total === 0 || tooMany || generate.isPending}
                onClick={() => { setError(null); generate.mutate() }}
              >
                {generate.isPending ? 'Starting…' : 'Generate batch'}
              </button>
            </RequireRole>
          </div>
        </div>
      )}
    </>
  )
}

function IdeaCard({ idea, selected, onToggle }: { idea: ContentIdea; selected: boolean; onToggle: (on: boolean) => void }) {
  return (
    <label className={`block cursor-pointer rounded-xl border p-4 ${selected ? 'border-brand/60 bg-brand/10' : 'border-white/10 bg-white/5'}`}>
      <div className="flex items-start gap-3">
        <input type="checkbox" className="mt-1" checked={selected} onChange={(e) => onToggle(e.target.checked)} />
        <div className="min-w-0 space-y-2">
          <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase ${TYPE_STYLE[idea.type] ?? 'bg-white/10 text-gray-300'}`}>
            {idea.type.replace('_', ' ')}
          </span>
          <div className="font-semibold text-white">{idea.title}</div>
          {idea.summary && <p className="text-xs text-gray-400">{idea.summary}</p>}
          {idea.hooks.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {idea.hooks.map((h) => (
                <span key={h} className="rounded-md border border-white/10 bg-black/20 px-2 py-0.5 text-xs text-gray-300">{h}</span>
              ))}
            </div>
          )}
          {idea.quote && <p className="border-l-2 border-white/10 pl-3 text-xs italic text-gray-500">“{idea.quote}”</p>}
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
    <GlassCard className="h-fit xl:sticky xl:top-4">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-gray-400">Select formats ({chosen.length})</h3>
      <p className="mt-1 text-xs text-gray-500">Each format has its own AI agent, written for that platform.</p>
      {loading && <p className="mt-3 text-sm text-gray-400">Loading formats…</p>}
      <div className="mt-3 divide-y divide-white/5">
        {groups.map(([platform, list]) => {
          const picked = list.filter((f) => chosen.includes(f.key)).length
          return (
            <div key={platform} className="py-2">
              <button className="flex w-full items-center justify-between text-sm text-gray-200" onClick={() => setOpen(open === platform ? null : platform)}>
                <span>{platform}{picked > 0 && <span className="ml-2 text-xs text-brand-glow">{picked} selected</span>}</span>
                <span className="text-gray-500">{open === platform ? '▴' : '▾'}</span>
              </button>
              {open === platform && (
                <div className="mt-2 space-y-2 pl-1">
                  {list.map((f) => (
                    <label key={f.key} className="flex cursor-pointer items-start gap-2 text-xs">
                      <input
                        type="checkbox"
                        className="mt-0.5"
                        checked={chosen.includes(f.key)}
                        onChange={(e) => onChange(e.target.checked ? [...chosen, f.key] : chosen.filter((k) => k !== f.key))}
                      />
                      <span>
                        <span className="text-gray-200">{f.label}</span>
                        <span className="ml-2 text-gray-500">{f.publishable ? 'publishes via GoHighLevel' : 'copy & paste'}</span>
                      </span>
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
          <input type="checkbox" checked={translate} onChange={(e) => onTranslate(e.target.checked)} />
          Also in Spanish
        </label>
      </div>
    </GlassCard>
  )
}

function NewSource({ companyId, onCreated }: { companyId: number; onCreated: (id: number) => void }) {
  const [title, setTitle] = useState('')
  const [kind, setKind] = useState<ContentSourceKind>('TRANSCRIPT')
  const [body, setBody] = useState('')
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => createSource(companyId, { title: title.trim(), kind, body: body.trim() }),
    onSuccess: (d) => onCreated(d.source.id),
    onError: (err) => setError(apiErrorMessage(err, 'Could not save the source')),
  })
  return (
    <GlassCard className="mb-4">
      <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <label className="label">Title</label>
            <input className="input" required maxLength={200} placeholder="e.g. Podcast with the founder" value={title} onChange={(e) => setTitle(e.target.value)} />
          </div>
          <div>
            <label className="label">Kind</label>
            <select className="input" value={kind} onChange={(e) => setKind(e.target.value as ContentSourceKind)}>
              <option value="TRANSCRIPT">Transcript</option>
              <option value="NOTES">Notes</option>
              <option value="ARTICLE">Article</option>
            </select>
          </div>
        </div>
        <div>
          <label className="label">Text</label>
          <textarea className="input min-h-[180px] text-xs" required maxLength={100_000} placeholder="Paste the transcript, notes or article here"
                    value={body} onChange={(e) => setBody(e.target.value)} />
          <p className="mt-1 text-[11px] text-gray-500">{body.length.toLocaleString()} / 100,000 characters</p>
        </div>
        <div className="flex items-center gap-3">
          <button type="submit" className="btn-primary" disabled={save.isPending || !title.trim() || !body.trim()}>
            {save.isPending ? 'Saving…' : 'Save source'}
          </button>
          {error && <span className="text-sm text-red-300">{error}</span>}
        </div>
      </form>
    </GlassCard>
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
    <GlassCard>
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
