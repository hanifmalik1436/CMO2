import axios from 'axios'
import { api } from '../../lib/api'
import type { AuthResponse } from '../../lib/types'

const baseURL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

// Login/register use a bare client (no auth header / refresh interceptor needed).
const authClient = axios.create({ baseURL })

export interface RegisterInput {
  email: string
  password: string
  fullName: string
  organizationName: string
}

export async function registerAccount(input: RegisterInput): Promise<AuthResponse> {
  const res = await authClient.post<AuthResponse>('/api/auth/register', input)
  return res.data
}

export async function login(email: string, password: string): Promise<AuthResponse> {
  const res = await authClient.post<AuthResponse>('/api/auth/login', { email, password })
  return res.data
}

export async function verifyEmail(token: string): Promise<void> {
  await authClient.post(`/api/auth/verify-email?token=${encodeURIComponent(token)}`)
}

export async function acceptInvite(token: string, password: string): Promise<AuthResponse> {
  const res = await authClient.post<AuthResponse>('/api/auth/accept-invite', { token, password })
  return res.data
}

// Authenticated request — refresh user details on demand.
export async function fetchMe() {
  return (await api.get('/api/me')).data
}
