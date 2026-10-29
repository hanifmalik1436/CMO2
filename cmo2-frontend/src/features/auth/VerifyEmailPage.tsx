import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { verifyEmail } from './authApi'
import { apiErrorMessage } from '../../lib/api'
import AuthLayout from './AuthLayout'

type Status = 'verifying' | 'success' | 'error'

export default function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [status, setStatus] = useState<Status>('verifying')
  const [message, setMessage] = useState('')
  const ran = useRef(false)

  useEffect(() => {
    if (ran.current) return // guard against StrictMode double-invoke (token is single-use)
    ran.current = true

    if (!token) {
      setStatus('error')
      setMessage('Missing verification token.')
      return
    }
    verifyEmail(token)
      .then(() => {
        setStatus('success')
        setMessage('Your email is verified. You can sign in now.')
      })
      .catch((err) => {
        setStatus('error')
        setMessage(apiErrorMessage(err, 'This verification link is invalid or has expired.'))
      })
  }, [token])

  return (
    <AuthLayout title="Email verification">
      <div className="text-sm text-gray-300">
        {status === 'verifying' && <p>Verifying your email…</p>}
        {status !== 'verifying' && <p>{message}</p>}
      </div>
      <Link to="/login" className="btn-primary mt-6 w-full">Go to sign in</Link>
    </AuthLayout>
  )
}
