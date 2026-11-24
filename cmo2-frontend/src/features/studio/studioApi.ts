import { api } from '../../lib/api'
import type {
  ContentBatch, ContentBatchInput, ContentSourceDetail, ContentSourceKind, ContentSourceSummary, StudioFormat,
} from '../../lib/types'

// Uploads and recordings can take a while: transcription and PDF reading happen before the answer.
const SLOW = { timeout: 5 * 60_000 }

export async function listStudioFormats(): Promise<StudioFormat[]> {
  return (await api.get<StudioFormat[]>('/api/studio/formats')).data
}

export async function listSources(companyId: number): Promise<ContentSourceSummary[]> {
  return (await api.get<ContentSourceSummary[]>(`/api/companies/${companyId}/studio/sources`)).data
}

export async function createSource(companyId: number, input: { title: string; kind: ContentSourceKind; body: string }): Promise<ContentSourceDetail> {
  return (await api.post<ContentSourceDetail>(`/api/companies/${companyId}/studio/sources`, input)).data
}

/** A .txt, .md, .pdf or .docx file, or an audio file (transcribed; the audio isn't kept). */
export async function uploadSource(companyId: number, file: File, title?: string): Promise<ContentSourceDetail> {
  const form = new FormData()
  form.append('file', file)
  if (title) form.append('title', title)
  return (await api.post<ContentSourceDetail>(`/api/companies/${companyId}/studio/sources/upload`, form, SLOW)).data
}

/** A voice recording made in the studio: transcribed, then only the text is kept. */
export async function recordSource(companyId: number, audio: Blob, title: string): Promise<ContentSourceDetail> {
  const ext = audio.type.includes('mp4') ? 'm4a' : audio.type.includes('ogg') ? 'ogg' : 'webm'
  const form = new FormData()
  form.append('audio', audio, `recording.${ext}`)
  form.append('title', title)
  return (await api.post<ContentSourceDetail>(`/api/companies/${companyId}/studio/sources/recording`, form, SLOW)).data
}

export async function linkSource(companyId: number, url: string, title?: string): Promise<ContentSourceDetail> {
  return (await api.post<ContentSourceDetail>(`/api/companies/${companyId}/studio/sources/link`, { url, title }, SLOW)).data
}

export async function starSource(sourceId: number, starred: boolean): Promise<ContentSourceSummary> {
  return (await api.put<ContentSourceSummary>(`/api/studio/sources/${sourceId}/star`, { starred })).data
}

export async function getSource(sourceId: number): Promise<ContentSourceDetail> {
  return (await api.get<ContentSourceDetail>(`/api/studio/sources/${sourceId}`)).data
}

export async function deleteSource(sourceId: number): Promise<void> {
  await api.delete(`/api/studio/sources/${sourceId}`)
}

export async function analyzeSource(sourceId: number, count: number): Promise<ContentSourceDetail> {
  return (await api.post<ContentSourceDetail>(`/api/studio/sources/${sourceId}/analyze`, null, { params: { count }, ...SLOW })).data
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
