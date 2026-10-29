import type { ReactNode } from 'react'

/** Centered glass card used by all auth screens. */
export default function AuthLayout({
  title,
  subtitle,
  children,
}: {
  title: string
  subtitle?: string
  children: ReactNode
}) {
  return (
    <div className="grid min-h-screen place-items-center bg-app-gradient p-4">
      <div className="w-full max-w-md">
        <div className="mb-6 flex items-center justify-center gap-2">
          <div className="grid h-10 w-10 place-items-center rounded-xl bg-brand font-bold text-white">C²</div>
          <span className="text-lg font-semibold text-white">CMO² Command Platform</span>
        </div>
        <div className="glass p-7">
          <h1 className="text-xl font-semibold text-white">{title}</h1>
          {subtitle && <p className="mb-6 mt-1 text-sm text-gray-400">{subtitle}</p>}
          {children}
        </div>
      </div>
    </div>
  )
}
