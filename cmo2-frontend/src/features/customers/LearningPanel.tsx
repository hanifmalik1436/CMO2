import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { approveExperiment, getExperiments, getLearning, rejectExperiment } from './customersApi'
import type { Experiment, LearningRecord } from '../../lib/types'

const ACTION_LABEL: Record<Experiment['action'], string> = {
  DRAFT_SOCIAL_POST: 'Social post draft',
  UPDATE_CUSTOM_VALUE: 'Change a GHL custom value',
  SUGGESTION: 'Suggestion for you',
}

const STATUS_STYLE: Record<string, string> = {
  PROPOSED: 'bg-amber-500/15 text-amber-300',
  APPLIED: 'bg-sky-500/15 text-sky-300',
  MEASURED: 'bg-emerald-500/15 text-emerald-300',
  REJECTED: 'bg-white/10 text-gray-400',
  FAILED: 'bg-red-500/15 text-red-300',
}

/** Stage 2's latest reading and Stage 5's experiments for one customer, with the owner's decisions. */
export function LearningPanel({ companyId }: { companyId: number }) {
  const learning = useQuery<LearningRecord[]>({
    queryKey: ['customers', companyId, 'learning'],
    queryFn: () => getLearning(companyId, 1),
  })
  const experiments = useQuery<Experiment[]>({
    queryKey: ['customers', companyId, 'experiments'],
    queryFn: () => getExperiments(companyId, 20),
  })
  const latest = learning.data?.[0]

  return (
    <GlassCard>
      <h3 className="font-semibold text-white">Learning &amp; experiments</h3>
      <p className="mt-1 text-xs text-gray-400">
        What the last cycle read in the figures, and the experiments it proposed. Nothing reaches the customer's
        audience without your approval.
      </p>
      <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST', 'ANALYST']} fallback={null}>
        {learning.isLoading && <p className="mt-3 text-sm text-gray-400">Loading…</p>}
        {learning.isError && <p className="mt-3 text-sm text-red-300">{apiErrorMessage(learning.error, 'Could not load learning')}</p>}
        {learning.data && !latest && <p className="mt-3 text-sm text-gray-400">No cycles have run for this customer yet.</p>}
        {latest && (
          <div className="mt-3 space-y-2 text-sm">
            <div className="text-[11px] uppercase text-gray-500">
              Cycle {latest.iterationNo} · {latest.source === 'ai' ? 'read by AI' : latest.source === 'rules' ? 'plain comparison' : 'no figures yet'}
              {' · '}confidence {Math.round(latest.confidence * 100)}%
            </div>
            <ul className="list-disc space-y-1 pl-5 text-gray-300">
              {latest.observations.map((o) => <li key={o}>{o}</li>)}
            </ul>
            {latest.hypotheses.length > 0 && (
              <ul className="space-y-1 text-xs text-gray-400">
                {latest.hypotheses.map((h) => (
                  <li key={h.statement}>
                    Hypothesis: {h.statement} <span className="text-gray-500">({h.metric} {h.expectedDirection})</span>
                  </li>
                ))}
              </ul>
            )}
            {latest.note && <p className="text-xs text-gray-500">{latest.note}</p>}
          </div>
        )}

        <div className="mt-5 text-[11px] uppercase text-gray-500">Experiments</div>
        {experiments.isLoading && <p className="mt-2 text-sm text-gray-400">Loading…</p>}
        {experiments.isError && (
          <p className="mt-2 text-sm text-red-300">{apiErrorMessage(experiments.error, 'Could not load experiments')}</p>
        )}
        {experiments.data && experiments.data.length === 0 && (
          <p className="mt-2 text-sm text-gray-400">None yet. They're proposed once the customer has figures to test against.</p>
        )}
        <div className="mt-2 divide-y divide-white/5">
          {(experiments.data ?? []).map((e) => <ExperimentRow key={e.id} companyId={companyId} experiment={e} />)}
        </div>
      </RequireRole>
    </GlassCard>
  )
}

function ExperimentRow({ companyId, experiment: e }: { companyId: number; experiment: Experiment }) {
  const qc = useQueryClient()
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['customers', companyId, 'experiments'] })
  const approve = useMutation({
    mutationFn: () => approveExperiment(e.id),
    onSuccess: refresh,
    onError: (err) => setError(apiErrorMessage(err, 'Could not approve')),
  })
  const reject = useMutation({
    mutationFn: () => rejectExperiment(e.id, reason.trim()),
    onSuccess: refresh,
    onError: (err) => setError(apiErrorMessage(err, 'Could not turn it down')),
  })
  const busy = approve.isPending || reject.isPending

  return (
    <div className="py-3 text-sm">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="text-white">{e.hypothesis}</div>
          <div className="text-xs text-gray-500">
            {ACTION_LABEL[e.action]} · expects {e.metricLabel} {e.expectedDirection}
            {e.baselineValue != null && <> from {e.baselineValue}</>}
          </div>
        </div>
        <span className={`rounded-full px-2 py-0.5 text-[10px] uppercase ${STATUS_STYLE[e.status] ?? ''}`}>{e.status}</span>
      </div>

      {e.action === 'SUGGESTION' && e.params.suggestion && <p className="mt-1 text-xs text-gray-300">{e.params.suggestion}</p>}
      {e.action === 'UPDATE_CUSTOM_VALUE' && (
        <p className="mt-1 text-xs text-gray-300">
          Set <span className="font-mono">{e.params.customValueName ?? e.params.customValueId}</span> to: “{e.params.newValue}”
        </p>
      )}
      {e.resultSummary && <p className="mt-1 text-xs text-gray-400">{e.resultSummary}</p>}
      {e.decisionNote && <p className="mt-1 text-xs text-gray-500">Note: {e.decisionNote}</p>}

      {e.status === 'PROPOSED' && (
        <RequireRole allow={['OWNER', 'ADMIN']} fallback={null}>
          {e.action === 'DRAFT_SOCIAL_POST' ? (
            <Link to={`/approvals?companyId=${companyId}`} className="mt-2 inline-block text-xs text-gray-300 underline">
              Review its draft in Approvals
            </Link>
          ) : (
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <button className="btn-primary h-8 py-0 text-xs" disabled={busy} onClick={() => { setError(null); approve.mutate() }}>
                {approve.isPending ? 'Working…' : e.action === 'UPDATE_CUSTOM_VALUE' ? 'Apply in GoHighLevel' : 'Mark done'}
              </button>
              {!rejecting ? (
                <button className="btn-ghost h-8 py-0 text-xs" disabled={busy} onClick={() => setRejecting(true)}>Turn down</button>
              ) : (
                <form className="flex flex-1 gap-2" onSubmit={(ev) => { ev.preventDefault(); setError(null); reject.mutate() }}>
                  <input className="input h-8 flex-1 py-0 text-xs" placeholder="Why?" maxLength={1000}
                         value={reason} onChange={(ev) => setReason(ev.target.value)} />
                  <button type="submit" className="btn-ghost h-8 py-0 text-xs" disabled={busy || !reason.trim()}>Confirm</button>
                </form>
              )}
            </div>
          )}
        </RequireRole>
      )}
      {error && <p className="mt-1 text-xs text-red-300">{error}</p>}
    </div>
  )
}
