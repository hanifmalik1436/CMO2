import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard } from '../../components/ui/GlassCard'
import { apiErrorMessage } from '../../lib/api'
import { listDocConnections, removeDocConnection, saveDocConnection, testDocConnection } from './docsApi'
import type { DocConnection, DocProviderKey, SaveDocConnectionInput } from '../../lib/types'

interface Field {
  key: keyof SaveDocConnectionInput
  label: string
  placeholder?: string
  secret?: boolean
  optional?: boolean
}

const FIELDS: Record<DocProviderKey, Field[]> = {
  NOTION: [
    { key: 'secret', label: 'Integration token', placeholder: 'ntn_…', secret: true },
    { key: 'notionRootId', label: 'Root page or database', placeholder: 'Paste its Notion link or id' },
  ],
  CONFLUENCE: [
    { key: 'confluenceSiteUrl', label: 'Site URL', placeholder: 'https://your-team.atlassian.net' },
    { key: 'confluenceEmail', label: 'Account email', placeholder: 'you@company.com' },
    { key: 'secret', label: 'API token', placeholder: 'From id.atlassian.com → Security → API tokens', secret: true },
    { key: 'confluenceSpaceKey', label: 'Space key', placeholder: 'e.g. CMO' },
    { key: 'confluenceParentPageId', label: 'Parent page id (optional)', placeholder: 'Defaults to the space home page', optional: true },
  ],
  LINEAR: [
    { key: 'secret', label: 'API key', placeholder: 'lin_api_…', secret: true },
    { key: 'linearTeamId', label: 'Team id (UUID)', placeholder: '00000000-0000-0000-0000-000000000000' },
  ],
  SLACK: [{ key: 'secret', label: 'Incoming webhook URL', placeholder: 'https://hooks.slack.com/services/…', secret: true }],
}

const HELP: Record<DocProviderKey, string> = {
  NOTION:
    'Each customer gets a page under this root, and every cycle adds a page under the customer. In Notion, share the root with your integration (••• → Connections).',
  CONFLUENCE: 'Each customer gets a page in this space, and every cycle adds a child page under it.',
  LINEAR: "Each customer gets a project in this team for the tasks CMO² tracks. The team id is a UUID, not the short key like 'CMO'.",
  SLACK: 'Cycle summaries and "customer connection is down" alerts go to this channel. Saving sends a test message.',
}

const SUMMARY: Partial<Record<DocProviderKey, (s: Record<string, string>) => string | null>> = {
  NOTION: (s) => (s.rootKind ? `Root ${s.rootKind}${s.botName ? ` · bot "${s.botName}"` : ''}` : null),
  CONFLUENCE: (s) => (s.spaceKey ? `Space ${s.spaceName ?? s.spaceKey} on ${s.siteUrl ?? ''}` : null),
  LINEAR: (s) => (s.teamName ? `Team ${s.teamName}` : null),
}

export default function WorkspaceTools() {
  const { data, isLoading, isError, error } = useQuery<DocConnection[]>({
    queryKey: ['docs', 'connections'],
    queryFn: listDocConnections,
  })

  return (
    <div className="mb-6">
      <h2 className="mb-1 font-semibold text-white">Workspace tools</h2>
      <p className="mb-4 text-sm text-gray-400">
        Where CMO² writes each customer's records. These are your own workspaces; customers never see CMO².
      </p>
      {isLoading && <GlassCard><p className="text-sm text-gray-400">Loading workspace tools…</p></GlassCard>}
      {isError && <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(error, 'Could not load workspace tools')}</p></GlassCard>}
      {data && (
        <div className="grid gap-4 lg:grid-cols-2">
          {data.map((c) => <ToolCard key={c.provider} conn={c} />)}
        </div>
      )}
    </div>
  )
}

function ToolCard({ conn }: { conn: DocConnection }) {
  const qc = useQueryClient()
  const [editing, setEditing] = useState(!conn.configured)
  const [values, setValues] = useState<SaveDocConnectionInput>(() => initialValues(conn))
  const [error, setError] = useState<string | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['docs'] })

  const save = useMutation({
    mutationFn: () => saveDocConnection(conn.provider, clean(values)),
    onSuccess: (saved) => {
      refresh()
      setValues(initialValues(saved))
      if (saved.status === 'CONNECTED') setEditing(false)
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not save')),
  })
  const test = useMutation({
    mutationFn: () => testDocConnection(conn.provider),
    onSuccess: refresh,
    onError: (err) => setError(apiErrorMessage(err, 'Could not check the connection')),
  })
  const remove = useMutation({
    mutationFn: () => removeDocConnection(conn.provider),
    onSuccess: () => {
      refresh()
      setEditing(true)
      setValues({})
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not remove')),
  })

  const summary = SUMMARY[conn.provider]?.(conn.settings) ?? null
  const busy = save.isPending || test.isPending || remove.isPending

  return (
    <GlassCard>
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="font-semibold text-white">{conn.name}</h3>
          <p className="mt-1 text-xs text-gray-400">{HELP[conn.provider]}</p>
        </div>
        <StatusBadge conn={conn} />
      </div>

      {conn.configured && (
        <div className="mt-3 space-y-1 text-xs text-gray-400">
          {summary && <div>{summary}</div>}
          <div>Secret {conn.maskedSecret}</div>
          {conn.lastCheckedAt && <div>Checked {new Date(conn.lastCheckedAt).toLocaleString()}</div>}
          {conn.status === 'ERROR' && conn.lastError && <div className="text-red-300">{conn.lastError}</div>}
        </div>
      )}

      {editing && (
        <form
          className="mt-4 space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            setError(null)
            save.mutate()
          }}
        >
          {FIELDS[conn.provider].map((f) => (
            <div key={f.key}>
              <label className="label">{f.label}</label>
              <input
                className="input"
                type={f.secret ? 'password' : 'text'}
                autoComplete="off"
                required={!f.optional && !(f.secret && conn.configured)}
                placeholder={f.secret && conn.configured ? `Leave blank to keep ${conn.maskedSecret}` : f.placeholder}
                value={values[f.key] ?? ''}
                onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
              />
            </div>
          ))}
          <div className="flex flex-wrap gap-2">
            <button type="submit" className="btn-primary" disabled={busy}>
              {save.isPending ? 'Checking…' : 'Save & check'}
            </button>
            {conn.configured && (
              <button type="button" className="btn-ghost" onClick={() => setEditing(false)} disabled={busy}>Cancel</button>
            )}
          </div>
        </form>
      )}

      {!editing && (
        <div className="mt-4 flex flex-wrap gap-2">
          <button className="btn-ghost" onClick={() => { setError(null); setEditing(true) }} disabled={busy}>Edit</button>
          <button className="btn-ghost" onClick={() => { setError(null); test.mutate() }} disabled={busy}>
            {test.isPending ? 'Checking…' : 'Check again'}
          </button>
          <button
            className="btn-ghost hover:text-red-300"
            onClick={() => {
              setError(null)
              if (window.confirm(`Remove ${conn.name}? CMO² stops writing there; existing pages stay.`)) remove.mutate()
            }}
            disabled={busy}
          >
            Remove
          </button>
        </div>
      )}

      {error && <p className="mt-3 text-sm text-red-300">{error}</p>}
    </GlassCard>
  )
}

function StatusBadge({ conn }: { conn: DocConnection }) {
  if (!conn.configured) {
    return <span className="rounded-full bg-white/10 px-2 py-0.5 text-[10px] uppercase text-gray-400">Not connected</span>
  }
  return conn.status === 'CONNECTED' ? (
    <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] uppercase text-emerald-300">Connected</span>
  ) : (
    <span className="rounded-full bg-red-500/15 px-2 py-0.5 text-[10px] uppercase text-red-300">Error</span>
  )
}

function initialValues(c: DocConnection): SaveDocConnectionInput {
  const s = c.settings ?? {}
  return {
    notionRootId: s.rootId,
    confluenceSiteUrl: s.siteUrl,
    confluenceEmail: s.email,
    confluenceSpaceKey: s.spaceKey,
    confluenceParentPageId: s.parentPageId,
    linearTeamId: s.teamId,
  }
}

/** Drop empty fields so a blank secret means "keep the stored one". */
function clean(v: SaveDocConnectionInput): SaveDocConnectionInput {
  const out: SaveDocConnectionInput = {}
  for (const [k, val] of Object.entries(v)) {
    if (typeof val === 'string' && val.trim() !== '') out[k as keyof SaveDocConnectionInput] = val.trim()
  }
  return out
}
