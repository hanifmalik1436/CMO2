import { api } from '../../lib/api'
import type { LoopIteration, LoopIterationDetail } from '../../lib/types'

export async function runCycle(companyId: number): Promise<LoopIteration> {
  return (await api.post<LoopIteration>(`/api/companies/${companyId}/loop/cycles`)).data
}

export async function listCycles(companyId: number): Promise<LoopIteration[]> {
  return (await api.get<LoopIteration[]>(`/api/companies/${companyId}/loop/cycles`)).data
}

export async function getCycle(iterationId: number): Promise<LoopIterationDetail> {
  return (await api.get<LoopIterationDetail>(`/api/loop/cycles/${iterationId}`)).data
}
