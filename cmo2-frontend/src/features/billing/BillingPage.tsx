import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { fetchBillingSummary, openPortal, simulateActivation, startCheckout } from './billingApi'
import type { BillingSummary, PlanOption, UsageMeter } from '../../lib/types'

const STATUS_STYLE: Record<string, string> = {
  TRIAL: 'bg-sky-500/15 text-sky-300',
  ACTIVE: 'bg-emerald-500/15 text-emerald-300',
  PAST_DUE: 'bg-amber-500/15 text-amber-300',
  SUSPENDED: 'bg-red-500/15 text-red-300',
  CANCELED: 'bg-red-500/15 text-red-300',
}

export default function BillingPage() {
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()

  const { data: summary, isLoading } = useQuery<BillingSummary>({
    queryKey: ['billing', 'summary'],
    queryFn: fetchBillingSummary,
  })

  const refresh = () => qc.invalidateQueries({ queryKey: ['billing', 'summary'] })

  // Stub-gateway "checkout" redirects back here with ?demo_plan=PRO — auto-complete it.
  const demoPlan = params.get('demo_plan')
  const activate = useMutation({
    mutationFn: (plan: string) => simulateActivation(plan),
    onSuccess: () => refresh(),
  })
  useEffect(() => {
    if (demoPlan) {
      activate.mutate(demoPlan)
      params.delete('demo_plan')
      setParams(params, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [demoPlan])

  if (isLoading || !summary) {
    return (
      <div>
        <PageHeader title="Billing" subtitle="Plan, usage, and payment." />
        <GlassCard><p className="text-sm text-gray-400">Loading…</p></GlassCard>
      </div>
    )
  }

  return (
    <div>
      <PageHeader
        title="Billing"
        subtitle="Manage your subscription, monitor usage, and upgrade when you grow."
        action={
          summary.hasPaymentMethod ? (
            <RequireRole allow={['OWNER', 'ADMIN']}>
              <ManagePortalButton />
            </RequireRole>
          ) : null
        }
      />

      {summary.demo && (
        <div className="mb-4 rounded-lg border border-sky-500/30 bg-sky-500/10 px-3 py-2 text-xs text-sky-200">
          Demo billing mode — “checkout” activates instantly with no card. Configure Stripe
          (<code>cmo2.billing.provider=stripe</code>) for real hosted Checkout &amp; Portal.
        </div>
      )}

      {/* Current status */}
      <GlassCard className="mb-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-lg font-semibold text-white">{summary.planName} plan</h3>
              <span className={['rounded px-2 py-0.5 text-[11px]', STATUS_STYLE[summary.status] ?? 'bg-white/10 text-gray-300'].join(' ')}>
                {summary.status}
              </span>
            </div>
            <p className="mt-1 text-xs text-gray-400">
              {summary.status === 'TRIAL' && summary.trialEndsAt
                ? `Trial ends ${new Date(summary.trialEndsAt).toLocaleDateString()}`
                : summary.currentPeriodEnd
                  ? `Renews ${new Date(summary.currentPeriodEnd).toLocaleDateString()}`
                  : 'No active subscription'}
            </p>
          </div>
        </div>

        {/* Usage meters */}
        <div className="mt-5 grid gap-4 sm:grid-cols-2">
          {summary.usage.map((m) => <Meter key={m.key} meter={m} />)}
        </div>
      </GlassCard>

      {/* Plan cards */}
      <div className="grid gap-4 lg:grid-cols-3">
        {summary.plans.map((p) => (
          <PlanCard key={p.code} plan={p} demo={summary.demo} onChanged={refresh} />
        ))}
      </div>
    </div>
  )
}

function Meter({ meter }: { meter: UsageMeter }) {
  const unlimited = meter.limit < 0
  const pct = unlimited || meter.limit === 0 ? 0 : Math.min(100, Math.round((meter.used / meter.limit) * 100))
  const danger = pct >= 90
  return (
    <div>
      <div className="mb-1 flex items-center justify-between text-xs">
        <span className="text-gray-300">{meter.label}</span>
        <span className="text-gray-400">
          {formatNum(meter.used)} / {unlimited ? '∞' : formatNum(meter.limit)}
        </span>
      </div>
      <div className="h-2 overflow-hidden rounded-full bg-white/10">
        <div
          className={['h-full rounded-full', danger ? 'bg-red-400' : 'bg-brand'].join(' ')}
          style={{ width: `${unlimited ? 6 : pct}%` }}
        />
      </div>
    </div>
  )
}

function PlanCard({ plan, demo, onChanged }: { plan: PlanOption; demo: boolean; onChanged: () => void }) {
  const checkout = useMutation({
    mutationFn: () => startCheckout(plan.code),
    onSuccess: (res) => {
      // Real Stripe: redirect to hosted checkout. Demo: the URL points back here with ?demo_plan=.
      window.location.assign(res.url)
    },
  })
  const demoActivate = useMutation({
    mutationFn: () => simulateActivation(plan.code),
    onSuccess: onChanged,
  })

  return (
    <GlassCard className={plan.current ? 'ring-1 ring-brand' : ''}>
      <div className="flex items-baseline justify-between">
        <h3 className="text-lg font-semibold text-white">{plan.name}</h3>
        {plan.current && <span className="rounded bg-brand/20 px-2 py-0.5 text-[10px] uppercase text-brand-glow">Current</span>}
      </div>
      <div className="mt-1 text-2xl font-bold text-white">
        ${(plan.priceCents / 100).toFixed(0)}<span className="text-sm font-normal text-gray-400">/mo</span>
      </div>
      <ul className="mt-4 space-y-1.5 text-sm text-gray-300">
        <li>{cap(plan.maxCompanies)} companies</li>
        <li>{cap(plan.maxConnections)} platform connections</li>
        <li>{formatNum(plan.monthlyAiTokens)} AI tokens/mo</li>
        <li>{cap(plan.maxSeats)} team seats</li>
      </ul>
      {!plan.current && (
        <RequireRole allow={['OWNER', 'ADMIN']}>
          <button
            className="btn-primary mt-5 w-full"
            disabled={checkout.isPending || demoActivate.isPending}
            onClick={() => (demo ? demoActivate.mutate() : checkout.mutate())}
            title={apiErrorMessage(checkout.error ?? demoActivate.error, '')}
          >
            {demo ? 'Switch (demo)' : 'Upgrade'}
          </button>
        </RequireRole>
      )}
    </GlassCard>
  )
}

function ManagePortalButton() {
  const portal = useMutation({
    mutationFn: () => openPortal(),
    onSuccess: (url) => window.location.assign(url),
  })
  return (
    <button className="btn-ghost" disabled={portal.isPending} onClick={() => portal.mutate()}>
      {portal.isPending ? 'Opening…' : 'Manage billing'}
    </button>
  )
}

function cap(n: number): string {
  return n >= 2_000_000_000 ? 'Unlimited' : String(n)
}

function formatNum(n: number): string {
  if (n < 0) return '∞'
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(n % 1_000_000 === 0 ? 0 : 1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(n % 1_000 === 0 ? 0 : 1)}K`
  return String(n)
}
