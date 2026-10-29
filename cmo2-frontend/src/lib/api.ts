import axios, {
  AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios'
import { sessionStore } from '../stores/session'
import type { AuthResponse } from './types'

const baseURL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

export const api = axios.create({ baseURL })

// Attach the bearer token to every request.
api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = sessionStore.getState().accessToken
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// --- Silent refresh on 401 -------------------------------------------------
// When a request 401s, attempt a single refresh, then replay the original request.
// Concurrent 401s share one in-flight refresh.
let refreshPromise: Promise<string | null> | null = null

async function refreshAccessToken(): Promise<string | null> {
  const { refreshToken, setSession, clear } = sessionStore.getState()
  if (!refreshToken) {
    clear()
    return null
  }
  try {
    // Bare axios (no interceptors) to avoid recursion.
    const res = await axios.post<AuthResponse>(`${baseURL}/api/auth/refresh`, { refreshToken })
    const data = res.data
    setSession(data.accessToken, data.refreshToken, data.user)
    return data.accessToken
  } catch {
    clear()
    return null
  }
}

api.interceptors.response.use(
  (res) => res,
  async (error: AxiosError) => {
    const original = error.config as (AxiosRequestConfig & { _retry?: boolean }) | undefined
    const status = error.response?.status
    const url = original?.url ?? ''

    const isAuthRoute = url.includes('/api/auth/')
    if (status === 401 && original && !original._retry && !isAuthRoute) {
      original._retry = true
      refreshPromise = refreshPromise ?? refreshAccessToken()
      const newToken = await refreshPromise
      refreshPromise = null

      if (newToken) {
        original.headers = { ...original.headers, Authorization: `Bearer ${newToken}` }
        return api(original)
      }
      // Refresh failed -> bounce to login.
      if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
        window.location.assign('/login')
      }
    }
    return Promise.reject(error)
  },
)

/** Extract a user-friendly message from an Axios error. */
export function apiErrorMessage(error: unknown, fallback = 'Something went wrong'): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { message?: string } | undefined
    return data?.message ?? error.message ?? fallback
  }
  return fallback
}
