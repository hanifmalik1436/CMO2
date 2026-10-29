import { api } from '../../lib/api'
import type {
  ConnectInput,
  LatestKpi,
  PlatformConnection,
  PlatformInfo,
  SyncJob,
  SyncTriggerResult,
} from '../../lib/types'

export async function listPlatforms(): Promise<PlatformInfo[]> {
  return (await api.get<PlatformInfo[]>('/api/integrations/platforms')).data
}

export async function listConnections(companyId: number): Promise<PlatformConnection[]> {
  return (await api.get<PlatformConnection[]>(`/api/companies/${companyId}/integrations`)).data
}

export async function connectPlatform(companyId: number, input: ConnectInput): Promise<PlatformConnection> {
  return (await api.post<PlatformConnection>(`/api/companies/${companyId}/integrations`, input)).data
}

export async function testConnection(connectionId: number): Promise<PlatformConnection> {
  return (await api.post<PlatformConnection>(`/api/integrations/${connectionId}/test`)).data
}

export async function syncConnection(connectionId: number): Promise<SyncTriggerResult> {
  return (await api.post<SyncTriggerResult>(`/api/integrations/${connectionId}/sync`)).data
}

export async function disconnect(connectionId: number): Promise<void> {
  await api.delete(`/api/integrations/${connectionId}`)
}

export async function listSyncJobs(connectionId: number): Promise<SyncJob[]> {
  return (await api.get<SyncJob[]>(`/api/integrations/${connectionId}/sync-jobs`)).data
}

export async function latestKpis(companyId: number): Promise<LatestKpi[]> {
  return (await api.get<LatestKpi[]>(`/api/companies/${companyId}/kpis/latest`)).data
}
