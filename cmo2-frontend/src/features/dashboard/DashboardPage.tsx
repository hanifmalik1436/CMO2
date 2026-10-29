import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../../lib/api'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import { useSession } from '../../stores/session'
import { latestKpis } from '../integrations/integrationsApi'
import { kpiLabel, kpiValue } from '../integrations/kpiFormat'
import type { Company, LatestKpi } from '../../lib/types'

const METRIC_ORDER = ['leads', 'cpl', 'roas', 'conversion_rate', 'appointments']

export default function DashboardPage() {
  const user = useSession((s) => s.user)

  const { data: companies, isLoading } = useQuery<Company[]>({
    queryKey: ['companies'],
    queryFn: async () => (await api.get<Company[]>('/api/companies')).data,
  })

  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])
  const primary = activeCompanies[0]

  // Live KPIs for the primary company; refetches every 30s so scheduled syncs surface automatically.
  const { data: kpis } = useQuery<LatestKpi[]>({
    queryKey: ['kpis', 'latest', primary?.id],
    queryFn: () => latestKpis(primary!.id),
    enabled: !!primary,
    refetchInterval: 30_000,
  })

  const byMetric = useMemo(() => {
    const map = new Map<string, LatestKpi>()
    ;(kpis ?? []).forEach((k) => map.set(k.metric, k))
    return map
  }, [kpis])

  const hasData = (kpis?.length ?? 0) > 0

  return (
    <div>
      <PageHeader
        title={`Welcome${user?.fullName ? `, ${user.fullName.split(' ')[0]}` : ''}`}
        subtitle={primary ? `Live KPIs for ${primary.name}` : 'Your unified marketing command center.'}
      />

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {METRIC_ORDER.map((metric) => {
          const kpi = byMetric.get(metric)
          return (
            <GlassCard key={metric}>
              <div className="text-xs uppercase tracking-wide text-gray-400">{kpiLabel(metric)}</div>
              <div className="mt-2 text-2xl font-semibold text-white">
                {kpi ? kpiValue(metric, kpi.value) : '—'}
              </div>
              <div className="mt-1 text-[11px] text-gray-500">
                {kpi ? `updated ${new Date(kpi.capturedAt).toLocaleTimeString()}` : 'no data yet'}
              </div>
            </GlassCard>
          )
        })}
      </div>

      <div className="mt-6 grid gap-4 lg:grid-cols-3">
        <GlassCard className="lg:col-span-2">
          <h2 className="mb-3 text-sm font-semibold text-white">Getting started</h2>
          <ol className="space-y-2 text-sm text-gray-300">
            <li>1. Add a <span className="text-brand-glow">Company</span> (a brand you manage).</li>
            <li>2. Connect <span className="text-brand-glow">GoHighLevel</span> in Integrations and hit <span className="text-brand-glow">Sync now</span>.</li>
            <li>3. Watch KPIs populate above (they also refresh on a schedule).</li>
            <li>4. Run a <span className="text-brand-glow">Strange Loop</span> cycle.</li>
          </ol>
          {!hasData && primary && (
            <p className="mt-4 text-xs text-gray-500">
              No KPI data for {primary.name} yet — connect a platform and run a sync to light up this dashboard.
            </p>
          )}
        </GlassCard>

        <GlassCard>
          <h2 className="mb-3 text-sm font-semibold text-white">Portfolio</h2>
          {isLoading ? (
            <p className="text-sm text-gray-400">Loading…</p>
          ) : (
            <div className="space-y-1 text-sm text-gray-300">
              <div className="flex justify-between"><span>Companies</span><span className="text-white">{companies?.length ?? 0}</span></div>
              <div className="flex justify-between"><span>Active</span><span className="text-white">{activeCompanies.length}</span></div>
            </div>
          )}
        </GlassCard>
      </div>
    </div>
  )
}
