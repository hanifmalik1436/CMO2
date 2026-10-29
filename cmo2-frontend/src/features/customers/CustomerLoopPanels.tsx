import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { runCycle } from '../strangeLoop/loopApi'
import { getAiInteractions, getCycleRecords, getLoopSchedule, setLoopSchedule } from './customersApi'
import type { AiInteraction, CadenceKey, CycleRecord, LoopSchedule } from '../../lib/types'

const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

/** Human description of a schedule, e.g. "Weekly on Monday at 09:00". */
export function describeSchedule(s: Pick<LoopSchedule, 'cadence' | 'timeOfDay' | 'dayOfWeek' | 'dayOfMonth'>): string {
  switch (s.cadence) {
    case 'DAILY': return `Daily at ${s.timeOfDay}`
    case 'WEEKLY': return `Weekly on ${DAYS[(s.dayOfWeek ?? 1) - 1]} at ${s.timeOfDay}`
    case 'MONTHLY': return `Monthly on day ${s.dayOfMonth} at ${s.timeOfDay}`
    default: return 'Off'
  }
}

export function ScheduleCard({ companyId }: { companyId: number }) {
  const qc = useQueryClient()
  const { data, isLoading, isError, error } = useQuery<LoopSchedule>({
    queryKey: ['customers', companyId, 'schedule'],
    queryFn: () => getLoopSchedule(companyId),
  })
  const [cadence, setCadence] = useState<CadenceKey>('OFF')
  const [time, setTime] = useState('09:00')
  const [dow, setDow] = useState(1)
  const [dom, setDom] = useState(1)
  const [message, setMessage] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    if (!data) return
    setCadence(data.cadence)
    setTime(data.timeOfDay ?? '09:00')
    setDow(data.dayOfWeek ?? 1)
    setDom(data.dayOfMonth ?? 1)
  }, [data])

  const save = useMutation({
    mutationFn: () => setLoopSchedule(companyId, {
      cadence,
      timeOfDay: cadence === 'OFF' ? undefined : time,
      dayOfWeek: cadence === 'WEEKLY' ? dow : undefined,
      dayOfMonth: cadence === 'MONTHLY' ? dom : undefined,
    }),
    onSuccess: (s) => {
      qc.setQueryData(['customers', companyId, 'schedule'], s)
      qc.invalidateQueries({ queryKey: ['loop', 'schedules'] })
      setMessage('Saved')
    },
    onError: (err) => setFailure(apiErrorMessage(err, 'Could not save the schedule')),
  })
  const runNow = useMutation({
    mutationFn: () => runCycle(companyId),
    onSuccess: () => setMessage('Cycle started. Its record appears below when it finishes.'),
    onError: (err) => setFailure(apiErrorMessage(err, 'Could not start a cycle')),
  })

  return (
    <GlassCard>
      <h3 className="font-semibold text-white">Strange Loop schedule</h3>
      {isLoading && <p className="mt-2 text-sm text-gray-400">Loading schedule…</p>}
      {isError && <p className="mt-2 text-sm text-red-300">{apiErrorMessage(error, 'Could not load the schedule')}</p>}
      {data && (
        <>
          <p className="mt-1 text-xs text-gray-400">
            {describeSchedule(data)} · {data.timezone}
            {data.nextRunAt && <> · next run {new Date(data.nextRunAt).toLocaleString()}</>}
            {data.lastScheduledRunAt && <> · last scheduled run {new Date(data.lastScheduledRunAt).toLocaleString()}</>}
          </p>
          <RequireRole allow={['OWNER', 'ADMIN']} fallback={null}>
            <form
              className="mt-3 flex flex-wrap items-end gap-3"
              onSubmit={(e) => { e.preventDefault(); setMessage(null); setFailure(null); save.mutate() }}
            >
              <div>
                <label className="label">How often</label>
                <select className="input" value={cadence} onChange={(e) => setCadence(e.target.value as CadenceKey)}>
                  <option value="OFF">Off</option>
                  <option value="DAILY">Daily</option>
                  <option value="WEEKLY">Weekly</option>
                  <option value="MONTHLY">Monthly</option>
                </select>
              </div>
              {cadence === 'WEEKLY' && (
                <div>
                  <label className="label">On</label>
                  <select className="input" value={dow} onChange={(e) => setDow(Number(e.target.value))}>
                    {DAYS.map((d, i) => <option key={d} value={i + 1}>{d}</option>)}
                  </select>
                </div>
              )}
              {cadence === 'MONTHLY' && (
                <div>
                  <label className="label">Day of month</label>
                  <select className="input" value={dom} onChange={(e) => setDom(Number(e.target.value))}>
                    {Array.from({ length: 28 }, (_, i) => i + 1).map((d) => <option key={d} value={d}>{d}</option>)}
                  </select>
                </div>
              )}
              {cadence !== 'OFF' && (
                <div>
                  <label className="label">At ({data.timezone})</label>
                  <input className="input" type="time" value={time} onChange={(e) => setTime(e.target.value)} required />
                </div>
              )}
              <button type="submit" className="btn-primary" disabled={save.isPending}>
                {save.isPending ? 'Saving…' : 'Save schedule'}
              </button>
              <button type="button" className="btn-ghost" disabled={runNow.isPending}
                      onClick={() => { setMessage(null); setFailure(null); runNow.mutate() }}>
                {runNow.isPending ? 'Starting…' : 'Run a cycle now'}
              </button>
            </form>
            <p className="mt-2 text-xs text-gray-500">The timezone comes from the customer profile (GoHighLevel fills it in).</p>
          </RequireRole>
          {message && <p className="mt-2 text-xs text-emerald-300">{message}</p>}
          {failure && <p className="mt-2 text-sm text-red-300">{failure}</p>}
        </>
      )}
    </GlassCard>
  )
}

export function MemoryCard({ companyId }: { companyId: number }) {
  const { data, isLoading, isError, error } = useQuery<CycleRecord[]>({
    queryKey: ['customers', companyId, 'memory'],
    queryFn: () => getCycleRecords(companyId, 5),
    refetchInterval: 15_000,
  })
  const [open, setOpen] = useState<number | null>(null)
  const latest = data?.[0]

  return (
    <GlassCard>
      <h3 className="font-semibold text-white">Cycle memory</h3>
      <p className="mt-1 text-xs text-gray-400">
        What each cycle did and decided. The next cycle starts from the latest record plus anything you changed in Notion,
        Confluence or Linear since.
      </p>
      <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST', 'ANALYST']} fallback={null}>
        {isLoading && <p className="mt-3 text-sm text-gray-400">Loading cycles…</p>}
        {isError && <p className="mt-3 text-sm text-red-300">{apiErrorMessage(error, 'Could not load cycle records')}</p>}
        {data && data.length === 0 && <p className="mt-3 text-sm text-gray-400">No cycles have run for this customer yet.</p>}
        {latest && (
          <div className="mt-3 space-y-3 text-sm">
            <p className="text-gray-200">{latest.summary}</p>
            <List title="Waiting on you" items={latest.tasksPending.map((t) => `${t.title}. ${t.detail}`)} />
            <List title="Your notes CMO² read back" items={latest.operatorNotes.map((n) => n.text)} />
            <Links links={latest.links} />
          </div>
        )}
        {data && data.length > 1 && (
          <div className="mt-4 divide-y divide-white/5 text-sm">
            {data.slice(1).map((r) => (
              <div key={r.iterationId} className="py-2">
                <button className="text-left text-gray-300 hover:text-white" onClick={() => setOpen(open === r.iterationId ? null : r.iterationId)}>
                  Cycle {r.iterationNo} · {new Date(r.periodEnd).toLocaleDateString()}
                </button>
                {open === r.iterationId && (
                  <div className="mt-2 space-y-2">
                    <p className="text-gray-400">{r.summary}</p>
                    <List title="Decisions" items={r.decisions} />
                    <Links links={r.links} />
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </RequireRole>
    </GlassCard>
  )
}

function List({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) return null
  return (
    <div>
      <div className="text-[11px] uppercase text-gray-500">{title}</div>
      <ul className="mt-1 list-disc space-y-1 pl-5 text-gray-300">
        {items.map((i) => <li key={i}>{i}</li>)}
      </ul>
    </div>
  )
}

function Links({ links }: { links: Record<string, string> }) {
  const entries = Object.entries(links)
  if (entries.length === 0) return null
  return (
    <div className="flex gap-3 text-xs">
      {entries.map(([tool, url]) => (
        <a key={tool} href={url} target="_blank" rel="noreferrer" className="text-gray-300 underline">
          {tool === 'notion' ? 'Notion page' : tool === 'confluence' ? 'Confluence page' : tool}
        </a>
      ))}
    </div>
  )
}

export function AiCallsCard({ companyId }: { companyId: number }) {
  const { data, isLoading, isError, error } = useQuery<AiInteraction[]>({
    queryKey: ['customers', companyId, 'ai-calls'],
    queryFn: () => getAiInteractions(companyId, 20),
  })
  const [open, setOpen] = useState<number | null>(null)

  return (
    <GlassCard>
      <h3 className="font-semibold text-white">AI calls</h3>
      <p className="mt-1 text-xs text-gray-400">Every prompt sent for this customer and what came back. Kept 180 days.</p>
      <RequireRole allow={['OWNER', 'ADMIN']} fallback={<p className="mt-3 text-sm text-gray-400">Owner or admin only.</p>}>
        {isLoading && <p className="mt-3 text-sm text-gray-400">Loading…</p>}
        {isError && <p className="mt-3 text-sm text-red-300">{apiErrorMessage(error, 'Could not load AI calls')}</p>}
        {data && data.length === 0 && <p className="mt-3 text-sm text-gray-400">No AI calls for this customer yet.</p>}
        <div className="mt-3 divide-y divide-white/5 text-xs">
          {(data ?? []).map((c) => (
            <div key={c.id} className="py-2">
              <button className="flex w-full justify-between gap-3 text-left text-gray-300 hover:text-white"
                      onClick={() => setOpen(open === c.id ? null : c.id)}>
                <span>{c.kind}{c.stage ? ` · ${c.stage.toLowerCase()}` : ''}</span>
                <span className="text-gray-500">
                  {(c.inputTokens + c.outputTokens).toLocaleString()} tokens · {new Date(c.createdAt).toLocaleString()}
                </span>
              </button>
              {open === c.id && (
                <div className="mt-2 space-y-2">
                  <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded bg-black/30 p-2 text-gray-400">{c.prompt}</pre>
                  <pre className="max-h-60 overflow-auto whitespace-pre-wrap rounded bg-black/30 p-2 text-gray-200">{c.response}</pre>
                </div>
              )}
            </div>
          ))}
        </div>
      </RequireRole>
    </GlassCard>
  )
}
