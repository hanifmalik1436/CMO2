import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { registerAccount } from './authApi'
import { useSession } from '../../stores/session'
import { apiErrorMessage } from '../../lib/api'
import AuthLayout from './AuthLayout'

export default function RegisterPage() {
  const navigate = useNavigate()
  const setSession = useSession((s) => s.setSession)

  const [fullName, setFullName] = useState('')
  const [organizationName, setOrganizationName] = useState('')
  const [email, setEmail] = useState('')
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
      const res = await registerAccount({
        fullName: fullName.trim(),
        organizationName: organizationName.trim(),
        email: email.trim(),
        password,
      })
      setSession(res.accessToken, res.refreshToken, res.user)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not create the account'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout title="Create your workspace" subtitle="Start your 14-day trial — no card required">
      <form onSubmit={onSubmit} className="space-y-4">
        {error && (
          <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {error}
          </div>
        )}
        <div>
          <label className="label" htmlFor="org">Organization name</label>
          <input id="org" required className="input" value={organizationName}
                 onChange={(e) => setOrganizationName(e.target.value)} placeholder="Acme Marketing Co." />
        </div>
        <div>
          <label className="label" htmlFor="name">Your name</label>
          <input id="name" required className="input" value={fullName}
                 onChange={(e) => setFullName(e.target.value)} placeholder="Jane Doe" />
        </div>
        <div>
          <label className="label" htmlFor="email">Work email</label>
          <input id="email" type="email" required className="input" value={email}
                 onChange={(e) => setEmail(e.target.value)} placeholder="you@agency.com" />
        </div>
        <div>
          <label className="label" htmlFor="password">Password</label>
          <input id="password" type="password" required className="input" value={password}
                 onChange={(e) => setPassword(e.target.value)} placeholder="At least 10 characters" />
        </div>
        <button type="submit" className="btn-primary w-full" disabled={loading}>
          {loading ? 'Creating…' : 'Create workspace'}
        </button>
      </form>
      <p className="mt-6 text-center text-sm text-gray-400">
        Already have an account?{' '}
        <Link to="/login" className="text-brand-glow hover:underline">Sign in</Link>
      </p>
    </AuthLayout>
  )
}
