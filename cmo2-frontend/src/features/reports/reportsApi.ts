import { api } from '../../lib/api'
import type { ReportSummary } from '../../lib/types'

export async function listReports(limit = 50): Promise<ReportSummary[]> {
  return (await api.get<ReportSummary[]>('/api/reports', { params: { limit } })).data
}

export async function generateCompanyReport(companyId: number): Promise<ReportSummary> {
  return (await api.post<ReportSummary>(`/api/companies/${companyId}/reports/generate`)).data
}

export async function generatePortfolioReport(): Promise<ReportSummary> {
  return (await api.post<ReportSummary>('/api/reports/portfolio/generate')).data
}

/** Downloads the PDF as a blob and triggers a browser save. */
export async function downloadReport(reportId: number): Promise<void> {
  const res = await api.get(`/api/reports/${reportId}/download`, { responseType: 'blob' })
  const url = window.URL.createObjectURL(res.data as Blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `report-${reportId}.pdf`
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.URL.revokeObjectURL(url)
}
