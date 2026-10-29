import { api } from '../../lib/api'
import type { InviteInput, Role, TeamMember } from '../../lib/types'

export async function listTeam(): Promise<TeamMember[]> {
  return (await api.get<TeamMember[]>('/api/team')).data
}

export async function inviteMember(input: InviteInput): Promise<TeamMember> {
  return (await api.post<TeamMember>('/api/team/invite', input)).data
}

export async function changeRole(userId: number, role: Role): Promise<TeamMember> {
  return (await api.patch<TeamMember>(`/api/team/${userId}/role`, { role })).data
}

export async function deactivateMember(userId: number): Promise<TeamMember> {
  return (await api.post<TeamMember>(`/api/team/${userId}/deactivate`)).data
}

export async function reactivateMember(userId: number): Promise<TeamMember> {
  return (await api.post<TeamMember>(`/api/team/${userId}/reactivate`)).data
}

/** Downloads the tenant data export bundle as a JSON file. */
export async function exportTenantData(): Promise<void> {
  const res = await api.get('/api/tenant/export', { responseType: 'blob' })
  const url = window.URL.createObjectURL(res.data as Blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'cmo2-data-export.json'
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.URL.revokeObjectURL(url)
}
