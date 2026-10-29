import type { ConnectionHealthLevel } from '../../lib/types'

const COLORS: Record<ConnectionHealthLevel, string> = {
  GREEN: 'bg-emerald-400',
  YELLOW: 'bg-amber-400',
  RED: 'bg-red-400',
}

const LABELS: Record<ConnectionHealthLevel, string> = {
  GREEN: 'Healthy',
  YELLOW: 'Degraded',
  RED: 'Down',
}

export function TrafficLight({ health }: { health: ConnectionHealthLevel }) {
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-gray-300">
      <span className={`h-2.5 w-2.5 rounded-full ${COLORS[health]}`} />
      {LABELS[health]}
    </span>
  )
}
