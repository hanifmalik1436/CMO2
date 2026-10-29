import { useState } from 'react'
import { signup } from './authApi'
import { apiErrorMessage } from '../../lib/api'
import AuthLayout from './AuthLayout'

/**
 * Public signup. Unlike the old /register flow, this does NOT create a CMO2 login — it
 * provisions a GoHighLevel sub-account and the registrant gets access to that by email.
 * There is nothing to sign in to here afterward, so this just shows a confirmation.
 */
export default function SignupPage() {
  const [businessName, setBusinessName] = useState('')
  const [contactName, setContactName] = useState('')
  const [contactEmail, setContactEmail] = useState('')
  const [industry, setIndustry] = useState('')
  const [website, setWebsite] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [confirmation, setConfirmation] = useState<string | null>(null)

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const res = await signup({
        businessName: businessName.trim(),
        contactName: contactName.trim(),
        contactEmail: contactEmail.trim(),
        industry: industry.trim() || undefined,
        website: website.trim() || undefined,
      })
      setConfirmation(res.message)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not set up your account'))
    } finally {
      setLoading(false)
    }
  }

  if (confirmation) {
    return (
      <AuthLayout title="You're all set" subtitle="We're provisioning your account">
        <p className="text-sm text-gray-300">{confirmation}</p>
      </AuthLayout>
    )
  }

  return (
    <AuthLayout title="Get started" subtitle="Tell us about your business — we'll set up your account">
      <form onSubmit={onSubmit} className="space-y-4">
        {error && (
          <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {error}
          </div>
        )}
        <div>
          <label className="label" htmlFor="businessName">Business name</label>
          <input id="businessName" required className="input" value={businessName}
                 onChange={(e) => setBusinessName(e.target.value)} placeholder="Acme Dental" />
        </div>
        <div>
          <label className="label" htmlFor="contactName">Your name</label>
          <input id="contactName" required className="input" value={contactName}
                 onChange={(e) => setContactName(e.target.value)} placeholder="Jane Doe" />
        </div>
        <div>
          <label className="label" htmlFor="contactEmail">Email</label>
          <input id="contactEmail" type="email" required className="input" value={contactEmail}
                 onChange={(e) => setContactEmail(e.target.value)} placeholder="you@business.com" />
        </div>
        <div>
          <label className="label" htmlFor="industry">Industry (optional)</label>
          <input id="industry" className="input" value={industry}
                 onChange={(e) => setIndustry(e.target.value)} placeholder="Healthcare" />
        </div>
        <div>
          <label className="label" htmlFor="website">Website (optional)</label>
          <input id="website" className="input" value={website}
                 onChange={(e) => setWebsite(e.target.value)} placeholder="https://business.com" />
        </div>
        <button type="submit" className="btn-primary w-full" disabled={loading}>
          {loading ? 'Setting up…' : 'Get started'}
        </button>
      </form>
    </AuthLayout>
  )
}
