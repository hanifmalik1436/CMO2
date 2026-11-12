import { api } from '../../lib/api'
import type {
  ContentBatch, ContentBatchInput, ContentSourceDetail, ContentSourceKind, ContentSourceSummary, StudioFormat,
} from '../../lib/types'

export async function listStudioFormats(): Promise<StudioFormat[]> {
  return (await api.get<StudioFormat[]>('/api/studio/formats')).data
}

export async function listSources(companyId: number): Promise<ContentSourceSummary[]> {
  return (await api.get<ContentSourceSummary[]>(`/api/companies/${companyId}/studio/sources`)).data
}

export async function createSource(companyId: number, input: { title: string; kind: ContentSourceKind; body: string }): Promise<ContentSourceDetail> {
  return (await api.post<ContentSourceDetail>(`/api/companies/${companyId}/studio/sources`, input)).data
}

export async function getSource(sourceId: number): Promise<ContentSourceDetail> {
  return (await api.get<ContentSourceDetail>(`/api/studio/sources/${sourceId}`)).data
}

export async function deleteSource(sourceId: number): Promise<void> {
  await api.delete(`/api/studio/sources/${sourceId}`)
}

export async function analyzeSource(sourceId: number, count: number): Promise<ContentSourceDetail> {
  return (await api.post<ContentSourceDetail>(`/api/studio/sources/${sourceId}/analyze`, null, { params: { count } })).data
}

export async function startBatch(companyId: number, input: ContentBatchInput): Promise<ContentBatch> {
  return (await api.post<ContentBatch>(`/api/companies/${companyId}/studio/batches`, input)).data
}

export async function getBatch(batchId: number): Promise<ContentBatch> {
  return (await api.get<ContentBatch>(`/api/studio/batches/${batchId}`)).data
}

export async function listBatches(companyId: number): Promise<ContentBatch[]> {
  return (await api.get<ContentBatch[]>(`/api/companies/${companyId}/studio/batches`, { params: { limit: 5 } })).data
}
