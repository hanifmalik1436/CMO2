import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import { listCycles, runCycle } from './loopApi'
import { loopTopic, subscribe } from '../../lib/ws'
import { useSession } from '../../stores/session'
import type { Company, LoopIteration, LoopUpdate } from '../../lib/types'

const STAGES = [
  { no: 1, name: 'Innovation', blurb: 'AI strategy generation' },
  { no: 2, name: 'Learning', blurb: 'Pattern analysis over KPIs' },
  { no: 3, name: 'Documentation', blurb: 'Auto-doc to Notion/Linear' },
  { no: 4, name: 'Reporting', blurb: 'Gap identification vs targets' },
  { no: 5, name: 'Optimization', blurb: 'Apply fixes, fire alerts' },
] as const

type StageState = { status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'; progressPct: number }
const initialStages = (): Record<number, StageState> =>
  Object.fromEntries(STAGES.map((s) => [s.no, { status: 'PENDING', progressPct: 0 }]))

export default function StrangeLoopPage() {
  const qc = useQueryClient()
  const tenantId = useSession((s) => s.user?.tenantId)

  const { data: companies } = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])

  const [companyId, setCompanyId] = useState<number | null>(null)
  useEffect(() => {
    if (companyId === null && activeCompanies.length > 0) setCompanyId(activeCompanies[0].id)
  }, [activeCompanies, companyId])

  const { data: iterations } = useQuery<LoopIteration[]>({
    queryKey: ['loop', 'cycles', companyId],
    queryFn: () => listCycles(companyId!),
    enabled: companyId !== null,
  })

  // Live stage state driven by the WebSocket stream.
  const [stages, setStages] = useState<Record<number, StageState>>(initialStages)
  const [statusLine, setStatusLine] = useState<string>('Idle')
  const activeCompanyRef = useRef<number | null>(null)
  activeCompanyRef.current = companyId

  useEffect(() => {
    if (!tenantId) return
    const dispose = subscribe<LoopUpdate>(loopTopic(tenantId), (u) => {
      // Only react to the company currently in view.
      if (u.companyId !== activeCompanyRef.current) return
      setStatusLine(`#${u.iterationNo} · ${u.message}`)

      setStages((prev) => {
        const next = { ...prev }
        if (u.type === 'STAGE_STARTED' && u.stageNo) next[u.stageNo] = { status: 'RUNNING', progressPct: 40 }
        if (u.type === 'STAGE_COMPLETED' && u.stageNo) next[u.stageNo] = { status: 'COMPLETED', progressPct: 100 }
        if (u.type === 'ITERATION_COMPLETED') STAGES.forEach((s) => (next[s.no] = { status: 'COMPLETED', progressPct: 100 }))
        if (u.type === 'ITERATION_FAILED') {
          for (const s of STAGES) if (next[s.no].status === 'RUNNING') next[s.no] = { status: 'FAILED', progressPct: 100 }
        }
        return next
      })

      if (u.type === 'ITERATION_COMPLETED' || u.type === 'ITERATION_FAILED') {
        qc.invalidateQueries({ queryKey: ['loop', 'cycles', activeCompanyRef.current] })
      }
    })
    return dispose
  }, [tenantId, qc])

  const start = useMutation({
    mutationFn: () => runCycle(companyId!),
    onMutate: () => {
      setStages(initialStages())
      setStatusLine('Starting cycle…')
    },
    onError: (err) => setStatusLine(apiErrorMessage(err, 'Failed to start cycle')),
  })

  const overall = Math.round(
    (Object.values(stages).reduce((a, s) => a + (s.status === 'COMPLETED' ? 1 : 0), 0) / STAGES.length) * 100,
  )

  return (
    <div>
      <PageHeader
        title="Strange Loop OS"
        subtitle="The 5-stage self-improving cycle: Innovation → Learning → Documentation → Reporting → Optimization."
        action={
          <div className="flex items-center gap-2">
            <select
              className="input w-48"
              value={companyId ?? ''}
              onChange={(e) => setCompanyId(Number(e.target.value))}
            >
              {activeCompanies.length === 0 && <option value="">No companies</option>}
              {activeCompanies.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
            <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']}>
              <button
                className="btn-primary"
                disabled={companyId === null || start.isPending}
                onClick={() => start.mutate()}
              >
                {start.isPending ? 'Running…' : '▶ Run Cycle'}
              </button>
            </RequireRole>
          </div>
        }
      />

      {activeCompanies.length === 0 ? (
        <GlassCard>
          <p className="text-sm text-gray-300">Add a company first — the loop runs per company.</p>
        </GlassCard>
      ) : (
        <>
          <GlassCard className="mb-6">
            <div className="mb-4 flex items-center justify-between">
              <span className="text-sm font-medium text-white">Live cycle</span>
              <span className="text-xs text-gray-400">{statusLine}</span>
            </div>

            <div className="mb-4 h-2 w-full overflow-hidden rounded-full bg-white/10">
              <div className="h-full rounded-full bg-brand transition-all duration-500" style={{ width: `${overall}%` }} />
            </div>

            <div className="grid gap-3 sm:grid-cols-5">
              {STAGES.map((s) => {
                const st = stages[s.no]
                return (
                  <div key={s.no} className="rounded-xl border border-white/10 bg-white/5 p-3">
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-semibold text-white">{s.no}. {s.name}</span>
                      <StatusDot status={st.status} />
                    </div>
                    <p className="mt-1 text-[10px] leading-tight text-gray-500">{s.blurb}</p>
                    <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-white/10">
                      <div
                        className={`h-full rounded-full transition-all duration-500 ${barColor(st.status)}`}
                        style={{ width: `${st.progressPct}%` }}
                      />
                    </div>
                  </div>
                )
              })}
            </div>
          </GlassCard>

          <GlassCard>
            <h2 className="mb-3 text-sm font-semibold text-white">Iteration history</h2>
            {!iterations || iterations.length === 0 ? (
              <p className="text-sm text-gray-400">No cycles yet. Click <span className="text-brand-glow">Run Cycle</span>.</p>
            ) : (
              <div className="divide-y divide-white/5">
                {iterations.map((it) => (
                  <div key={it.id} className="flex items-center justify-between py-2 text-sm">
                    <span className="text-gray-200">Cycle #{it.iterationNo}</span>
                    <span className="text-xs text-gray-500">{it.triggerType}</span>
                    <span className={statusClass(it.status)}>{it.status}</span>
                  </div>
                ))}
              </div>
            )}
          </GlassCard>
        </>
      )}
    </div>
  )
}

function StatusDot({ status }: { status: StageState['status'] }) {
  const color =
    status === 'COMPLETED' ? 'bg-emerald-400' : status === 'RUNNING' ? 'bg-brand-glow animate-pulse'
      : status === 'FAILED' ? 'bg-red-400' : 'bg-gray-600'
  return <span className={`h-2 w-2 rounded-full ${color}`} />
}

function barColor(status: StageState['status']): string {
  if (status === 'COMPLETED') return 'bg-emerald-400'
  if (status === 'RUNNING') return 'bg-brand-glow'
  if (status === 'FAILED') return 'bg-red-400'
  return 'bg-transparent'
}

function statusClass(status: string): string {
  const base = 'rounded-full px-2 py-0.5 text-[10px] uppercase '
  if (status === 'COMPLETED') return base + 'bg-emerald-500/15 text-emerald-300'
  if (status === 'RUNNING') return base + 'bg-brand/20 text-brand-glow'
  if (status === 'FAILED') return base + 'bg-red-500/15 text-red-300'
  return base + 'bg-white/10 text-gray-300'
}
