import { api } from '../../lib/api'
import type { Company, CompanyInput } from '../../lib/types'

export async function listCompanies(): Promise<Company[]> {
  return (await api.get<Company[]>('/api/companies')).data
}

export async function createCompany(input: CompanyInput): Promise<Company> {
  return (await api.post<Company>('/api/companies', input)).data
}

export async function archiveCompany(id: number): Promise<void> {
  await api.delete(`/api/companies/${id}`)
}
