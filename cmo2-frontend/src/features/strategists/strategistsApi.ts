import { api } from '../../lib/api'
import type {
  ReviewGate,
  SessionDetail,
  StrategistAgent,
  StrategistMessage,
  StrategistSession,
} from '../../lib/types'

export async function listAgents(): Promise<StrategistAgent[]> {
  return (await api.get<StrategistAgent[]>('/api/strategists/agents')).data
}

export async function listSessions(companyId: number): Promise<StrategistSession[]> {
  return (await api.get<StrategistSession[]>(`/api/companies/${companyId}/strategists/sessions`)).data
}

export async function startSession(companyId: number, agentCode: string): Promise<StrategistSession> {
  return (await api.post<StrategistSession>(`/api/companies/${companyId}/strategists/sessions`, { agentCode })).data
}

export async function getSession(sessionId: number): Promise<SessionDetail> {
  return (await api.get<SessionDetail>(`/api/strategists/sessions/${sessionId}`)).data
}

export async function sendMessage(sessionId: number, content: string): Promise<StrategistMessage> {
  return (await api.post<StrategistMessage>(`/api/strategists/sessions/${sessionId}/messages`, { content })).data
}

export async function produceDeliverables(sessionId: number): Promise<StrategistSession> {
  return (await api.post<StrategistSession>(`/api/strategists/sessions/${sessionId}/deliverables`)).data
}

export async function listGates(companyId: number): Promise<ReviewGate[]> {
  return (await api.get<ReviewGate[]>(`/api/companies/${companyId}/strategists/gates`)).data
}

export async function approveGate(companyId: number, gateCode: string, notes?: string): Promise<ReviewGate> {
  return (await api.post<ReviewGate>(`/api/companies/${companyId}/strategists/gates/${gateCode}/approve`, { notes })).data
}
