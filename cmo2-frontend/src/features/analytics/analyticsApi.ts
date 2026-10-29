import { api } from '../../lib/api'
import type {
  Forecast,
  MetricAggregate,
  TimeseriesPoint,
  WhatIfResult,
  WhatIfScenario,
} from '../../lib/types'

export async function fetchForecast(companyId: number, metric: string, horizon = 6): Promise<Forecast> {
  return (
    await api.get<Forecast>(`/api/companies/${companyId}/analytics/forecast`, {
      params: { metric, horizon },
    })
  ).data
}

export async function runWhatIf(companyId: number, scenario: WhatIfScenario): Promise<WhatIfResult> {
  return (await api.post<WhatIfResult>(`/api/companies/${companyId}/analytics/what-if`, scenario)).data
}

export async function fetchTimeseries(
  companyId: number,
  metric: string,
  limit = 30,
): Promise<TimeseriesPoint[]> {
  return (
    await api.get<TimeseriesPoint[]>(
      `/api/companies/${companyId}/analytics/timeseries`,
      { params: { metric, limit } },
    )
  ).data
}

export async function fetchPortfolio(): Promise<MetricAggregate[]> {
  return (await api.get<MetricAggregate[]>('/api/analytics/portfolio')).data
}
