import type { ReactNode } from 'react'
import { useSession } from '../../stores/session'
import type { Role } from '../../lib/types'

/**
 * Convenience UI gate — hides content unless the user holds one of the allowed roles.
 * This is NOT a security boundary; the backend re-checks every request via @PreAuthorize.
 */
export default function RequireRole({
  allow,
  children,
  fallback = null,
}: {
  allow: Role[]
  children: ReactNode
  fallback?: ReactNode
}) {
  const roles = useSession((s) => s.user?.roles ?? [])
  const permitted = roles.some((r) => allow.includes(r))
  return <>{permitted ? children : fallback}</>
}
