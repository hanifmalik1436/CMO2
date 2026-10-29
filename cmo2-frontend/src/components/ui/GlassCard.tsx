import type { ReactNode } from 'react'

export function GlassCard({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`glass p-5 ${className}`}>{children}</div>
}

export function PageHeader({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return (
    <div className="mb-6 flex items-end justify-between gap-4">
      <div>
        <h1 className="text-xl font-semibold text-white">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-gray-400">{subtitle}</p>}
      </div>
      {action}
    </div>
  )
}
