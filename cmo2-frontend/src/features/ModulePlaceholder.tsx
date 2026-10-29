import { GlassCard, PageHeader } from '../components/ui/GlassCard'

/**
 * Stand-in for modules delivered in later phases (Strange Loop, Integrations,
 * Assets, Strategists, Analytics, Reports, Alerts, Billing). Keeps the prototype's
 * navigation complete while signalling exactly which phase delivers the feature.
 */
export default function ModulePlaceholder({ title, phase, blurb }: { title: string; phase: string; blurb: string }) {
  return (
    <div>
      <PageHeader title={title} subtitle={blurb} />
      <GlassCard>
        <div className="flex items-center gap-3 text-sm text-gray-300">
          <span className="rounded-full bg-brand/20 px-2.5 py-1 text-xs font-medium text-brand-glow">{phase}</span>
          <span>This module is scaffolded and arrives in a later build phase.</span>
        </div>
      </GlassCard>
    </div>
  )
}
