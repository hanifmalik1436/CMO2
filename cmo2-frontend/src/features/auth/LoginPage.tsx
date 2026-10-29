import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { login } from './authApi'
import { useSession } from '../../stores/session'
import { apiErrorMessage } from '../../lib/api'
import AuthLayout from './AuthLayout'

export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const setSession = useSession((s) => s.setSession)

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  // Keep the query string too: the GHL install callback carries its code there.
  const fromLocation = (location.state as { from?: { pathname: string; search?: string } } | null)?.from
  const from = fromLocation ? fromLocation.pathname + (fromLocation.search ?? '') : '/dashboard'

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const res = await login(email.trim(), password)
      setSession(res.accessToken, res.refreshToken, res.user)
      navigate(from, { replace: true })
    } catch (err) {
      setError(apiErrorMessage(err, 'Invalid email or password'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout title="Welcome back" subtitle="Sign in to your CMO² command center">
      <form onSubmit={onSubmit} className="space-y-4">
        {error && (
          <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {error}
          </div>
        )}
        <div>
          <label className="label" htmlFor="email">Email</label>
          <input id="email" type="email" required className="input" value={email}
                 onChange={(e) => setEmail(e.target.value)} placeholder="you@agency.com" />
        </div>
        <div>
          <label className="label" htmlFor="password">Password</label>
          <input id="password" type="password" required className="input" value={password}
                 onChange={(e) => setPassword(e.target.value)} placeholder="••••••••••" />
        </div>
        <button type="submit" className="btn-primary w-full" disabled={loading}>
          {loading ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
      {/* This is CMO2's internal operator console now — accounts are added via team invite
          (see TeamController), not self-registration, so there's no public "create an
          account" link here. /register now provisions a client's GHL sub-account instead. */}
    </AuthLayout>
  )
}
