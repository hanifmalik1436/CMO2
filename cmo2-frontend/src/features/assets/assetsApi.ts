import { api } from '../../lib/api'
import type {
  AiUsage, ApprovalItem, Asset, AssetDeployment, AssetStatus, GenerateResult, PublishOptions, SocialAccounts,
} from '../../lib/types'

export async function generateAsset(companyId: number, type: string, brief: string): Promise<GenerateResult> {
  return (await api.post<GenerateResult>(`/api/companies/${companyId}/assets`, { type, brief })).data
}

export async function listAssets(companyId: number): Promise<Asset[]> {
  return (await api.get<Asset[]>(`/api/companies/${companyId}/assets`)).data
}

/** Publishes an approved asset; live GHL social posts are scheduled in the customer's Social Planner. */
export async function publishAsset(assetId: number, options: PublishOptions = {}): Promise<AssetDeployment> {
  return (await api.post<AssetDeployment>(`/api/assets/${assetId}/deploy`, { platform: 'GOHIGHLEVEL', ...options })).data
}

export async function listApprovals(params: { companyId?: number; status?: AssetStatus } = {}): Promise<ApprovalItem[]> {
  return (await api.get<ApprovalItem[]>('/api/approvals', { params })).data
}

export async function editAssetContent(assetId: number, body: string): Promise<Asset> {
  return (await api.put<Asset>(`/api/assets/${assetId}/content`, { body })).data
}

export async function approveAsset(assetId: number, note?: string): Promise<Asset> {
  return (await api.post<Asset>(`/api/assets/${assetId}/approve`, { note })).data
}

export async function rejectAsset(assetId: number, reason: string): Promise<Asset> {
  return (await api.post<Asset>(`/api/assets/${assetId}/reject`, { reason })).data
}

export async function listSocialAccounts(companyId: number): Promise<SocialAccounts> {
  return (await api.get<SocialAccounts>(`/api/companies/${companyId}/social-accounts`)).data
}

export async function currentUsage(): Promise<AiUsage> {
  return (await api.get<AiUsage>('/api/usage/current')).data
}

/** Pull the readable "body" out of an asset's content JSON. */
export function assetBody(asset: Asset): string {
  if (!asset.contentJson) return ''
  try {
    const parsed = JSON.parse(asset.contentJson) as { body?: string }
    return parsed.body ?? asset.contentJson
  } catch {
    return asset.contentJson
  }
}
