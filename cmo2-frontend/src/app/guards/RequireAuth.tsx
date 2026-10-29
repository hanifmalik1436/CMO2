import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useSession } from '../../stores/session'

/** Gate for authenticated routes. Redirects to /login, preserving the intended path. */
export default function RequireAuth() {
  const isAuthed = useSession((s) => !!s.accessToken)
  const location = useLocation()

  if (!isAuthed) {
    return <Navigate to="/login" replace state={{ from: location }} />
  }
  return <Outlet />
}
