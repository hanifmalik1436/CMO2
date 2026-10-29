import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import { TrafficLight } from '../../components/ui/TrafficLight'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import {
  connectPlatform,
  disconnect,
  listConnections,
  listPlatforms,
  listSyncJobs,
  syncConnection,
  testConnection,
} from './integrationsApi'
import type { Company, PlatformConnection, PlatformInfo, SyncJob } from '../../lib/types'

export default function IntegrationsPage() {
  const qc = useQueryClient()
  const { data: companies } = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])

  const [companyId, setCompanyId] = useState<number | null>(null)
  useEffect(() => {
    if (companyId === null && activeCompanies.length > 0) setCompanyId(activeCompanies[0].id)
  }, [activeCompanies, companyId])

  const { data: connections, isLoading } = useQuery<PlatformConnection[]>({
    queryKey: ['connections', companyId],
    queryFn: () => listConnections(companyId!),
    enabled: companyId !== null,
  })

  const [showForm, setShowForm] = useState(false)

  return (
    <div>
      <PageHeader
        title="Integrations Hub"
        subtitle="Connect any platform in the catalog, monitor health, and sync KPIs into the dashboard. Demo mode is on by default, so every platform works without a live account."
        action={
          <div className="flex items-center gap-2">
            <select className="input w-48" value={companyId ?? ''} onChange={(e) => setCompanyId(Number(e.target.value))}>
              {activeCompanies.length === 0 && <option value="">No companies</option>}
              {activeCompanies.map((c) => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
            <RequireRole allow={['OWNER', 'ADMIN']}>
              <button className="btn-primary" disabled={companyId === null} onClick={() => setShowForm((v) => !v)}>
                {showForm ? 'Close' : '+ Connect'}
              </button>
            </RequireRole>
          </div>
        }
      />

      {activeCompanies.length === 0 ? (
        <GlassCard><p className="text-sm text-gray-300">Add a company first.</p></GlassCard>
      ) : (
        <>
          {showForm && companyId !== null && (
            <div className="mb-6">
              <ConnectForm companyId={companyId} onDone={() => setShowForm(false)} />
            </div>
          )}

          {isLoading && <GlassCard><p className="text-sm text-gray-400">Loading connections…</p></GlassCard>}

          {!isLoading && (connections?.length ?? 0) === 0 && (
            <GlassCard>
              <div className="py-8 text-center">
                <p className="text-sm text-gray-300">No platforms connected yet.</p>
                <p className="mt-1 text-xs text-gray-500">Connect GoHighLevel to start syncing KPIs.</p>
              </div>
            </GlassCard>
          )}

          <div className="space-y-4">
            {(connections ?? []).map((conn) => (
              <ConnectionCard key={conn.id} conn={conn} onChanged={() => qc.invalidateQueries({ queryKey: ['connections', companyId] })} />
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function ConnectForm({ companyId, onDone }: { companyId: number; onDone: () => void }) {
  const qc = useQueryClient()
  const { data: platforms } = useQuery<PlatformInfo[]>({ queryKey: ['platforms'], queryFn: listPlatforms })
  const supported = (platforms ?? []).filter((p) => p.supported)

  const [platform, setPlatform] = useState('GOHIGHLEVEL')
  const [apiKey, setApiKey] = useState('')
  const [locationId, setLocationId] = useState('')
  const [error, setError] = useState<string | null>(null)

  const selected = supported.find((p) => p.code === platform)

  const connect = useMutation({
    mutationFn: () => connectPlatform(companyId, { platform, apiKey: apiKey.trim(), locationId: locationId.trim() || undefined }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['connections', companyId] })
      onDone()
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not connect')),
  })

  return (
    <GlassCard>
      <form
        onSubmit={(e) => { e.preventDefault(); setError(null); connect.mutate() }}
        className="grid gap-4 sm:grid-cols-3"
      >
        {error && (
          <div className="sm:col-span-3 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {error}
          </div>
        )}
        <div className="sm:col-span-3 text-xs text-gray-400">
          Every platform runs in <span className="text-white">demo mode</span> by default — any key beginning
          with <code className="text-brand-glow">demo</code> returns realistic KPIs. The key is encrypted and only shown as ••••last4.
          {selected && !selected.deploysAssets && (
            <span> {selected.name} is a data source (no asset deployment).</span>
          )}
        </div>
        <div>
          <label className="label">Platform *</label>
          <select className="input" value={platform} onChange={(e) => setPlatform(e.target.value)}>
            {supported.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
          </select>
        </div>
        <div>
          <label className="label">API key *</label>
          <input className="input" required value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="demo-key-123456" />
        </div>
        <div>
          <label className="label">Location ID</label>
          <input className="input" value={locationId} onChange={(e) => setLocationId(e.target.value)} placeholder="optional" />
        </div>
        <div className="sm:col-span-3 flex items-center gap-2">
          <button type="submit" className="btn-primary" disabled={connect.isPending}>
            {connect.isPending ? 'Connecting…' : 'Connect'}
          </button>
          <button type="button" className="btn-ghost" onClick={onDone}>Cancel</button>
        </div>
      </form>
    </GlassCard>
  )
}

function ConnectionCard({ conn, onChanged }: { conn: PlatformConnection; onChanged: () => void }) {
  const [showLogs, setShowLogs] = useState(false)

  const test = useMutation({ mutationFn: () => testConnection(conn.id), onSuccess: onChanged })
  const sync = useMutation({ mutationFn: () => syncConnection(conn.id), onSuccess: onChanged })
  const remove = useMutation({ mutationFn: () => disconnect(conn.id), onSuccess: onChanged })

  return (
    <GlassCard>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <div className="flex items-center gap-3">
            <h3 className="font-semibold text-white">{conn.platformName}</h3>
            <TrafficLight health={conn.health} />
          </div>
          <p className="mt-1 text-xs text-gray-400">
            Key {conn.maskedKey} · {conn.status}
            {conn.lastSyncAt && ` · last sync ${new Date(conn.lastSyncAt).toLocaleString()}`}
          </p>
          {conn.lastError && <p className="mt-1 text-xs text-red-300">{conn.lastError}</p>}
        </div>
        <div className="flex items-center gap-2">
          <button className="btn-ghost" disabled={test.isPending} onClick={() => test.mutate()}>Test</button>
          <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']}>
            <button className="btn-primary" disabled={sync.isPending} onClick={() => sync.mutate()}>
              {sync.isPending ? 'Syncing…' : 'Sync now'}
            </button>
          </RequireRole>
          <button className="btn-ghost" onClick={() => setShowLogs((v) => !v)}>{showLogs ? 'Hide logs' : 'Logs'}</button>
          <RequireRole allow={['OWNER', 'ADMIN']}>
            <button className="text-xs text-gray-400 hover:text-red-300" disabled={remove.isPending} onClick={() => remove.mutate()}>
              Disconnect
            </button>
          </RequireRole>
        </div>
      </div>
      {showLogs && <SyncLogs connectionId={conn.id} />}
    </GlassCard>
  )
}

function SyncLogs({ connectionId }: { connectionId: number }) {
  const { data: jobs, isLoading } = useQuery<SyncJob[]>({
    queryKey: ['sync-jobs', connectionId],
    queryFn: () => listSyncJobs(connectionId),
  })

  if (isLoading) return <p className="mt-4 text-xs text-gray-500">Loading logs…</p>
  if (!jobs || jobs.length === 0) return <p className="mt-4 text-xs text-gray-500">No syncs yet.</p>

  return (
    <div className="mt-4 divide-y divide-white/5 border-t border-white/10 pt-3">
      {jobs.map((j) => (
        <div key={j.id} className="flex items-center justify-between py-1.5 text-xs">
          <span className="text-gray-400">{j.type}</span>
          <span className="text-gray-500">{j.startedAt ? new Date(j.startedAt).toLocaleTimeString() : '—'}</span>
          <span className="text-gray-400">{j.kpisIngested} KPIs</span>
          <span className={j.status === 'SUCCESS' ? 'text-emerald-300' : j.status === 'FAILED' ? 'text-red-300' : 'text-amber-300'}>
            {j.status}
          </span>
        </div>
      ))}
    </div>
  )
}
