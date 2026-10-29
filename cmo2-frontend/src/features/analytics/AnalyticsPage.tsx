import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import { kpiLabel, kpiValue } from '../integrations/kpiFormat'
import { fetchForecast, fetchPortfolio, fetchTimeseries, runWhatIf } from './analyticsApi'
import type {
  Company,
  Forecast,
  MetricAggregate,
  TimeseriesPoint,
  WhatIfResult,
} from '../../lib/types'

const METRICS = ['leads', 'cpl', 'roas', 'ctr', 'conversion_rate', 'appointments']

const TREND_STYLE: Record<string, string> = {
  up: 'text-emerald-300',
  down: 'text-red-300',
  flat: 'text-gray-300',
  insufficient_data: 'text-gray-500',
}

export default function AnalyticsPage() {
  const { data: companies } = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])

  const [companyId, setCompanyId] = useState<number | null>(null)
  const [metric, setMetric] = useState('leads')
  const [showForecast, setShowForecast] = useState(false)
  useEffect(() => {
    if (companyId === null && activeCompanies.length > 0) setCompanyId(activeCompanies[0].id)
  }, [activeCompanies, companyId])

  const { data: portfolio } = useQuery<MetricAggregate[]>({
    queryKey: ['analytics', 'portfolio'],
    queryFn: fetchPortfolio,
  })

  const { data: series, isLoading } = useQuery<TimeseriesPoint[]>({
    queryKey: ['analytics', 'timeseries', companyId, metric],
    queryFn: () => fetchTimeseries(companyId!, metric, 30),
    enabled: companyId !== null,
  })

  const { data: forecast } = useQuery<Forecast>({
    queryKey: ['analytics', 'forecast', companyId, metric],
    queryFn: () => fetchForecast(companyId!, metric, 6),
    enabled: companyId !== null && showForecast,
  })

  const chartData = useMemo(() => {
    if (showForecast && forecast) {
      const rows: { t: string; actual?: number; forecast?: number }[] = forecast.history.map((v, i) => ({
        t: String(i + 1),
        actual: v,
      }))
      if (rows.length) rows[rows.length - 1].forecast = forecast.history[forecast.history.length - 1]
      forecast.projection.forEach((p) => rows.push({ t: `+${p.step}`, forecast: p.value }))
      return rows
    }
    return (series ?? []).map((p) => ({
      t: new Date(p.capturedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
      actual: p.value,
    }))
  }, [showForecast, forecast, series])

  return (
    <div>
      <PageHeader
        title="Analytics"
        subtitle="Portfolio roll-up, KPI trends, forecasting, and What-If simulation."
        action={
          <select className="input w-48" value={companyId ?? ''} onChange={(e) => setCompanyId(Number(e.target.value))}>
            {activeCompanies.length === 0 && <option value="">No companies</option>}
            {activeCompanies.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        }
      />

      {/* Portfolio strip */}
      <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {(portfolio ?? METRICS.map((m) => ({ metric: m, value: 0, companiesWithData: 0 }))).map((agg) => (
          <GlassCard key={agg.metric} className="text-center">
            <div className="text-[11px] uppercase tracking-wide text-gray-400">{kpiLabel(agg.metric)}</div>
            <div className="mt-1 text-lg font-semibold text-white">{kpiValue(agg.metric, agg.value)}</div>
            <div className="mt-0.5 text-[10px] text-gray-500">{agg.companiesWithData} co.</div>
          </GlassCard>
        ))}
      </div>

      {/* Trend / forecast chart */}
      <GlassCard className="mb-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <h3 className="font-semibold text-white">{showForecast ? 'Forecast' : 'Trend'}</h3>
            <button
              onClick={() => setShowForecast((v) => !v)}
              className={[
                'rounded-md px-2.5 py-1 text-xs transition',
                showForecast ? 'bg-brand/30 text-white' : 'bg-white/5 text-gray-400 hover:text-gray-200',
              ].join(' ')}
            >
              {showForecast ? '◉ Forecast on' : '◌ Forecast'}
            </button>
          </div>
          <div className="flex flex-wrap gap-1.5">
            {METRICS.map((m) => (
              <button
                key={m}
                onClick={() => setMetric(m)}
                className={[
                  'rounded-md px-2.5 py-1 text-xs transition',
                  metric === m ? 'bg-brand/30 text-white' : 'bg-white/5 text-gray-400 hover:text-gray-200',
                ].join(' ')}
              >
                {kpiLabel(m)}
              </button>
            ))}
          </div>
        </div>

        {companyId === null ? (
          <p className="py-12 text-center text-sm text-gray-400">Add a company to see trends.</p>
        ) : isLoading ? (
          <p className="py-12 text-center text-sm text-gray-400">Loading…</p>
        ) : chartData.length === 0 ? (
          <p className="py-12 text-center text-sm text-gray-400">
            No data yet for {kpiLabel(metric)}. Sync an integration to populate KPIs.
          </p>
        ) : (
          <div className="h-72 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={chartData} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.08)" />
                <XAxis dataKey="t" stroke="#9ca3af" fontSize={11} />
                <YAxis stroke="#9ca3af" fontSize={11} width={48} />
                <Tooltip
                  contentStyle={{
                    background: 'rgba(17,24,39,0.95)',
                    border: '1px solid rgba(255,255,255,0.1)',
                    borderRadius: 8,
                    color: '#fff',
                  }}
                  formatter={(v: number) => kpiValue(metric, v)}
                />
                <Line type="monotone" dataKey="actual" name={kpiLabel(metric)} stroke="#6366f1" strokeWidth={2} dot={{ r: 2 }} />
                {showForecast && (
                  <Line type="monotone" dataKey="forecast" name="Forecast" stroke="#34d399"
                        strokeWidth={2} strokeDasharray="5 4" dot={{ r: 2 }} connectNulls />
                )}
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}

        {showForecast && forecast && (
          <div className="mt-3 flex flex-wrap items-center gap-3 text-xs">
            <span className={['font-medium uppercase', TREND_STYLE[forecast.trend] ?? 'text-gray-300'].join(' ')}>
              {forecast.trend.replace('_', ' ')}
            </span>
            <span className="text-gray-400">{forecast.narrative}</span>
            <span className="ml-auto text-gray-500">confidence {(forecast.confidence * 100).toFixed(0)}%</span>
          </div>
        )}
      </GlassCard>

      {/* What-If simulator */}
      {companyId !== null && <WhatIfCard companyId={companyId} />}
    </div>
  )
}

function WhatIfCard({ companyId }: { companyId: number }) {
  const [spend, setSpend] = useState('20')
  const [cpl, setCpl] = useState('-10')
  const [conv, setConv] = useState('10')

  const sim = useMutation<WhatIfResult, unknown, void>({
    mutationFn: () =>
      runWhatIf(companyId, {
        spendChangePct: num(spend),
        cplChangePct: num(cpl),
        conversionChangePct: num(conv),
      }),
  })

  return (
    <GlassCard>
      <h3 className="mb-1 font-semibold text-white">What-If simulator</h3>
      <p className="mb-4 text-xs text-gray-400">
        Project how budget, cost-per-lead, and conversion changes ripple through the funnel — from this company's latest KPIs.
      </p>
      <form
        onSubmit={(e) => { e.preventDefault(); sim.mutate() }}
        className="grid gap-3 sm:grid-cols-4"
      >
        <Field label="Spend change %" value={spend} onChange={setSpend} />
        <Field label="CPL change %" value={cpl} onChange={setCpl} />
        <Field label="Conversion change %" value={conv} onChange={setConv} />
        <div className="flex items-end">
          <button type="submit" className="btn-primary w-full" disabled={sim.isPending}>
            {sim.isPending ? 'Simulating…' : 'Simulate'}
          </button>
        </div>
      </form>

      {sim.isError && (
        <p className="mt-3 text-sm text-red-300">{apiErrorMessage(sim.error, 'Simulation failed')}</p>
      )}

      {sim.data && (
        <div className="mt-5">
          <p className="mb-3 text-sm text-gray-200">{sim.data.summary}</p>
          <div className="overflow-hidden rounded-lg border border-white/10">
            <table className="w-full text-sm">
              <thead className="bg-white/5 text-[11px] uppercase text-gray-400">
                <tr>
                  <th className="px-3 py-2 text-left">Metric</th>
                  <th className="px-3 py-2 text-right">Baseline</th>
                  <th className="px-3 py-2 text-right">Projected</th>
                  <th className="px-3 py-2 text-right">Change</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-white/5">
                {sim.data.metrics.map((m) => (
                  <tr key={m.metric}>
                    <td className="px-3 py-2 text-gray-300">{m.label}</td>
                    <td className="px-3 py-2 text-right text-gray-400">{kpiValue(m.metric, m.baseline)}</td>
                    <td className="px-3 py-2 text-right text-white">{kpiValue(m.metric, m.projected)}</td>
                    <td className={['px-3 py-2 text-right', m.changePct >= 0 ? 'text-emerald-300' : 'text-red-300'].join(' ')}>
                      {m.changePct >= 0 ? '+' : ''}{m.changePct.toFixed(1)}%
                    </td>
                  </tr>
                ))}
                <tr className="bg-white/5">
                  <td className="px-3 py-2 font-medium text-gray-200">Est. revenue</td>
                  <td className="px-3 py-2 text-right text-gray-400">${formatMoney(sim.data.baselineRevenue)}</td>
                  <td className="px-3 py-2 text-right text-white">${formatMoney(sim.data.projectedRevenue)}</td>
                  <td className={['px-3 py-2 text-right', sim.data.projectedRevenue >= sim.data.baselineRevenue ? 'text-emerald-300' : 'text-red-300'].join(' ')}>
                    {revPct(sim.data.baselineRevenue, sim.data.projectedRevenue)}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      )}
    </GlassCard>
  )
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <div>
      <label className="label">{label}</label>
      <input className="input" type="number" step="any" value={value} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}

function num(v: string): number | null {
  const n = Number(v)
  return v.trim() === '' || Number.isNaN(n) ? null : n
}

function formatMoney(v: number): string {
  return v.toLocaleString(undefined, { maximumFractionDigits: 0 })
}

function revPct(base: number, proj: number): string {
  if (base === 0) return '—'
  const p = ((proj - base) / base) * 100
  return `${p >= 0 ? '+' : ''}${p.toFixed(1)}%`
}
