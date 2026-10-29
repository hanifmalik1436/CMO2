import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import {
  downloadReport,
  generateCompanyReport,
  generatePortfolioReport,
  listReports,
} from './reportsApi'
import type { Company, ReportSummary } from '../../lib/types'

export default function ReportsPage() {
  const qc = useQueryClient()
  const { data: companies } = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])

  const [companyId, setCompanyId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (companyId === null && activeCompanies.length > 0) setCompanyId(activeCompanies[0].id)
  }, [activeCompanies, companyId])

  const { data: reports, isLoading } = useQuery<ReportSummary[]>({
    queryKey: ['reports'],
    queryFn: () => listReports(50),
  })

  const refresh = () => qc.invalidateQueries({ queryKey: ['reports'] })

  const genCompany = useMutation({
    mutationFn: () => generateCompanyReport(companyId!),
    onSuccess: refresh,
    onError: (err) => setError(apiErrorMessage(err, 'Could not generate report')),
  })
  const genPortfolio = useMutation({
    mutationFn: () => generatePortfolioReport(),
    onSuccess: refresh,
    onError: (err) => setError(apiErrorMessage(err, 'Could not generate report')),
  })

  const companyName = (id: number | null) =>
    id === null ? 'Portfolio' : (companies?.find((c) => c.id === id)?.name ?? `Company ${id}`)

  return (
    <div>
      <PageHeader
        title="Reports"
        subtitle="Leadership-ready PDF reports. A portfolio report is also generated automatically every week."
        action={
          <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST', 'ANALYST']}>
            <div className="flex items-center gap-2">
              <select className="input w-44" value={companyId ?? ''} onChange={(e) => setCompanyId(Number(e.target.value))}>
                {activeCompanies.length === 0 && <option value="">No companies</option>}
                {activeCompanies.map((c) => (
                  <option key={c.id} value={c.id}>{c.name}</option>
                ))}
              </select>
              <button className="btn-primary" disabled={companyId === null || genCompany.isPending}
                onClick={() => { setError(null); genCompany.mutate() }}>
                {genCompany.isPending ? 'Generating…' : 'Generate'}
              </button>
              <button className="btn-ghost" disabled={genPortfolio.isPending}
                onClick={() => { setError(null); genPortfolio.mutate() }}>
                {genPortfolio.isPending ? '…' : 'Portfolio'}
              </button>
            </div>
          </RequireRole>
        }
      />

      {error && (
        <div className="mb-4 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">{error}</div>
      )}

      {isLoading ? (
        <GlassCard><p className="text-sm text-gray-400">Loading reports…</p></GlassCard>
      ) : (reports?.length ?? 0) === 0 ? (
        <GlassCard>
          <div className="py-8 text-center">
            <p className="text-sm text-gray-300">No reports yet.</p>
            <p className="mt-1 text-xs text-gray-500">Generate one above, or wait for the weekly auto-report.</p>
          </div>
        </GlassCard>
      ) : (
        <GlassCard>
          <div className="divide-y divide-white/5">
            {reports!.map((r) => (
              <div key={r.id} className="flex items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <div className="truncate text-sm font-medium text-white">{r.title}</div>
                  <div className="mt-0.5 text-xs text-gray-500">
                    {companyName(r.companyId)} · {r.type === 'WEEKLY' ? 'Weekly' : 'On-demand'}
                    {r.period && ` · ${r.period}`} · {new Date(r.createdAt).toLocaleString()}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <span className="rounded bg-white/10 px-1.5 py-0.5 text-[10px] uppercase text-gray-300">{r.format}</span>
                  <DownloadButton reportId={r.id} />
                </div>
              </div>
            ))}
          </div>
        </GlassCard>
      )}
    </div>
  )
}

function DownloadButton({ reportId }: { reportId: number }) {
  const dl = useMutation({ mutationFn: () => downloadReport(reportId) })
  return (
    <button className="btn-ghost text-xs" disabled={dl.isPending} onClick={() => dl.mutate()}>
      {dl.isPending ? 'Downloading…' : 'Download PDF'}
    </button>
  )
}
