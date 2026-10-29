import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import {
  approveGate,
  getSession,
  listAgents,
  listGates,
  produceDeliverables,
  sendMessage,
  startSession,
} from './strategistsApi'
import type { Company, ReviewGate, SessionDetail, StrategistAgent } from '../../lib/types'

const PHASES = [
  { n: 1, label: 'Insights & Brand' },
  { n: 2, label: 'Distribution' },
  { n: 3, label: 'Big Media & Content' },
  { n: 4, label: 'Sales & Operations' },
]

export default function StrategistsPage() {
  const qc = useQueryClient()
  const { data: companies } = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])

  const [companyId, setCompanyId] = useState<number | null>(null)
  useEffect(() => {
    if (companyId === null && activeCompanies.length > 0) setCompanyId(activeCompanies[0].id)
  }, [activeCompanies, companyId])

  const [sessionId, setSessionId] = useState<number | null>(null)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<string | null>(null)

  const { data: agents } = useQuery<StrategistAgent[]>({ queryKey: ['agents'], queryFn: listAgents })
  const { data: gates } = useQuery<ReviewGate[]>({
    queryKey: ['gates', companyId],
    queryFn: () => listGates(companyId!),
    enabled: companyId !== null,
  })
  const { data: detail } = useQuery<SessionDetail>({
    queryKey: ['session', sessionId],
    queryFn: () => getSession(sessionId!),
    enabled: sessionId !== null,
  })

  const start = useMutation({
    mutationFn: (code: string) => startSession(companyId!, code),
    onSuccess: (s) => setSessionId(s.id),
    onError: (e) => setError(apiErrorMessage(e, 'Could not start session')),
  })
  const chat = useMutation({
    mutationFn: () => sendMessage(sessionId!, draft.trim()),
    onSuccess: () => {
      setDraft('')
      qc.invalidateQueries({ queryKey: ['session', sessionId] })
    },
    onError: (e) => setError(apiErrorMessage(e, 'Message failed')),
  })
  const deliver = useMutation({
    mutationFn: () => produceDeliverables(sessionId!),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['session', sessionId] }),
    onError: (e) => setError(apiErrorMessage(e, 'Could not produce deliverables')),
  })
  const approve = useMutation({
    mutationFn: (code: string) => approveGate(companyId!, code),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['gates', companyId] }),
    onError: (e) => setError(apiErrorMessage(e, 'Approval failed')),
  })

  const agentsByPhase = (phase: number) => (agents ?? []).filter((a) => a.phase === phase)
  const chatMessages = (detail?.messages ?? []).filter((m) => m.role !== 'SYSTEM')

  return (
    <div>
      <PageHeader
        title="AI Strategist Council"
        subtitle="17+ specialist agents across 4 phases, gated by human review RG-1…RG-4. Approved output feeds Strange Loop Stage 1."
        action={
          <select className="input w-48" value={companyId ?? ''} onChange={(e) => { setCompanyId(Number(e.target.value)); setSessionId(null) }}>
            {activeCompanies.length === 0 && <option value="">No companies</option>}
            {activeCompanies.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        }
      />

      {error && (
        <div className="mb-4 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">{error}</div>
      )}

      {activeCompanies.length === 0 ? (
        <GlassCard><p className="text-sm text-gray-300">Add a company first.</p></GlassCard>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[18rem_1fr]">
          {/* Agents + gates */}
          <div className="space-y-4">
            <GlassCard>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">Council</div>
              <div className="space-y-3">
                {PHASES.map((p) => (
                  <div key={p.n}>
                    <div className="mb-1 text-[11px] font-medium text-brand-glow">Phase {p.n} · {p.label}</div>
                    <div className="space-y-1">
                      {agentsByPhase(p.n).map((a) => (
                        <RequireRole key={a.code} allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={
                          <div className="px-2 py-1 text-xs text-gray-500">{a.name}</div>
                        }>
                          <button
                            className="block w-full rounded px-2 py-1 text-left text-xs text-gray-300 hover:bg-white/5"
                            disabled={companyId === null || start.isPending}
                            onClick={() => start.mutate(a.code)}
                          >
                            {a.name}
                          </button>
                        </RequireRole>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </GlassCard>

            <GlassCard>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">Review gates</div>
              <div className="space-y-2">
                {(gates ?? []).map((g) => (
                  <div key={g.gateCode} className="flex items-center justify-between text-sm">
                    <span className="text-gray-300">{g.gateCode} <span className="text-gray-500">· P{g.phase}</span></span>
                    {g.status === 'APPROVED' ? (
                      <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] uppercase text-emerald-300">approved</span>
                    ) : (
                      <RequireRole allow={['OWNER', 'ADMIN']} fallback={
                        <span className="rounded-full bg-white/10 px-2 py-0.5 text-[10px] uppercase text-gray-400">pending</span>
                      }>
                        <button className="btn-ghost px-2 py-1 text-xs" disabled={approve.isPending} onClick={() => approve.mutate(g.gateCode)}>
                          Approve
                        </button>
                      </RequireRole>
                    )}
                  </div>
                ))}
              </div>
            </GlassCard>
          </div>

          {/* Session */}
          <div className="space-y-4">
            {!detail ? (
              <GlassCard>
                <div className="py-12 text-center text-sm text-gray-400">
                  Pick an agent on the left to start a session.
                </div>
              </GlassCard>
            ) : (
              <>
                <GlassCard>
                  <div className="mb-3 flex items-center justify-between">
                    <div>
                      <h3 className="font-semibold text-white">{detail.session.agentName}</h3>
                      <p className="text-xs text-gray-500">{detail.session.status}</p>
                    </div>
                    <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']}>
                      <button className="btn-primary" disabled={deliver.isPending} onClick={() => deliver.mutate()}>
                        {deliver.isPending ? 'Working…' : 'Produce deliverables'}
                      </button>
                    </RequireRole>
                  </div>

                  <div className="max-h-72 space-y-3 overflow-auto">
                    {chatMessages.length === 0 && <p className="text-sm text-gray-500">No messages yet — ask the agent anything.</p>}
                    {chatMessages.map((m) => (
                      <div key={m.id} className={m.role === 'USER' ? 'text-right' : ''}>
                        <div className={`inline-block max-w-[85%] rounded-xl px-3 py-2 text-sm ${
                          m.role === 'USER' ? 'bg-brand/20 text-white' : 'bg-white/5 text-gray-200'
                        }`}>
                          <pre className="whitespace-pre-wrap font-sans">{m.content}</pre>
                        </div>
                      </div>
                    ))}
                  </div>

                  <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']}>
                    <form
                      className="mt-3 flex gap-2"
                      onSubmit={(e) => { e.preventDefault(); if (draft.trim()) chat.mutate() }}
                    >
                      <input className="input" placeholder="Ask the strategist…" value={draft} onChange={(e) => setDraft(e.target.value)} />
                      <button type="submit" className="btn-primary" disabled={chat.isPending || !draft.trim()}>
                        {chat.isPending ? '…' : 'Send'}
                      </button>
                    </form>
                  </RequireRole>
                </GlassCard>

                {detail.session.deliverablesJson && (
                  <GlassCard>
                    <h2 className="mb-3 text-sm font-semibold text-white">Deliverables</h2>
                    <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded-lg bg-black/30 p-4 text-xs text-gray-200">
                      {detail.session.deliverablesJson}
                    </pre>
                  </GlassCard>
                )}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
