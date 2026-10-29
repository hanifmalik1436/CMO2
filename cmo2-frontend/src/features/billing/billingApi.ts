import { api } from '../../lib/api'
import type { BillingSummary, CheckoutResponse } from '../../lib/types'

export async function fetchBillingSummary(): Promise<BillingSummary> {
  return (await api.get<BillingSummary>('/api/billing/summary')).data
}

export async function startCheckout(plan: string): Promise<CheckoutResponse> {
  return (await api.post<CheckoutResponse>('/api/billing/checkout', { plan })).data
}

export async function openPortal(): Promise<string> {
  return (await api.post<{ url: string }>('/api/billing/portal', {})).data.url
}

/** Demo-only: completes an upgrade locally (stub gateway). */
export async function simulateActivation(plan: string): Promise<BillingSummary> {
  return (await api.post<BillingSummary>('/api/billing/simulate-activation', { plan })).data
}
