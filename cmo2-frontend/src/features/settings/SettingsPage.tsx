import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import {
  changeRole,
  deactivateMember,
  exportTenantData,
  inviteMember,
  listTeam,
  reactivateMember,
} from '../team/teamApi'
import type { Role, TeamMember } from '../../lib/types'
import WorkspaceTools from './WorkspaceTools'

const ROLES: Role[] = ['OWNER', 'ADMIN', 'STRATEGIST', 'ANALYST', 'VIEWER']

const STATUS_STYLE: Record<string, string> = {
  ACTIVE: 'bg-emerald-500/15 text-emerald-300',
  PENDING_VERIFICATION: 'bg-amber-500/15 text-amber-300',
  LOCKED: 'bg-amber-500/15 text-amber-300',
  DISABLED: 'bg-white/10 text-gray-400',
}

export default function SettingsPage() {
  const qc = useQueryClient()
  const { data: team, isLoading } = useQuery<TeamMember[]>({ queryKey: ['team'], queryFn: listTeam })
  const refresh = () => qc.invalidateQueries({ queryKey: ['team'] })

  const exportData = useMutation({ mutationFn: () => exportTenantData() })

  return (
    <div>
      <PageHeader
        title="Settings & Admin"
        subtitle="Workspace tools, your team, roles, and your data."
        action={
          <button className="btn-ghost" disabled={exportData.isPending} onClick={() => exportData.mutate()}>
            {exportData.isPending ? 'Exporting…' : 'Export data'}
          </button>
        }
      />

      <RequireRole
        allow={['OWNER', 'ADMIN']}
        fallback={null}
      >
        <WorkspaceTools />
        <div className="mb-6">
          <InviteForm onDone={refresh} />
        </div>
      </RequireRole>

      <GlassCard>
        <h3 className="mb-3 font-semibold text-white">Team</h3>
        {isLoading ? (
          <p className="py-6 text-center text-sm text-gray-400">Loading team…</p>
        ) : (
          <div className="divide-y divide-white/5">
            {(team ?? []).map((m) => (
              <MemberRow key={m.id} member={m} onChanged={refresh} />
            ))}
          </div>
        )}
      </GlassCard>
    </div>
  )
}

function InviteForm({ onDone }: { onDone: () => void }) {
  const [email, setEmail] = useState('')
  const [fullName, setFullName] = useState('')
  const [role, setRole] = useState<Role>('ANALYST')
  const [error, setError] = useState<string | null>(null)

  const invite = useMutation({
    mutationFn: () => inviteMember({ email: email.trim(), fullName: fullName.trim(), role }),
    onSuccess: () => {
      setEmail('')
      setFullName('')
      setError(null)
      onDone()
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not send invite')),
  })

  return (
    <GlassCard>
      <h3 className="mb-3 font-semibold text-white">Invite a team member</h3>
      <form
        onSubmit={(e) => { e.preventDefault(); setError(null); invite.mutate() }}
        className="grid gap-3 sm:grid-cols-4"
      >
        {error && (
          <div className="sm:col-span-4 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {error}
          </div>
        )}
        <input className="input sm:col-span-2" type="email" required placeholder="teammate@company.com"
               value={email} onChange={(e) => setEmail(e.target.value)} />
        <input className="input" required placeholder="Full name"
               value={fullName} onChange={(e) => setFullName(e.target.value)} />
        <div className="flex gap-2">
          <select className="input" value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {ROLES.filter((r) => r !== 'OWNER').map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        </div>
        <div className="sm:col-span-4">
          <button type="submit" className="btn-primary" disabled={invite.isPending}>
            {invite.isPending ? 'Inviting…' : 'Send invite'}
          </button>
        </div>
      </form>
    </GlassCard>
  )
}

function MemberRow({ member, onChanged }: { member: TeamMember; onChanged: () => void }) {
  const role = useMutation({ mutationFn: (r: Role) => changeRole(member.id, r), onSuccess: onChanged })
  const deactivate = useMutation({ mutationFn: () => deactivateMember(member.id), onSuccess: onChanged })
  const reactivate = useMutation({ mutationFn: () => reactivateMember(member.id), onSuccess: onChanged })
  const disabled = member.status === 'DISABLED'

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 py-3">
      <div className="min-w-0">
        <div className="flex items-center gap-2">
          <span className="truncate text-sm font-medium text-white">{member.fullName || member.email}</span>
          <span className={['rounded px-1.5 py-0.5 text-[10px]', STATUS_STYLE[member.status] ?? 'bg-white/10 text-gray-300'].join(' ')}>
            {member.status.replace('_', ' ')}
          </span>
        </div>
        <div className="text-xs text-gray-500">{member.email} · {member.roles.join(', ')}</div>
      </div>
      <RequireRole allow={['OWNER', 'ADMIN']}>
        <div className="flex items-center gap-2">
          <RequireRole allow={['OWNER']}>
            <select
              className="input h-8 py-0 text-xs"
              value={member.roles[0] ?? 'VIEWER'}
              disabled={role.isPending || disabled}
              onChange={(e) => role.mutate(e.target.value as Role)}
            >
              {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
          </RequireRole>
          {disabled ? (
            <button className="btn-ghost text-xs" disabled={reactivate.isPending} onClick={() => reactivate.mutate()}>
              Reactivate
            </button>
          ) : (
            <button className="text-xs text-gray-400 hover:text-red-300" disabled={deactivate.isPending} onClick={() => deactivate.mutate()}>
              Deactivate
            </button>
          )}
        </div>
      </RequireRole>
    </div>
  )
}
