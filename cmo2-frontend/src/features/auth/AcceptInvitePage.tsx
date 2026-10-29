import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { acceptInvite } from './authApi'
import { useSession } from '../../stores/session'
import { apiErrorMessage } from '../../lib/api'
import AuthLayout from './AuthLayout'

export default function AcceptInvitePage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const setSession = useSession((s) => s.setSession)

  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    if (password.length < 10) {
      setError('Password must be at least 10 characters')
      return
    }
    setLoading(true)
    try {
      const res = await acceptInvite(token, password)
      setSession(res.accessToken, res.refreshToken, res.user)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not accept the invite'))
    } finally {
      setLoading(false)
    }
  }

  if (!token) {
    return (
      <AuthLayout title="Invalid invite" subtitle="This link is missing its token.">
        <p className="text-center text-sm text-gray-400">
          Ask your admin to resend the invitation, or{' '}
          <Link to="/login" className="text-brand-glow hover:underline">sign in</Link>.
        </p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Join your team" subtitle="Set a password to activate your account">
      <form onSubmit={onSubmit} className="space-y-4">
        {error && (
          <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {error}
          </div>
        )}
        <div>
          <label className="label" htmlFor="password">Choose a password</label>
          <input id="password" type="password" required className="input" value={password}
                 onChange={(e) => setPassword(e.target.value)} placeholder="At least 10 characters" />
        </div>
        <button type="submit" className="btn-primary w-full" disabled={loading}>
          {loading ? 'Joining…' : 'Set password & join'}
        </button>
      </form>
    </AuthLayout>
  )
}
