import { useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import { TrafficLight } from '../../components/ui/TrafficLight'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { disconnectAgency, enableSubAccount, getAgencyStatus, getGhlMode, listLoopSchedules, listSubAccounts } from './customersApi'
import { listCompanies } from '../companies/companiesApi'
import { listApprovals } from '../assets/assetsApi'
import { describeSchedule } from './CustomerLoopPanels'
import { listDocTargets, provisionDocTargets, retryDocSharing } from '../settings/docsApi'
import type { ApprovalItem, Company, DocTargets, GhlAgencyStatus, GhlModeInfo, GhlSubAccount, LoopSchedule } from '../../lib/types'

export default function CustomersPage() {
  const mode = useQuery<GhlModeInfo>({ queryKey: ['ghl', 'mode'], queryFn: getGhlMode })
  const companies = useQuery<Company[]>({
    queryKey: ['companies'],
    queryFn: listCompanies,
    enabled: mode.data?.agencyEnabled === false,
  })
  const agencyEnabled = mode.data?.agencyEnabled ?? false
  const agency = useQuery<GhlAgencyStatus>({
    queryKey: ['ghl', 'agency'],
    queryFn: getAgencyStatus,
    enabled: agencyEnabled,
  })
  const connected = agency.data?.connected ?? false

  if (mode.isLoading) {
    return (
      <div>
        <PageHeader title="Customers" subtitle="Loading GoHighLevel mode…" />
        <GlassCard><p className="text-sm text-gray-400">Checking your GoHighLevel integration mode…</p></GlassCard>
      </div>
    )
  }

  if (mode.isError || !mode.data) {
    return (
      <div>
        <PageHeader title="Customers" subtitle="Could not load GoHighLevel mode." />
        <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(mode.error, 'Could not load integration mode')}</p></GlassCard>
      </div>
    )
  }

  if (!mode.data.agencyEnabled) {
    if (companies.isLoading) {
      return (
        <div>
          <PageHeader title="Customers" subtitle="Loading your customer…" />
          <GlassCard><p className="text-sm text-gray-400">Loading companies…</p></GlassCard>
        </div>
      )
    }
    if (companies.isError) {
      return (
        <div>
          <PageHeader title="Customers" subtitle="Could not load customer list." />
          <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(companies.error, 'Could not load companies')}</p></GlassCard>
        </div>
      )
    }
    const active = (companies.data ?? []).filter((c) => c.status === 'ACTIVE')
    if (active.length > 0) {
      return <Navigate to={`/customers/${active[0].id}`} replace />
    }
    return <SingleSubaccountModeCard />
  }

  return (
    <div>
      <PageHeader
        title="Customers"
        subtitle="Every sub-account under your GoHighLevel agency. Enable one to make it a CMO² customer."
      />
      <RequireRole
        allow={['OWNER', 'ADMIN']}
        fallback={
          <GlassCard>
            <p className="text-sm text-gray-400">Only the owner or an admin can manage customers.</p>
          </GlassCard>
        }
      >
        <AgencyCard query={agency} />
        {connected && <SubAccountList />}
      </RequireRole>
    </div>
  )
}

function SingleSubaccountModeCard() {
  return (
    <div>
      <PageHeader
        title="Customers"
        subtitle="Agency onboarding is disabled in this environment. Use direct company integrations for development."
      />
      <GlassCard>
        <h3 className="font-semibold text-white">Single sub-account development mode is enabled</h3>
        <p className="mt-2 text-sm text-gray-300">
          No tenant or customer data is removed. To test now without an agency account:
        </p>
        <ol className="mt-3 list-inside list-decimal space-y-1 text-sm text-gray-300">
          <li>Create or choose a company in <Link className="underline decoration-white/30 hover:decoration-white" to="/companies">Companies</Link>.</li>
          <li>Open <Link className="underline decoration-white/30 hover:decoration-white" to="/integrations">Integrations</Link>.</li>
          <li>Connect GoHighLevel using your key and sub-account location id.</li>
        </ol>
        <p className="mt-3 text-xs text-gray-500">
          To restore agency onboarding later, set <code>CMO2_GHL_MODE=agency</code> and restart the backend.
        </p>
      </GlassCard>
    </div>
  )
}

function AgencyCard({ query }: { query: UseQueryResult<GhlAgencyStatus> }) {
  const qc = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const disconnect = useMutation({
    mutationFn: disconnectAgency,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['ghl'] }),
    onError: (err) => setError(apiErrorMessage(err, 'Could not disconnect')),
  })

  if (query.isLoading) {
    return <GlassCard className="mb-6"><p className="text-sm text-gray-400">Checking your GoHighLevel agency…</p></GlassCard>
  }
  if (query.isError || !query.data) {
    return (
      <GlassCard className="mb-6">
        <p className="text-sm text-red-300">{apiErrorMessage(query.error, 'Could not load the agency connection')}</p>
      </GlassCard>
    )
  }

  const a = query.data
  return (
    <GlassCard className="mb-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h3 className="font-semibold text-white">GoHighLevel agency</h3>
          {a.connected ? (
            <p className="mt-1 text-xs text-gray-400">
              Connected · agency id <span className="font-mono text-gray-300">{a.ghlCompanyId}</span>
            </p>
          ) : (
            <p className="mt-1 text-xs text-gray-400">Not connected.</p>
          )}
        </div>
        {a.connected ? (
          <button
            className="btn-ghost"
            onClick={() => {
              setError(null)
              if (window.confirm('Disconnect your GoHighLevel agency? Customers stay, but syncing stops until you reconnect.')) {
                disconnect.mutate()
              }
            }}
            disabled={disconnect.isPending}
          >
            {disconnect.isPending ? 'Disconnecting…' : 'Disconnect'}
          </button>
        ) : a.appConfigured && a.installUrl ? (
          <a className="btn-primary" href={a.installUrl}>Connect GoHighLevel agency</a>
        ) : null}
      </div>

      {a.connected && a.status === 'ERROR' && (
        <div className="mt-4 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
          {a.lastError ?? 'The agency connection needs attention.'}{' '}
          {a.installUrl && <a className="underline" href={a.installUrl}>Reconnect</a>}
        </div>
      )}

      {!a.connected && !a.appConfigured && (
        <p className="mt-4 text-xs text-gray-500">
          Your GHL Marketplace app isn't configured yet. Set CMO2_GHL_CLIENT_ID, CMO2_GHL_CLIENT_SECRET and
          CMO2_GHL_INSTALL_URL on the backend, then reload this page.
        </p>
      )}

      {error && <p className="mt-3 text-sm text-red-300">{error}</p>}
    </GlassCard>
  )
}

function SubAccountList() {
  const { data, isLoading, isError, error } = useQuery<GhlSubAccount[]>({
    queryKey: ['ghl', 'sub-accounts'],
    queryFn: listSubAccounts,
  })
  const targets = useQuery<DocTargets[]>({ queryKey: ['docs', 'targets'], queryFn: listDocTargets })
  const targetsByCompany = new Map((targets.data ?? []).map((t) => [t.companyId, t]))
  const schedules = useQuery<LoopSchedule[]>({ queryKey: ['loop', 'schedules'], queryFn: listLoopSchedules })
  const scheduleByCompany = new Map((schedules.data ?? []).map((s) => [s.companyId, s]))
  const waiting = useQuery<ApprovalItem[]>({ queryKey: ['approvals', 'all', 'PENDING_APPROVAL'], queryFn: () => listApprovals() })
  const waitingByCompany = new Map<number, number>()
  for (const item of waiting.data ?? []) {
    waitingByCompany.set(item.asset.companyId, (waitingByCompany.get(item.asset.companyId) ?? 0) + 1)
  }

  if (isLoading) {
    return <GlassCard><p className="text-sm text-gray-400">Loading sub-accounts from GoHighLevel…</p></GlassCard>
  }
  if (isError) {
    return <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(error, 'Could not load sub-accounts')}</p></GlassCard>
  }
  const rows = data ?? []
  if (rows.length === 0) {
    return (
      <GlassCard>
        <div className="py-8 text-center">
          <p className="text-sm text-gray-300">No sub-accounts under this agency yet.</p>
          <p className="mt-1 text-xs text-gray-500">Create one in GoHighLevel (and load its Snapshot), then refresh.</p>
        </div>
      </GlassCard>
    )
  }

  return (
    <GlassCard className="overflow-x-auto">
      <table className="w-full text-left text-sm">
        <thead className="text-xs uppercase text-gray-500">
          <tr>
            <th className="pb-3 pr-4 font-medium">Sub-account</th>
            <th className="pb-3 pr-4 font-medium">Contact</th>
            <th className="pb-3 pr-4 font-medium">Status</th>
            <th className="pb-3 pr-4 font-medium">Last sync</th>
            <th className="pb-3 pr-4 font-medium">Loop</th>
            <th className="pb-3 pr-4 font-medium">Drafts</th>
            <th className="pb-3 pr-4 font-medium">Workspace</th>
            <th className="pb-3 font-medium" />
          </tr>
        </thead>
        <tbody className="divide-y divide-white/5">
          {rows.map((s) => (
            <SubAccountRow
              key={s.locationId}
              sub={s}
              targets={s.companyId != null ? targetsByCompany.get(s.companyId) : undefined}
              schedule={s.companyId != null ? scheduleByCompany.get(s.companyId) : undefined}
              waiting={s.companyId != null ? waitingByCompany.get(s.companyId) ?? 0 : 0}
            />
          ))}
        </tbody>
      </table>
    </GlassCard>
  )
}

function SubAccountRow({ sub, targets, schedule, waiting }: {
  sub: GhlSubAccount
  targets?: DocTargets
  schedule?: LoopSchedule
  waiting: number
}) {
  const qc = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const enable = useMutation({
    mutationFn: () => enableSubAccount(sub.locationId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ghl', 'sub-accounts'] })
      qc.invalidateQueries({ queryKey: ['companies'] })
      qc.invalidateQueries({ queryKey: ['docs', 'targets'] })
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not enable this sub-account')),
  })
  const provision = useMutation({
    mutationFn: () => provisionDocTargets(sub.companyId as number),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['docs', 'targets'] }),
    onError: (err) => setError(apiErrorMessage(err, 'Could not set up workspace pages')),
  })
  const retryShare = useMutation({
    mutationFn: () => retryDocSharing(sub.companyId as number),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['docs', 'targets'] }),
    onError: (err) => setError(apiErrorMessage(err, 'Could not retry sharing')),
  })
  const links = [
    { label: 'Notion', url: targets?.notionUrl, id: targets?.notionPageId },
    { label: 'Confluence', url: targets?.confluenceUrl, id: targets?.confluencePageId },
    { label: 'Linear', url: targets?.linearUrl, id: targets?.linearProjectId },
  ].filter((l) => l.id)

  return (
    <tr className="align-top">
      <td className="py-3 pr-4">
        {sub.enabled && sub.companyId != null ? (
          <Link to={`/customers/${sub.companyId}`} className="text-white underline decoration-white/30 hover:decoration-white">
            {sub.name ?? 'Unnamed sub-account'}
          </Link>
        ) : (
          <div className="text-white">{sub.name ?? 'Unnamed sub-account'}</div>
        )}
        <div className="font-mono text-[11px] text-gray-500">{sub.locationId}</div>
        {sub.website && <div className="truncate text-xs text-gray-400">{sub.website}</div>}
      </td>
      <td className="py-3 pr-4 text-xs text-gray-400">
        {sub.email && <div>{sub.email}</div>}
        {sub.phone && <div>{sub.phone}</div>}
        {[sub.city, sub.country].filter(Boolean).join(', ')}
      </td>
      <td className="py-3 pr-4">
        {sub.enabled ? (
          sub.health ? <TrafficLight health={sub.health} /> : <span className="text-xs text-gray-400">Enabled</span>
        ) : (
          <span className="text-xs text-gray-500">Not enabled</span>
        )}
        {sub.lastError && <div className="mt-1 max-w-xs text-xs text-red-300">{sub.lastError}</div>}
      </td>
      <td className="py-3 pr-4 text-xs text-gray-400">
        {sub.lastSyncAt ? new Date(sub.lastSyncAt).toLocaleString() : '—'}
      </td>
      <td className="py-3 pr-4 text-xs text-gray-400">
        {sub.enabled && schedule ? (
          <>
            <div>{describeSchedule(schedule)}</div>
            {schedule.nextRunAt && <div className="text-gray-500">next {new Date(schedule.nextRunAt).toLocaleString()}</div>}
          </>
        ) : '—'}
      </td>
      <td className="py-3 pr-4 text-xs">
        {sub.enabled && sub.companyId != null && waiting > 0 ? (
          <Link to={`/approvals?companyId=${sub.companyId}`} className="text-amber-300 underline">{waiting} waiting</Link>
        ) : <span className="text-gray-500">—</span>}
      </td>
      <td className="py-3 pr-4 text-xs">
        {sub.enabled && (
          <div className="space-y-1">
            {links.map((l) =>
              l.url ? (
                <a key={l.label} className="block text-gray-300 underline" href={l.url} target="_blank" rel="noreferrer">
                  {l.label}
                </a>
              ) : (
                <span key={l.label} className="block text-gray-400">{l.label}</span>
              ),
            )}
            {targets?.lastError && <div className="max-w-xs text-red-300">{targets.lastError}</div>}
            {renderShareState('Notion', targets?.notionShare)}
            {renderShareState('Confluence', targets?.confluenceShare)}
            {renderShareState('Linear', targets?.linearShare)}
            {renderShareState('Slack', targets?.slackShare)}
            <button
              className="text-gray-400 underline hover:text-gray-200"
              onClick={() => { setError(null); provision.mutate() }}
              disabled={provision.isPending}
            >
              {provision.isPending ? 'Setting up…' : links.length ? 'Set up missing pages' : 'Set up pages'}
            </button>
            <RequireRole allow={['OWNER']}>
              <button
                className="text-gray-400 underline hover:text-gray-200"
                onClick={() => { setError(null); retryShare.mutate() }}
                disabled={retryShare.isPending}
              >
                {retryShare.isPending ? 'Retrying…' : 'Retry sharing'}
              </button>
            </RequireRole>
          </div>
        )}
      </td>
      <td className="py-3 text-right">
        {!sub.enabled && (
          <button className="btn-primary" onClick={() => { setError(null); enable.mutate() }} disabled={enable.isPending}>
            {enable.isPending ? 'Enabling…' : 'Enable'}
          </button>
        )}
        {error && <div className="mt-2 max-w-xs text-left text-xs text-red-300">{error}</div>}
      </td>
    </tr>
  )
}

function renderShareState(
  label: string,
  state?: { status: string | null; message: string | null; sharedEmail: string | null } | null,
) {
  if (!state || !state.status) return null
  const tone =
    state.status === 'SHARED'
      ? 'text-emerald-300'
      : state.status === 'FAILED'
        ? 'text-red-300'
        : state.status === 'WARNING'
          ? 'text-amber-300'
          : 'text-gray-400'
  const text = state.sharedEmail ? `${state.status.toLowerCase()} (${state.sharedEmail})` : state.status.toLowerCase()
  return (
    <div key={label} className={`max-w-xs ${tone}`}>
      {label}: {text}
      {state.message ? ` — ${state.message}` : ''}
    </div>
  )
}
