import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import { kpiLabel } from '../integrations/kpiFormat'
import {
  acknowledgeAlert,
  createRule,
  deleteRule,
  listAlerts,
  listRules,
  simulateSpike,
} from './alertsApi'
import type {
  Alert,
  AlertChannelType,
  AlertOperator,
  AlertRule,
  AlertSeverity,
  Company,
} from '../../lib/types'

const METRICS = ['leads', 'cpl', 'roas', 'ctr', 'conversion_rate', 'appointments']
const OPERATORS: { value: AlertOperator; label: string }[] = [
  { value: 'GT', label: '> greater than' },
  { value: 'GTE', label: '≥ at least' },
  { value: 'LT', label: '< less than' },
  { value: 'LTE', label: '≤ at most' },
]
const SEVERITIES: AlertSeverity[] = ['INFO', 'WARNING', 'CRITICAL']
const CHANNELS: AlertChannelType[] = ['EMAIL', 'SLACK', 'SMS', 'IN_APP']

const SEV_STYLE: Record<AlertSeverity, string> = {
  INFO: 'bg-sky-500/15 text-sky-300',
  WARNING: 'bg-amber-500/15 text-amber-300',
  CRITICAL: 'bg-red-500/15 text-red-300',
}

export default function AlertsPage() {
  const qc = useQueryClient()
  const { data: companies } = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])

  const [companyId, setCompanyId] = useState<number | null>(null)
  useEffect(() => {
    if (companyId === null && activeCompanies.length > 0) setCompanyId(activeCompanies[0].id)
  }, [activeCompanies, companyId])

  const { data: rules } = useQuery<AlertRule[]>({
    queryKey: ['alert-rules', companyId],
    queryFn: () => listRules(companyId!),
    enabled: companyId !== null,
  })

  const { data: alerts } = useQuery<Alert[]>({
    queryKey: ['alerts'],
    queryFn: () => listAlerts(50),
    refetchInterval: 15000,
  })

  const refreshAlerts = () => qc.invalidateQueries({ queryKey: ['alerts'] })

  const simulate = useMutation({
    mutationFn: (metric: string) => simulateSpike(companyId!, metric, 999),
    onSuccess: refreshAlerts,
  })

  return (
    <div>
      <PageHeader
        title="Alerts"
        subtitle="Threshold rules across email, Slack, SMS & in-app. The engine sweeps KPIs continuously and fires within minutes."
        action={
          <select className="input w-48" value={companyId ?? ''} onChange={(e) => setCompanyId(Number(e.target.value))}>
            {activeCompanies.length === 0 && <option value="">No companies</option>}
            {activeCompanies.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        }
      />

      {activeCompanies.length === 0 ? (
        <GlassCard><p className="text-sm text-gray-300">Add a company first.</p></GlassCard>
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="space-y-6">
            <RequireRole allow={['OWNER', 'ADMIN']}>
              {companyId !== null && <RuleForm companyId={companyId} />}
            </RequireRole>

            <GlassCard>
              <div className="mb-3 flex items-center justify-between">
                <h3 className="font-semibold text-white">Rules</h3>
                <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']}>
                  <button
                    className="btn-ghost text-xs"
                    disabled={simulate.isPending || !rules?.length}
                    onClick={() => simulate.mutate(rules?.[0]?.metric ?? 'cpl')}
                    title="Ingest a spike for the first rule's metric and evaluate immediately"
                  >
                    {simulate.isPending ? 'Simulating…' : '⚡ Simulate spike'}
                  </button>
                </RequireRole>
              </div>
              {(rules?.length ?? 0) === 0 ? (
                <p className="py-6 text-center text-sm text-gray-400">No rules yet.</p>
              ) : (
                <div className="space-y-2">
                  {rules!.map((r) => <RuleRow key={r.id} rule={r} companyId={companyId!} />)}
                </div>
              )}
            </GlassCard>
          </div>

          <GlassCard>
            <h3 className="mb-3 font-semibold text-white">Alert center</h3>
            {(alerts?.length ?? 0) === 0 ? (
              <p className="py-6 text-center text-sm text-gray-400">No alerts fired. All clear.</p>
            ) : (
              <div className="space-y-2">
                {alerts!.map((a) => <AlertRow key={a.id} alert={a} onChanged={refreshAlerts} />)}
              </div>
            )}
          </GlassCard>
        </div>
      )}
    </div>
  )
}

function RuleForm({ companyId }: { companyId: number }) {
  const qc = useQueryClient()
  const [metric, setMetric] = useState('cpl')
  const [operator, setOperator] = useState<AlertOperator>('GT')
  const [threshold, setThreshold] = useState('50')
  const [severity, setSeverity] = useState<AlertSeverity>('WARNING')
  const [channels, setChannels] = useState<AlertChannelType[]>(['EMAIL', 'IN_APP'])
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)

  function toggle(c: AlertChannelType) {
    setChannels((prev) => (prev.includes(c) ? prev.filter((x) => x !== c) : [...prev, c]))
  }

  const create = useMutation({
    mutationFn: () =>
      createRule(companyId, {
        metric,
        operator,
        threshold: Number(threshold),
        severity,
        channels: channels.map((type) => ({
          type,
          target: type === 'EMAIL' ? email.trim() || undefined : undefined,
        })),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['alert-rules', companyId] })
      setError(null)
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not create rule')),
  })

  return (
    <GlassCard>
      <h3 className="mb-3 font-semibold text-white">New rule</h3>
      <form
        onSubmit={(e) => { e.preventDefault(); setError(null); create.mutate() }}
        className="space-y-3"
      >
        {error && (
          <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">{error}</div>
        )}
        <div className="grid grid-cols-3 gap-2">
          <div>
            <label className="label">Metric</label>
            <select className="input" value={metric} onChange={(e) => setMetric(e.target.value)}>
              {METRICS.map((m) => <option key={m} value={m}>{kpiLabel(m)}</option>)}
            </select>
          </div>
          <div>
            <label className="label">Condition</label>
            <select className="input" value={operator} onChange={(e) => setOperator(e.target.value as AlertOperator)}>
              {OPERATORS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            </select>
          </div>
          <div>
            <label className="label">Threshold</label>
            <input className="input" type="number" step="any" required value={threshold} onChange={(e) => setThreshold(e.target.value)} />
          </div>
        </div>

        <div>
          <label className="label">Severity</label>
          <div className="flex gap-1.5">
            {SEVERITIES.map((s) => (
              <button key={s} type="button" onClick={() => setSeverity(s)}
                className={['rounded-md px-2.5 py-1 text-xs', severity === s ? SEV_STYLE[s] : 'bg-white/5 text-gray-400'].join(' ')}>
                {s}
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="label">Channels</label>
          <div className="flex flex-wrap gap-1.5">
            {CHANNELS.map((c) => (
              <button key={c} type="button" onClick={() => toggle(c)}
                className={['rounded-md px-2.5 py-1 text-xs', channels.includes(c) ? 'bg-brand/30 text-white' : 'bg-white/5 text-gray-400'].join(' ')}>
                {c}
              </button>
            ))}
          </div>
        </div>

        {channels.includes('EMAIL') && (
          <div>
            <label className="label">Email recipient</label>
            <input className="input" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="alerts@yourbrand.com" />
          </div>
        )}

        <button type="submit" className="btn-primary" disabled={create.isPending}>
          {create.isPending ? 'Saving…' : 'Create rule'}
        </button>
      </form>
    </GlassCard>
  )
}

function RuleRow({ rule, companyId }: { rule: AlertRule; companyId: number }) {
  const qc = useQueryClient()
  const remove = useMutation({
    mutationFn: () => deleteRule(rule.id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['alert-rules', companyId] }),
  })
  const opSign = { GT: '>', GTE: '≥', LT: '<', LTE: '≤' }[rule.operator]
  return (
    <div className="flex items-center justify-between rounded-lg bg-white/5 px-3 py-2 text-sm">
      <div>
        <span className="text-white">{kpiLabel(rule.metric)} {opSign} {rule.threshold}</span>
        <span className={['ml-2 rounded px-1.5 py-0.5 text-[10px]', SEV_STYLE[rule.severity]].join(' ')}>{rule.severity}</span>
      </div>
      <RequireRole allow={['OWNER', 'ADMIN']}>
        <button className="text-xs text-gray-400 hover:text-red-300" disabled={remove.isPending} onClick={() => remove.mutate()}>
          Delete
        </button>
      </RequireRole>
    </div>
  )
}

function AlertRow({ alert, onChanged }: { alert: Alert; onChanged: () => void }) {
  const ack = useMutation({ mutationFn: () => acknowledgeAlert(alert.id), onSuccess: onChanged })
  const dispatched: string[] = (() => {
    try { return alert.dispatchedJson ? JSON.parse(alert.dispatchedJson) : [] } catch { return [] }
  })()
  return (
    <div className="rounded-lg bg-white/5 px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <span className={['rounded px-1.5 py-0.5 text-[10px]', SEV_STYLE[alert.severity]].join(' ')}>{alert.severity}</span>
        <span className="flex-1 truncate text-sm text-gray-200">{alert.message}</span>
        {alert.status === 'FIRED' ? (
          <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST', 'ANALYST']}>
            <button className="btn-ghost text-xs" disabled={ack.isPending} onClick={() => ack.mutate()}>Ack</button>
          </RequireRole>
        ) : (
          <span className="text-[10px] uppercase text-emerald-300">{alert.status}</span>
        )}
      </div>
      <div className="mt-1 flex items-center gap-2 text-[10px] text-gray-500">
        {alert.firedAt && <span>{new Date(alert.firedAt).toLocaleString()}</span>}
        {dispatched.length > 0 && <span>· sent via {dispatched.join(', ')}</span>}
      </div>
    </div>
  )
}
