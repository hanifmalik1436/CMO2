import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { useSession } from '../stores/session'
import type { TenantInfo } from '../lib/types'

interface NavItem {
  to: string
  label: string
  icon: string
  ready?: boolean
}

// Mirrors the prototype's 11-module navigation. Items without `ready` are
// scaffolded placeholders for later phases (kept visible to match the design spec).
const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: '▤', ready: true },
  { to: '/customers', label: 'Customers', icon: '◉', ready: true },
  { to: '/companies', label: 'Companies', icon: '◳', ready: true },
  { to: '/strange-loop', label: 'Strange Loop', icon: '↻', ready: true },
  { to: '/integrations', label: 'Integrations', icon: '⇄', ready: true },
  { to: '/assets', label: 'Asset Generator', icon: '✦', ready: true },
  { to: '/approvals', label: 'Approvals', icon: '✓', ready: true },
  { to: '/strategists', label: 'AI Strategists', icon: '◎', ready: true },
  { to: '/analytics', label: 'Analytics', icon: '▲', ready: true },
  { to: '/reports', label: 'Reports', icon: '▦', ready: true },
  { to: '/alerts', label: 'Alerts', icon: '!', ready: true },
  { to: '/settings', label: 'Settings', icon: '⚙', ready: true },
  { to: '/billing', label: 'Billing', icon: '$', ready: true },
]

export default function AppShell() {
  const navigate = useNavigate()
  const user = useSession((s) => s.user)
  const clear = useSession((s) => s.clear)
  const refreshToken = useSession((s) => s.refreshToken)

  const { data: tenant } = useQuery<TenantInfo>({
    queryKey: ['tenant', 'current'],
    queryFn: async () => (await api.get<TenantInfo>('/api/tenant/current')).data,
  })

  async function logout() {
    try {
      if (refreshToken) await api.post('/api/auth/logout', { refreshToken })
    } catch {
      /* best effort */
    }
    clear()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-screen bg-app-gradient">
      <div className="flex min-h-screen">
        {/* Sidebar */}
        <aside className="hidden w-60 shrink-0 flex-col border-r border-white/10 bg-ink/60 p-4 backdrop-blur-md md:flex">
          <div className="mb-6 flex items-center gap-2 px-2">
            <div className="grid h-9 w-9 place-items-center rounded-xl bg-brand font-bold text-white">C²</div>
            <div className="leading-tight">
              <div className="text-sm font-semibold text-white">CMO²</div>
              <div className="text-[11px] text-gray-400">Command Platform</div>
            </div>
          </div>

          <nav className="flex-1 space-y-1">
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  [
                    'flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition',
                    isActive ? 'bg-brand/20 text-white' : 'text-gray-400 hover:bg-white/5 hover:text-gray-200',
                  ].join(' ')
                }
              >
                <span className="w-4 text-center opacity-80">{item.icon}</span>
                <span>{item.label}</span>
                {!item.ready && (
                  <span className="ml-auto rounded bg-white/10 px-1.5 py-0.5 text-[9px] uppercase text-gray-400">
                    soon
                  </span>
                )}
              </NavLink>
            ))}
          </nav>
        </aside>

        {/* Main column */}
        <div className="flex min-w-0 flex-1 flex-col">
          {/* Topbar */}
          <header className="flex items-center gap-4 border-b border-white/10 bg-ink/40 px-6 py-3 backdrop-blur-md">
            <div className="min-w-0">
              <div className="truncate text-sm font-semibold text-white">{tenant?.name ?? 'Loading…'}</div>
              <div className="text-[11px] text-gray-400">
                {tenant ? `${tenant.planCode} · ${tenant.status}` : ' '}
              </div>
            </div>
            <div className="ml-auto flex items-center gap-3">
              <div className="hidden text-right sm:block">
                <div className="text-sm text-gray-200">{user?.fullName ?? user?.email}</div>
                <div className="text-[11px] text-gray-500">{user?.roles?.join(', ')}</div>
              </div>
              <button className="btn-ghost" onClick={logout}>
                Sign out
              </button>
            </div>
          </header>

          <main className="flex-1 overflow-y-auto p-6">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  )
}
