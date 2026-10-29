import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { archiveCompany, createCompany, listCompanies } from './companiesApi'
import type { Company } from '../../lib/types'

export default function CompaniesPage() {
  const qc = useQueryClient()
  const [showForm, setShowForm] = useState(false)

  const { data, isLoading, isError, error } = useQuery<Company[]>({
    queryKey: ['companies'],
    queryFn: listCompanies,
  })

  const archive = useMutation({
    mutationFn: archiveCompany,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['companies'] }),
  })

  const companies = data ?? []
  const active = companies.filter((c) => c.status === 'ACTIVE')

  return (
    <div>
      <PageHeader
        title="Companies"
        subtitle="Brands you manage. Switching scope re-scopes every module to the selected company."
        action={
          <RequireRole allow={['OWNER', 'ADMIN']}>
            <button className="btn-primary" onClick={() => setShowForm((v) => !v)}>
              {showForm ? 'Close' : '+ New company'}
            </button>
          </RequireRole>
        }
      />

      {showForm && (
        <div className="mb-6">
          <NewCompanyForm onDone={() => setShowForm(false)} />
        </div>
      )}

      {isLoading && <GlassCard><p className="text-sm text-gray-400">Loading companies…</p></GlassCard>}

      {isError && (
        <GlassCard>
          <p className="text-sm text-red-300">{apiErrorMessage(error, 'Failed to load companies')}</p>
        </GlassCard>
      )}

      {!isLoading && !isError && active.length === 0 && (
        <GlassCard>
          <div className="py-8 text-center">
            <p className="text-sm text-gray-300">No companies yet.</p>
            <p className="mt-1 text-xs text-gray-500">Create your first brand to start onboarding it.</p>
          </div>
        </GlassCard>
      )}

      {active.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {active.map((c) => (
            <GlassCard key={c.id}>
              <div className="flex items-start justify-between">
                <div>
                  <h3 className="font-semibold text-white">{c.name}</h3>
                  <p className="text-xs text-gray-400">{c.industry || 'Industry not set'}</p>
                </div>
                <span className="rounded-full bg-emerald-500/15 px-2 py-0.5 text-[10px] uppercase text-emerald-300">
                  {c.status}
                </span>
              </div>
              {c.website && <p className="mt-3 truncate text-xs text-gray-400">{c.website}</p>}
              <div className="mt-4 flex items-center justify-between">
                <span className="text-[11px] text-gray-500">Onboarding step {c.onboardingStep}/5</span>
                <RequireRole allow={['OWNER', 'ADMIN']}>
                  <button
                    className="text-xs text-gray-400 hover:text-red-300"
                    onClick={() => archive.mutate(c.id)}
                    disabled={archive.isPending}
                  >
                    Archive
                  </button>
                </RequireRole>
              </div>
            </GlassCard>
          ))}
        </div>
      )}
    </div>
  )
}

function NewCompanyForm({ onDone }: { onDone: () => void }) {
  const qc = useQueryClient()
  const [name, setName] = useState('')
  const [industry, setIndustry] = useState('')
  const [website, setWebsite] = useState('')
  const [error, setError] = useState<string | null>(null)

  const create = useMutation({
    mutationFn: createCompany,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['companies'] })
      onDone()
    },
    onError: (err) => setError(apiErrorMessage(err, 'Could not create company')),
  })

  function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    create.mutate({ name: name.trim(), industry: industry.trim() || undefined, website: website.trim() || undefined })
  }

  return (
    <GlassCard>
      <form onSubmit={submit} className="grid gap-4 sm:grid-cols-3">
        {error && (
          <div className="sm:col-span-3 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {error}
          </div>
        )}
        <div>
          <label className="label">Name *</label>
          <input className="input" required value={name} onChange={(e) => setName(e.target.value)} placeholder="Acme Co." />
        </div>
        <div>
          <label className="label">Industry</label>
          <input className="input" value={industry} onChange={(e) => setIndustry(e.target.value)} placeholder="SaaS" />
        </div>
        <div>
          <label className="label">Website</label>
          <input className="input" value={website} onChange={(e) => setWebsite(e.target.value)} placeholder="https://acme.com" />
        </div>
        <div className="sm:col-span-3 flex gap-2">
          <button type="submit" className="btn-primary" disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create company'}
          </button>
          <button type="button" className="btn-ghost" onClick={onDone}>Cancel</button>
        </div>
      </form>
    </GlassCard>
  )
}
