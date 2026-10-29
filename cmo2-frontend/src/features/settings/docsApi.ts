import { api } from '../../lib/api'
import type { DocConnection, DocProviderKey, DocTargets, SaveDocConnectionInput } from '../../lib/types'

export async function listDocConnections(): Promise<DocConnection[]> {
  return (await api.get<DocConnection[]>('/api/docs/connections')).data
}

export async function saveDocConnection(provider: DocProviderKey, input: SaveDocConnectionInput): Promise<DocConnection> {
  return (await api.put<DocConnection>(`/api/docs/connections/${provider.toLowerCase()}`, input)).data
}

export async function testDocConnection(provider: DocProviderKey): Promise<DocConnection> {
  return (await api.post<DocConnection>(`/api/docs/connections/${provider.toLowerCase()}/test`)).data
}

export async function removeDocConnection(provider: DocProviderKey): Promise<void> {
  await api.delete(`/api/docs/connections/${provider.toLowerCase()}`)
}

export async function listDocTargets(): Promise<DocTargets[]> {
  return (await api.get<DocTargets[]>('/api/docs/targets')).data
}

export async function provisionDocTargets(companyId: number): Promise<DocTargets> {
  return (await api.post<DocTargets>(`/api/companies/${companyId}/doc-targets/provision`)).data
}
