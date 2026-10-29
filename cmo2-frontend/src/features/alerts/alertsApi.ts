import { api } from '../../lib/api'
import type { Alert, AlertRule, CreateRuleInput } from '../../lib/types'

export async function listRules(companyId: number): Promise<AlertRule[]> {
  return (await api.get<AlertRule[]>(`/api/companies/${companyId}/alert-rules`)).data
}

export async function createRule(companyId: number, input: CreateRuleInput): Promise<AlertRule> {
  return (await api.post<AlertRule>(`/api/companies/${companyId}/alert-rules`, input)).data
}

export async function deleteRule(ruleId: number): Promise<void> {
  await api.delete(`/api/alert-rules/${ruleId}`)
}

export async function listAlerts(limit = 50): Promise<Alert[]> {
  return (await api.get<Alert[]>('/api/alerts', { params: { limit } })).data
}

export async function acknowledgeAlert(alertId: number): Promise<Alert> {
  return (await api.post<Alert>(`/api/alerts/${alertId}/acknowledge`)).data
}

export async function simulateSpike(
  companyId: number,
  metric: string,
  value: number,
): Promise<Alert[]> {
  return (
    await api.post<Alert[]>(`/api/companies/${companyId}/alerts/simulate`, { metric, value })
  ).data
}
