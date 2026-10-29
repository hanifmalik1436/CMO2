import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { UserSummary } from '../lib/types'

/**
 * Client-side session state (Zustand). Holds the access/refresh tokens and the
 * current user. Persisted to localStorage so a page reload keeps the user signed in;
 * the access token is re-validated (and silently refreshed) by the Axios layer.
 *
 * NOTE: this is convenience state only — the backend re-verifies every request.
 */
interface SessionState {
  accessToken: string | null
  refreshToken: string | null
  user: UserSummary | null
  setSession: (accessToken: string, refreshToken: string, user: UserSummary) => void
  setAccessToken: (accessToken: string) => void
  setUser: (user: UserSummary) => void
  clear: () => void
  isAuthenticated: () => boolean
}

export const useSession = create<SessionState>()(
  persist(
    (set, get) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      setSession: (accessToken, refreshToken, user) => set({ accessToken, refreshToken, user }),
      setAccessToken: (accessToken) => set({ accessToken }),
      setUser: (user) => set({ user }),
      clear: () => set({ accessToken: null, refreshToken: null, user: null }),
      isAuthenticated: () => !!get().accessToken,
    }),
    { name: 'cmo2.session' },
  ),
)

// Non-React access for the Axios interceptors.
export const sessionStore = useSession
