import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import { apiErrorMessage } from '../../lib/api'
import { connectAgency } from './customersApi'

/**
 * Where GoHighLevel sends you back after installing the app on your agency. The code it carries
 * is single-use, so the exchange runs exactly once (React's dev mode runs effects twice).
 */
export default function GhlCallbackPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const started = useRef(false)
  const [error, setError] = useState<string | null>(null)
  const code = params.get('code')

  useEffect(() => {
    if (started.current) return
    started.current = true
    if (!code) {
      setError('GoHighLevel did not send back an authorization code. Start the connection again from Customers.')
      return
    }
    connectAgency(code)
      .then(() => {
        qc.invalidateQueries({ queryKey: ['ghl'] })
        navigate('/customers', { replace: true })
      })
      .catch((err) => setError(apiErrorMessage(err, 'Could not connect your GoHighLevel agency')))
  }, [code, navigate, qc])

  return (
    <div>
      <PageHeader title="Connecting GoHighLevel" />
      <GlassCard>
        {error ? (
          <div>
            <p className="text-sm text-red-300">{error}</p>
            <Link to="/customers" className="mt-4 inline-block text-sm text-gray-300 underline">Back to Customers</Link>
          </div>
        ) : (
          <p className="text-sm text-gray-400">Finishing the connection with your agency…</p>
        )}
      </GlassCard>
    </div>
  )
}
