import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import {
  approveAsset, assetBody, editAssetContent, listApprovals, listSocialAccounts, publishAsset, rejectAsset,
} from './assetsApi'
import type { ApprovalItem, AssetStatus, Company, SocialAccounts } from '../../lib/types'

const TABS: { status: AssetStatus; label: string }[] = [
  { status: 'PENDING_APPROVAL', label: 'Waiting' },
  { status: 'APPROVED', label: 'Approved' },
  { status: 'DEPLOYED', label: 'Published' },
  { status: 'REJECTED', label: 'Rejected' },
]

export default function ApprovalsPage() {
  const [params, setParams] = useSearchParams()
  const companyId = params.get('companyId') ? Number(params.get('companyId')) : undefined
  const status = (params.get('status') as AssetStatus | null) ?? 'PENDING_APPROVAL'
  const setParam = (key: string, value?: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    setParams(next, { replace: true })
  }

  const companies = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const queue = useQuery<ApprovalItem[]>({
    queryKey: ['approvals', companyId ?? 'all', status],
    queryFn: () => listApprovals({ companyId, status }),
  })

  return (
    <div>
      <PageHeader
        title="Approvals"
        subtitle="Everything CMO² writes for a customer waits here. Nothing reaches their audience until you approve it."
      />
      <RequireRole
        allow={['OWNER', 'ADMIN']}
        fallback={<GlassCard><p className="text-sm text-gray-400">Only the owner or an admin can review drafts.</p></GlassCard>}
      >
        <div className="mb-4 flex flex-wrap items-center gap-3">
          <div className="flex rounded-lg border border-white/10 p-0.5 text-xs">
            {TABS.map((t) => (
              <button
                key={t.status}
                onClick={() => setParam('status', t.status === 'PENDING_APPROVAL' ? undefined : t.status)}
                className={`rounded px-3 py-1 ${status === t.status ? 'bg-brand text-white' : 'text-gray-400'}`}
              >
                {t.label}
              </button>
            ))}
          </div>
          <select
            className="input h-8 w-auto py-0 text-xs"
            value={companyId ?? ''}
            onChange={(e) => setParam('companyId', e.target.value || undefined)}
          >
            <option value="">All customers</option>
            {(companies.data ?? []).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </div>

        {queue.isLoading && <GlassCard><p className="text-sm text-gray-400">Loading drafts…</p></GlassCard>}
        {queue.isError && (
          <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(queue.error, 'Could not load drafts')}</p></GlassCard>
        )}
        {queue.data && queue.data.length === 0 && (
          <GlassCard>
            <p className="py-8 text-center text-sm text-gray-400">
              {status === 'PENDING_APPROVAL' ? 'Nothing waiting for approval.' : 'Nothing here.'}
            </p>
          </GlassCard>
        )}
        <div className="space-y-4">
          {(queue.data ?? []).map((item) => <DraftCard key={item.asset.id} item={item} />)}
        </div>
      </RequireRole>
    </div>
  )
}

function DraftCard({ item }: { item: ApprovalItem }) {
  const qc = useQueryClient()
  const a = item.asset
  const pending = a.status === 'PENDING_APPROVAL'
  const [text, setText] = useState(() => assetBody(a))
  const [rejecting, setRejecting] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['approvals'] })
  const fail = (fallback: string) => (err: unknown) => setError(apiErrorMessage(err, fallback))

  const edited = text.trim() !== assetBody(a).trim()
  const save = useMutation({ mutationFn: () => editAssetContent(a.id, text), onSuccess: refresh, onError: fail('Could not save the edit') })
  const approve = useMutation({
    mutationFn: async () => {
      if (edited) await editAssetContent(a.id, text)
      return approveAsset(a.id)
    },
    onSuccess: refresh,
    onError: fail('Could not approve'),
  })
  const reject = useMutation({ mutationFn: () => rejectAsset(a.id, reason.trim()), onSuccess: refresh, onError: fail('Could not reject') })
  const busy = save.isPending || approve.isPending || reject.isPending

  return (
    <GlassCard>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold text-white">{item.companyName ?? `Customer #${a.companyId}`}</h3>
          <p className="text-xs text-gray-500">
            {a.type.replace(/_/g, ' ').toLowerCase()} · {a.language} · v{a.version} · {new Date(a.createdAt).toLocaleString()}
          </p>
        </div>
        <StatusPill status={a.status} />
      </div>

      {pending ? (
        <textarea
          className="input mt-3 min-h-[180px] font-mono text-xs"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
      ) : (
        <pre className="mt-3 max-h-72 overflow-auto whitespace-pre-wrap rounded-lg bg-black/30 p-3 text-xs text-gray-200">
          {assetBody(a)}
        </pre>
      )}

      {a.reviewNote && (
        <p className="mt-2 text-xs text-gray-400">
          {a.status === 'REJECTED' ? 'Rejected: ' : 'Note: '}{a.reviewNote}
        </p>
      )}

      {pending && (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <button className="btn-primary" disabled={busy || !text.trim()} onClick={() => { setError(null); approve.mutate() }}>
            {approve.isPending ? 'Approving…' : edited ? 'Save & approve' : 'Approve'}
          </button>
          {edited && (
            <button className="btn-ghost" disabled={busy || !text.trim()} onClick={() => { setError(null); save.mutate() }}>
              {save.isPending ? 'Saving…' : 'Save edit'}
            </button>
          )}
          {!rejecting ? (
            <button className="btn-ghost hover:text-red-300" disabled={busy} onClick={() => setRejecting(true)}>Reject</button>
          ) : (
            <form
              className="flex flex-1 flex-wrap gap-2"
              onSubmit={(e) => { e.preventDefault(); setError(null); reject.mutate() }}
            >
              <input
                className="input h-9 min-w-[220px] flex-1"
                placeholder="Why? (kept with the draft)"
                maxLength={1000}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
              <button type="submit" className="btn-ghost text-red-300" disabled={busy || !reason.trim()}>
                {reject.isPending ? 'Rejecting…' : 'Confirm reject'}
              </button>
              <button type="button" className="btn-ghost" onClick={() => setRejecting(false)}>Cancel</button>
            </form>
          )}
        </div>
      )}

      {a.status === 'APPROVED' && <PublishPanel item={item} onDone={refresh} />}

      {item.latestDeployment && <LastAttempt item={item} />}

      {error && <p className="mt-3 text-sm text-red-300">{error}</p>}
    </GlassCard>
  )
}

function PublishPanel({ item, onDone }: { item: ApprovalItem; onDone: () => void }) {
  const a = item.asset
  const social = a.type === 'SOCIAL_MEDIA'
  const unconfirmed = item.latestDeployment?.status === 'UNCONFIRMED'
  const accounts = useQuery<SocialAccounts>({
    queryKey: ['social-accounts', a.companyId],
    queryFn: () => listSocialAccounts(a.companyId),
    enabled: social,
  })
  const [chosen, setChosen] = useState<string[] | null>(null)
  const [when, setWhen] = useState(() => localInput(new Date(Date.now() + 10 * 60_000)))
  const [media, setMedia] = useState('')
  const [checked, setChecked] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const usable = (accounts.data?.accounts ?? []).filter((x) => !x.expired)
  const selected = chosen ?? usable.map((x) => x.id)
  const live = social && accounts.data?.live

  const publish = useMutation({
    mutationFn: () => publishAsset(a.id, live ? {
      accountIds: selected,
      scheduleAt: new Date(when).toISOString(),
      mediaUrls: media.split(/[\s,]+/).map((m) => m.trim()).filter(Boolean),
      confirmRetry: unconfirmed && checked,
    } : { confirmRetry: unconfirmed && checked }),
    onSuccess: onDone,
    onError: (err) => setError(apiErrorMessage(err, 'Could not publish')),
  })

  return (
    <div className="mt-4 rounded-lg border border-white/10 p-3">
      <h4 className="text-sm font-semibold text-white">Publish</h4>
      {social && accounts.isLoading && <p className="mt-2 text-xs text-gray-400">Loading the customer's social accounts…</p>}
      {social && accounts.isError && (
        <p className="mt-2 text-xs text-red-300">{apiErrorMessage(accounts.error, 'Could not load social accounts')}</p>
      )}
      {accounts.data?.message && <p className="mt-2 text-xs text-gray-400">{accounts.data.message}</p>}
      {!social && (
        <p className="mt-2 text-xs text-gray-400">
          Only social posts publish to GoHighLevel so far. Email waits on the email spike; for now, copy the approved text into GHL.
        </p>
      )}

      {live && (
        <div className="mt-3 space-y-3">
          <div className="flex flex-wrap gap-3">
            {(accounts.data?.accounts ?? []).map((acc) => (
              <label key={acc.id} className={`flex items-center gap-2 text-xs ${acc.expired ? 'text-gray-600' : 'text-gray-300'}`}>
                <input
                  type="checkbox"
                  disabled={acc.expired}
                  checked={selected.includes(acc.id)}
                  onChange={(e) => setChosen(e.target.checked ? [...selected, acc.id] : selected.filter((id) => id !== acc.id))}
                />
                {acc.name ?? acc.id} <span className="text-gray-500">({acc.platform ?? 'unknown'}{acc.expired ? ', expired' : ''})</span>
              </label>
            ))}
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className="label">Goes out at (your time)</label>
              <input className="input" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />
            </div>
            <div>
              <label className="label">Image or video links (optional, https)</label>
              <input className="input" placeholder="https://…/photo.jpg" value={media} onChange={(e) => setMedia(e.target.value)} />
            </div>
          </div>
        </div>
      )}

      {unconfirmed && (
        <label className="mt-3 flex items-start gap-2 text-xs text-amber-300">
          <input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />
          I checked the customer's Social Planner and this post isn't there, so publishing again won't duplicate it.
        </label>
      )}

      <div className="mt-3 flex items-center gap-3">
        <button
          className="btn-primary"
          disabled={publish.isPending || (unconfirmed && !checked) || (live === true && selected.length === 0) || (social && accounts.isLoading)}
          onClick={() => { setError(null); publish.mutate() }}
        >
          {publish.isPending ? 'Publishing…' : live ? 'Schedule in GoHighLevel' : 'Publish'}
        </button>
        {error && <span className="text-sm text-red-300">{error}</span>}
      </div>
    </div>
  )
}

function LastAttempt({ item }: { item: ApprovalItem }) {
  const d = item.latestDeployment!
  const tone = d.status === 'ACCEPTED' ? 'text-emerald-300' : d.status === 'UNCONFIRMED' ? 'text-amber-300' : 'text-red-300'
  return (
    <div className="mt-3 text-xs">
      <span className={tone}>
        {d.status === 'ACCEPTED' ? (d.scheduledFor ? `Scheduled for ${new Date(d.scheduledFor).toLocaleString()}` : 'Published')
          : d.status === 'UNCONFIRMED' ? 'Not confirmed by GoHighLevel' : 'Last attempt failed'}
      </span>
      {d.externalRef && <span className="ml-2 font-mono text-gray-500">{d.externalRef}</span>}
      {d.errorMessage && <p className="mt-1 text-gray-400">{d.errorMessage}</p>}
    </div>
  )
}

function StatusPill({ status }: { status: AssetStatus }) {
  const style: Record<string, string> = {
    PENDING_APPROVAL: 'bg-amber-500/15 text-amber-300',
    APPROVED: 'bg-sky-500/15 text-sky-300',
    DEPLOYED: 'bg-emerald-500/15 text-emerald-300',
    REJECTED: 'bg-red-500/15 text-red-300',
  }
  const label: Record<string, string> = {
    PENDING_APPROVAL: 'Waiting', APPROVED: 'Approved', DEPLOYED: 'Published', REJECTED: 'Rejected',
  }
  return (
    <span className={`rounded-full px-2 py-0.5 text-[10px] uppercase ${style[status] ?? 'bg-white/10 text-gray-300'}`}>
      {label[status] ?? status}
    </span>
  )
}

/** A Date as the value a datetime-local input expects, in the viewer's own timezone. */
function localInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
