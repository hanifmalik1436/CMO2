import { useSearchParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard } from '../../components/ui/GlassCard'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import { StudioStart } from './StudioStart'
import { ContentLibrary } from './ContentLibrary'
import { ContentGenerator } from './ContentGenerator'
import { Icon } from './icons'
import type { Company } from '../../lib/types'

type View = 'studio' | 'library' | 'generator'

/**
 * Content Studio: record or bring in a source (Studio), find it again (Content Library), and turn
 * it into drafts for every platform (generator). The customer and view live in the URL, so the
 * browser's back button works.
 */
export default function ContentStudioPage() {
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()
  const companies = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const active = (companies.data ?? []).filter((c) => c.status === 'ACTIVE')
  const companyId = Number(params.get('companyId')) || active[0]?.id
  const company = active.find((c) => c.id === companyId)
  const sourceId = Number(params.get('sourceId')) || null
  const requested = (params.get('view') as View | null) ?? 'studio'
  const view: View = requested === 'generator' && !sourceId ? 'library' : requested

  const go = (next: View, extra: Record<string, string> = {}) => {
    const p: Record<string, string> = { view: next, ...extra }
    if (companyId) p.companyId = String(companyId)
    setParams(p)
  }

  return (
    <div className="-m-2 min-h-[calc(100vh-6rem)] rounded-2xl bg-[#121212]/60 p-4 sm:p-6">
      <div className="mb-6 flex flex-wrap items-center gap-4 border-b border-white/10 pb-3">
        <h1 className="text-lg font-semibold text-white">Content Studio</h1>
        <nav className="flex gap-1" aria-label="Content Studio">
          <Tab active={view === 'studio'} onClick={() => go('studio')} label="Studio" />
          <Tab active={view !== 'studio'} onClick={() => go('library')} label="Content Library" />
        </nav>
        <span className="flex-1" />
        {active.length > 0 && companyId && (
          <label className="flex items-center gap-2 text-xs text-gray-400">
            Customer
            <select className="input h-8 w-56 py-0 text-xs" value={companyId}
                    onChange={(e) => setParams({ view: view === 'generator' ? 'library' : view, companyId: e.target.value })}>
              {active.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
        )}
      </div>

      {companies.isLoading && <GlassCard><p className="text-sm text-gray-400">Loading customers…</p></GlassCard>}
      {companies.isError && <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(companies.error, 'Could not load customers')}</p></GlassCard>}
      {companies.data && active.length === 0 && (
        <GlassCard><p className="text-sm text-gray-400">No customers yet. Enable one on the Customers page first.</p></GlassCard>
      )}

      {companyId && view === 'studio' && (
        <StudioStart
          key={companyId}
          companyId={companyId}
          onAdded={(d) => {
            qc.invalidateQueries({ queryKey: ['studio', companyId, 'sources'] })
            qc.setQueryData(['studio', 'source', d.source.id], d)
            go('library')
          }}
        />
      )}
      {companyId && view === 'library' && (
        <ContentLibrary key={companyId} companyId={companyId} onCreate={(id) => go('generator', { sourceId: String(id) })} />
      )}
      {companyId && view === 'generator' && sourceId && (
        <ContentGenerator key={`${companyId}-${sourceId}`} companyId={companyId} companyName={company?.name ?? 'this customer'}
                          sourceId={sourceId} onBack={() => go('library')} />
      )}
    </div>
  )
}

function Tab({ active, onClick, label }: { active: boolean; onClick: () => void; label: string }) {
  return (
    <button type="button" onClick={onClick} aria-current={active ? 'page' : undefined}
            className={`flex items-center gap-2 border-b-2 px-3 py-1.5 text-sm transition ${active ? 'border-brand text-white' : 'border-transparent text-gray-400 hover:text-white'}`}>
      <Icon name={label === 'Studio' ? 'mic' : 'paste'} className="h-3.5 w-3.5" />
      {label}
    </button>
  )
}
