/** Human labels + formatting for the canonical KPI metrics. */
const count = (v: number) => Math.round(v).toLocaleString()
const money = (v: number) => v.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })

const META: Record<string, { label: string; format: (v: number) => string }> = {
  leads: { label: 'New leads', format: count },
  cpl: { label: 'CPL', format: (v) => `$${v.toFixed(2)}` },
  roas: { label: 'ROAS', format: (v) => `${v.toFixed(2)}x` },
  ctr: { label: 'CTR', format: (v) => `${v.toFixed(2)}%` },
  conversion_rate: { label: 'Close rate', format: (v) => `${v.toFixed(1)}%` },
  appointments: { label: 'Appointments', format: count },
  won: { label: 'Deals won', format: count },
  revenue: { label: 'Revenue', format: money },
  pipeline_value: { label: 'Open pipeline', format: money },
  contacts_total: { label: 'Contacts', format: count },
  opportunities_open: { label: 'Open opportunities', format: count },
  opportunities_won_total: { label: 'Won (all time)', format: count },
  opportunities_lost_total: { label: 'Lost (all time)', format: count },
  conversations_unread: { label: 'Unread conversations', format: count },
  social_accounts: { label: 'Social accounts', format: count },
}

/** Display order for a customer's figures. */
export const METRIC_ORDER = [
  'leads', 'won', 'revenue', 'appointments', 'conversion_rate', 'pipeline_value', 'opportunities_open',
  'contacts_total', 'conversations_unread', 'social_accounts',
]

export function kpiLabel(metric: string): string {
  return META[metric]?.label ?? metric
}

export function kpiValue(metric: string, value: number): string {
  return META[metric]?.format(value) ?? value.toString()
}
