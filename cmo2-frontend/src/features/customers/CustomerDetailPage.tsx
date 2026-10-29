import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm, type Resolver } from 'react-hook-form'
import { z } from 'zod'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { AiCallsCard, MemoryCard, ScheduleCard } from './CustomerLoopPanels'
import { LearningPanel } from './LearningPanel'
import { getCustomerProfile, getIdentityBlock, getWeekTrends, updateCustomerProfile } from './customersApi'
import { METRIC_ORDER, kpiLabel, kpiValue } from '../integrations/kpiFormat'
import type { CustomerProfile, CustomerProfileInput, KpiTrend } from '../../lib/types'

// Mirrors UpdateProfileRequest's Bean Validation on the backend.
const schema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(160),
  industry: z.string().max(120).optional(),
  brandVoice: z.string().max(255).optional(),
  website: z.string().max(255).optional(),
  timezone: z.string().max(64).optional(),
  offer: z.string().max(1000).optional(),
  audience: z.string().max(1000).optional(),
  bannedTopics: z.string().max(1000).optional(),
  notes: z.string().max(8000).optional(),
})
type FormValues = z.infer<typeof schema>

const zodResolver: Resolver<FormValues> = async (values) => {
  const parsed = schema.safeParse(values)
  if (parsed.success) return { values: parsed.data, errors: {} }
  const errors: Record<string, { type: string; message: string }> = {}
  for (const issue of parsed.error.issues) {
    const key = String(issue.path[0])
    errors[key] ??= { type: issue.code, message: issue.message }
  }
  return { values: {}, errors }
}

export default function CustomerDetailPage() {
  const companyId = Number(useParams().companyId)
  const profile = useQuery<CustomerProfile>({
    queryKey: ['customers', companyId, 'profile'],
    queryFn: () => getCustomerProfile(companyId),
    enabled: Number.isFinite(companyId),
  })

  return (
    <div>
      <Link to="/customers" className="text-xs text-gray-400 underline">← Customers</Link>
      <PageHeader
        title={profile.data?.name ?? 'Customer'}
        subtitle="What CMO² knows about this business. Every AI call for this customer starts from it."
      />
      {profile.isLoading && <GlassCard><p className="text-sm text-gray-400">Loading customer…</p></GlassCard>}
      {profile.isError && (
        <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(profile.error, 'Could not load this customer')}</p></GlassCard>
      )}
      {profile.data && (
        <div className="grid gap-6 xl:grid-cols-3">
          <div className="space-y-6 xl:col-span-2">
            <ScheduleCard companyId={companyId} />
            <WeekFigures companyId={companyId} />
            <LearningPanel companyId={companyId} />
            <MemoryCard companyId={companyId} />
            <ProfileForm profile={profile.data} />
          </div>
          <div className="space-y-6">
            <GhlDetails profile={profile.data} />
            <IdentityPreview companyId={companyId} />
            <AiCallsCard companyId={companyId} />
          </div>
        </div>
      )}
    </div>
  )
}

function WeekFigures({ companyId }: { companyId: number }) {
  const { data, isLoading, isError, error } = useQuery<KpiTrend[]>({
    queryKey: ['customers', companyId, 'week'],
    queryFn: () => getWeekTrends(companyId),
  })
  const rows = (data ?? []).filter((t) => METRIC_ORDER.includes(t.metric))

  return (
    <GlassCard>
      <h3 className="font-semibold text-white">Last 7 days</h3>
      <p className="mt-1 text-xs text-gray-400">
        Today so far included, in the customer's timezone. The previous 7 days in grey. Totals like contacts show the
        latest reading.
      </p>
      {isLoading && <p className="mt-4 text-sm text-gray-400">Loading figures…</p>}
      {isError && <p className="mt-4 text-sm text-red-300">{apiErrorMessage(error, 'Could not load figures')}</p>}
      {data && rows.length === 0 && (
        <p className="mt-4 text-sm text-gray-400">No figures yet. They appear after this customer's GoHighLevel sub-account syncs.</p>
      )}
      {rows.length > 0 && (
        <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
          {rows.map((r) => (
            <div key={r.metric} className="rounded-lg border border-white/5 bg-white/5 p-3">
              <div className="text-[11px] uppercase text-gray-500">{kpiLabel(r.metric)}</div>
              <div className="mt-1 text-lg font-semibold text-white">{kpiValue(r.metric, r.current)}</div>
              <div className="text-xs text-gray-500">
                {r.previous == null ? 'no earlier data' : kpiValue(r.metric, r.previous)}
              </div>
            </div>
          ))}
        </div>
      )}
    </GlassCard>
  )
}

function ProfileForm({ profile }: { profile: CustomerProfile }) {
  const qc = useQueryClient()
  const [saved, setSaved] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)
  const { register, handleSubmit, reset, formState: { errors, isDirty } } = useForm<FormValues>({
    resolver: zodResolver,
    defaultValues: toForm(profile),
  })
  useEffect(() => reset(toForm(profile)), [profile, reset])

  const save = useMutation({
    mutationFn: (v: FormValues) => updateCustomerProfile(profile.companyId, clean(v)),
    onSuccess: (p) => {
      qc.setQueryData(['customers', profile.companyId, 'profile'], p)
      qc.invalidateQueries({ queryKey: ['customers', profile.companyId, 'identity'] })
      qc.invalidateQueries({ queryKey: ['companies'] })
      setSaved(true)
    },
    onError: (err) => setServerError(apiErrorMessage(err, 'Could not save')),
  })

  const field = (name: keyof FormValues, label: string, opts: { placeholder?: string; area?: boolean } = {}) => (
    <div className={opts.area ? 'sm:col-span-2' : undefined}>
      <label className="label">{label}</label>
      {opts.area ? (
        <textarea className="input min-h-[96px]" placeholder={opts.placeholder} {...register(name)} />
      ) : (
        <input className="input" placeholder={opts.placeholder} {...register(name)} />
      )}
      {errors[name] && <p className="mt-1 text-xs text-red-300">{errors[name]?.message}</p>}
    </div>
  )

  return (
    <GlassCard>
      <h3 className="font-semibold text-white">Profile</h3>
      <p className="mt-1 text-xs text-gray-400">Your description of the business. Customers never see this.</p>
      <RequireRole
        allow={['OWNER', 'ADMIN']}
        fallback={<p className="mt-4 text-sm text-gray-400">Only the owner or an admin can edit the profile.</p>}
      >
        <form
          className="mt-4 grid gap-4 sm:grid-cols-2"
          onSubmit={handleSubmit((v) => { setSaved(false); setServerError(null); save.mutate(v) })}
        >
          {field('name', 'Name')}
          {field('industry', 'Industry', { placeholder: 'e.g. Roofing' })}
          {field('website', 'Website')}
          {field('timezone', 'Timezone', { placeholder: 'e.g. Europe/London (GHL fills this in)' })}
          {field('brandVoice', 'Brand voice', { placeholder: 'e.g. Plain, friendly, no jargon' })}
          {field('offer', 'Offer', { placeholder: 'What they sell and the hook, e.g. free roof survey' })}
          {field('audience', 'Audience', { placeholder: 'Who they sell to', area: true })}
          {field('bannedTopics', 'Never mention', { placeholder: 'Topics, claims or names to avoid', area: true })}
          {field('notes', 'Your notes', { placeholder: 'Anything else the AI should know', area: true })}
          <div className="flex items-center gap-3 sm:col-span-2">
            <button type="submit" className="btn-primary" disabled={save.isPending || !isDirty}>
              {save.isPending ? 'Saving…' : 'Save profile'}
            </button>
            {saved && !isDirty && <span className="text-xs text-emerald-300">Saved</span>}
            {serverError && <span className="text-sm text-red-300">{serverError}</span>}
          </div>
        </form>
      </RequireRole>
    </GlassCard>
  )
}

function GhlDetails({ profile: p }: { profile: CustomerProfile }) {
  const rows: [string, string | null][] = [
    ['Name in GHL', p.ghlBusinessName],
    ['Location', [p.city, p.country].filter(Boolean).join(', ') || null],
    ['Timezone', p.timezone],
    ['Phone', p.phone],
    ['Email', p.email],
    ['Social accounts', p.socialChannels.length ? p.socialChannels.join(', ') : null],
  ]
  return (
    <GlassCard>
      <h3 className="font-semibold text-white">From GoHighLevel</h3>
      <p className="mt-1 text-xs text-gray-400">
        {p.factsSyncedAt
          ? `As the customer set it up in their sub-account. Updated ${new Date(p.factsSyncedAt).toLocaleString()}.`
          : 'Nothing yet. These fill in on the first live sync.'}
      </p>
      {p.ghlLocationId && <p className="mt-2 font-mono text-[11px] text-gray-500">{p.ghlLocationId}</p>}
      <dl className="mt-3 space-y-2 text-sm">
        {rows.map(([label, value]) => (
          <div key={label} className="flex justify-between gap-3">
            <dt className="text-gray-500">{label}</dt>
            <dd className="text-right text-gray-200">{value ?? '—'}</dd>
          </div>
        ))}
      </dl>
    </GlassCard>
  )
}

function IdentityPreview({ companyId }: { companyId: number }) {
  const { data, isLoading, isError, error } = useQuery<string>({
    queryKey: ['customers', companyId, 'identity'],
    queryFn: () => getIdentityBlock(companyId),
  })
  return (
    <GlassCard>
      <h3 className="font-semibold text-white">What the AI is told</h3>
      <p className="mt-1 text-xs text-gray-400">Added to every draft and strategist conversation for this customer.</p>
      <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST', 'ANALYST']} fallback={null}>
        {isLoading && <p className="mt-3 text-sm text-gray-400">Loading…</p>}
        {isError && <p className="mt-3 text-sm text-red-300">{apiErrorMessage(error, 'Could not load the preview')}</p>}
        {data && (
          <pre className="mt-3 max-h-[420px] overflow-auto whitespace-pre-wrap rounded-lg bg-black/30 p-3 text-xs text-gray-300">
            {data}
          </pre>
        )}
      </RequireRole>
    </GlassCard>
  )
}

function toForm(p: CustomerProfile): FormValues {
  return {
    name: p.name,
    industry: p.industry ?? '',
    brandVoice: p.brandVoice ?? '',
    website: p.website ?? '',
    timezone: p.timezone ?? '',
    offer: p.offer ?? '',
    audience: p.audience ?? '',
    bannedTopics: p.bannedTopics ?? '',
    notes: p.notes ?? '',
  }
}

function clean(v: FormValues): CustomerProfileInput {
  const out: CustomerProfileInput = { name: v.name.trim() }
  for (const [k, val] of Object.entries(v)) {
    if (k !== 'name' && typeof val === 'string') (out as unknown as Record<string, string>)[k] = val.trim()
  }
  return out
}
