import { api } from '../../lib/api'
import type {
  AiInteraction, CustomerProfile, CustomerProfileInput, CycleRecord, Experiment, GhlAgencyStatus, GhlSubAccount,
  KpiTrend, LearningRecord, LoopSchedule, LoopScheduleInput,
} from '../../lib/types'

export async function getAgencyStatus(): Promise<GhlAgencyStatus> {
  return (await api.get<GhlAgencyStatus>('/api/ghl/agency')).data
}

export async function connectAgency(code: string): Promise<GhlAgencyStatus> {
  return (await api.post<GhlAgencyStatus>('/api/ghl/agency/connect', { code })).data
}

export async function disconnectAgency(): Promise<void> {
  await api.delete('/api/ghl/agency')
}

export async function listSubAccounts(): Promise<GhlSubAccount[]> {
  return (await api.get<GhlSubAccount[]>('/api/ghl/sub-accounts')).data
}

export async function enableSubAccount(locationId: string): Promise<GhlSubAccount> {
  return (await api.post<GhlSubAccount>(`/api/ghl/sub-accounts/${encodeURIComponent(locationId)}/enable`)).data
}

export async function getCustomerProfile(companyId: number): Promise<CustomerProfile> {
  return (await api.get<CustomerProfile>(`/api/companies/${companyId}/profile`)).data
}

export async function updateCustomerProfile(companyId: number, input: CustomerProfileInput): Promise<CustomerProfile> {
  return (await api.put<CustomerProfile>(`/api/companies/${companyId}/profile`, input)).data
}

export async function getIdentityBlock(companyId: number): Promise<string> {
  return (await api.get<{ companyId: number; text: string }>(`/api/companies/${companyId}/identity`)).data.text
}

export async function getWeekTrends(companyId: number): Promise<KpiTrend[]> {
  return (await api.get<KpiTrend[]>(`/api/companies/${companyId}/kpis/week`)).data
}

export async function getLoopSchedule(companyId: number): Promise<LoopSchedule> {
  return (await api.get<LoopSchedule>(`/api/companies/${companyId}/loop/schedule`)).data
}

export async function setLoopSchedule(companyId: number, input: LoopScheduleInput): Promise<LoopSchedule> {
  return (await api.put<LoopSchedule>(`/api/companies/${companyId}/loop/schedule`, input)).data
}

export async function listLoopSchedules(): Promise<LoopSchedule[]> {
  return (await api.get<LoopSchedule[]>('/api/loop/schedules')).data
}

export async function getCycleRecords(companyId: number, limit = 5): Promise<CycleRecord[]> {
  return (await api.get<CycleRecord[]>(`/api/companies/${companyId}/loop/memory`, { params: { limit } })).data
}

export async function getAiInteractions(companyId: number, limit = 20): Promise<AiInteraction[]> {
  return (await api.get<AiInteraction[]>(`/api/companies/${companyId}/ai-interactions`, { params: { limit } })).data
}

export async function getLearning(companyId: number, limit = 3): Promise<LearningRecord[]> {
  return (await api.get<LearningRecord[]>(`/api/companies/${companyId}/learning`, { params: { limit } })).data
}

export async function getExperiments(companyId: number, limit = 20): Promise<Experiment[]> {
  return (await api.get<Experiment[]>(`/api/companies/${companyId}/experiments`, { params: { limit } })).data
}

export async function approveExperiment(id: number, note?: string): Promise<Experiment> {
  return (await api.post<Experiment>(`/api/experiments/${id}/approve`, { note })).data
}

export async function rejectExperiment(id: number, reason: string): Promise<Experiment> {
  return (await api.post<Experiment>(`/api/experiments/${id}/reject`, { reason })).data
}
