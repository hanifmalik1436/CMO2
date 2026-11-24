import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { deleteSource, listSources, starSource } from './studioApi'
import { Icon, kindIcon } from './icons'
import type { ContentSourceSummary } from '../../lib/types'

const BUSY = ['PENDING', 'ANALYZING']

/**
 * Every source for the customer: where its idea search stands, when it was last used and how many
 * drafts it has produced. Create opens the generator for that source.
 */
export function ContentLibrary({ companyId, onCreate }: { companyId: number; onCreate: (sourceId: number) => void }) {
  const qc = useQueryClient()
  const key = ['studio', companyId, 'sources']
  const sources = useQuery<ContentSourceSummary[]>({
    queryKey: key,
    queryFn: () => listSources(companyId),
    // Poll only while an idea search is running.
    refetchInterval: (q) => (q.state.data?.some((s) => BUSY.includes(s.status)) ? 2000 : false),
  })
  const [search, setSearch] = useState('')
  const [favourites, setFavourites] = useState(false)
  const [newestFirst, setNewestFirst] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const star = useMutation({
    mutationFn: (s: ContentSourceSummary) => starSource(s.id, !s.starred),
    onSuccess: (updated) => qc.setQueryData<ContentSourceSummary[]>(key, (old) => old?.map((s) => (s.id === updated.id ? updated : s))),
    onError: (err) => setError(apiErrorMessage(err, 'Could not update the favourite')),
  })
  const remove = useMutation({
    mutationFn: (id: number) => deleteSource(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
    onError: (err) => setError(apiErrorMessage(err, 'Could not delete the source')),
  })

  const rows = useMemo(() => {
    const q = search.trim().toLowerCase()
    return (sources.data ?? [])
      .filter((s) => (!q || s.title.toLowerCase().includes(q)) && (!favourites || s.starred))
      .sort((a, b) => (newestFirst ? -1 : 1) * (new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()))
  }, [sources.data, search, favourites, newestFirst])

  return (
    <div>
      <div className="flex flex-wrap items-center gap-2">
        <label className="flex h-9 w-56 items-center gap-2 rounded-lg border border-white/10 bg-white/5 px-3 text-gray-400">
          <Icon name="search" className="h-3.5 w-3.5" />
          <input className="w-full bg-transparent text-sm text-gray-100 outline-none placeholder:text-gray-500" placeholder="Search"
                 value={search} onChange={(e) => setSearch(e.target.value)} />
        </label>
        <button type="button" onClick={() => setFavourites(!favourites)} title="Favourites only" aria-pressed={favourites}
                className={`grid h-9 w-9 place-items-center rounded-lg hover:bg-white/10 ${favourites ? 'text-yellow-400' : 'text-gray-400'}`}>
          <Icon name="star" filled={favourites} />
        </button>
        <span className="flex-1" />
        <button type="button" className="btn-ghost h-9 py-0 text-xs" onClick={() => setNewestFirst(!newestFirst)}>
          {newestFirst ? 'Newest first' : 'Oldest first'}
        </button>
      </div>
      <p className="my-4 text-right text-xs text-gray-400">{rows.length} of {sources.data?.length ?? 0}</p>
      {error && <p className="mb-3 text-sm text-red-300">{error}</p>}

      {sources.isLoading && <GlassCard><p className="text-sm text-gray-400">Loading sources…</p></GlassCard>}
      {sources.isError && <GlassCard><p className="text-sm text-red-300">{apiErrorMessage(sources.error, 'Could not load sources')}</p></GlassCard>}
      {sources.data && sources.data.length === 0 && (
        <GlassCard>
          <p className="py-6 text-center text-sm text-gray-400">
            No sources yet. Record a voice note or bring in a transcript, file or link from the Studio tab.
          </p>
        </GlassCard>
      )}

      {rows.length > 0 && (
        <div className="overflow-x-auto rounded-xl border border-white/10">
          <table className="w-full min-w-[820px] text-sm">
            <thead>
              <tr className="text-left text-[11px] text-gray-400">
                <th className="px-4 py-2 font-normal">Title</th>
                <th className="px-2 py-2 text-center font-normal">Source</th>
                <th className="px-2 py-2 font-normal">Created</th>
                <th className="px-2 py-2 font-normal">Last used</th>
                <th className="px-2 py-2 text-right font-normal">Generated</th>
                <th className="px-2 py-2" />
                <th className="px-4 py-2 text-right font-normal">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((s) => (
                <tr key={s.id} className="border-t border-white/5 hover:bg-white/[0.02]">
                  <td className="max-w-0 px-4 py-2.5">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-medium text-white" title={s.title}>{s.title}</span>
                      <StatusPill source={s} />
                    </div>
                  </td>
                  <td className="px-2 text-center">
                    <span className="inline-grid h-6 w-6 place-items-center rounded-md bg-white/10 text-gray-300" title={s.kind.toLowerCase()}>
                      <Icon name={kindIcon(s.kind)} className="h-3.5 w-3.5" />
                    </span>
                  </td>
                  <td className="px-2 text-xs text-gray-400">{day(s.createdAt)}</td>
                  <td className={`px-2 text-xs ${s.lastUsedAt ? 'text-gray-400' : 'text-orange-400'}`}>{s.lastUsedAt ? day(s.lastUsedAt) : 'Never'}</td>
                  <td className="px-2 text-right text-xs text-gray-400">{s.generated}</td>
                  <td className="px-2">
                    <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']}
                                 fallback={s.starred ? <span className="text-yellow-400"><Icon name="star" filled className="h-3.5 w-3.5" /></span> : null}>
                      <button type="button" aria-label={s.starred ? 'Remove from favourites' : 'Add to favourites'} onClick={() => star.mutate(s)}
                              className={`grid h-6 w-6 place-items-center ${s.starred ? 'text-yellow-400' : 'text-gray-500 hover:text-gray-300'}`}>
                        <Icon name="star" filled={s.starred} className="h-3.5 w-3.5" />
                      </button>
                    </RequireRole>
                  </td>
                  <td className="px-4">
                    <div className="flex items-center justify-end gap-2">
                      <button type="button" className="btn-primary h-7 px-2.5 py-0 text-xs" onClick={() => onCreate(s.id)}>
                        Create <Icon name="arrow" className="h-3 w-3" />
                      </button>
                      <RequireRole allow={['OWNER', 'ADMIN']} fallback={null}>
                        <button type="button" aria-label={`Delete ${s.title}`} disabled={remove.isPending}
                                className="grid h-7 w-7 place-items-center rounded text-gray-500 hover:bg-white/10 hover:text-red-300"
                                onClick={() => { if (window.confirm(`Delete "${s.title}"? Drafts already made from it stay.`)) remove.mutate(s.id) }}>
                          <Icon name="trash" className="h-3.5 w-3.5" />
                        </button>
                      </RequireRole>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {sources.data && sources.data.length > 0 && rows.length === 0 && (
        <p className="py-8 text-center text-sm text-gray-500">No sources match.</p>
      )}
    </div>
  )
}

function StatusPill({ source: s }: { source: ContentSourceSummary }) {
  if (BUSY.includes(s.status)) {
    return (
      <span className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-white/10 px-2 py-0.5 text-[11px] text-gray-200">
        <span className="h-2.5 w-2.5 animate-spin rounded-full border border-gray-300 border-t-transparent" /> Finding ideas…
      </span>
    )
  }
  if (s.status === 'TOO_SHORT') {
    return <span className="shrink-0 rounded-full border border-amber-300/40 bg-amber-200/10 px-2 py-0.5 text-[11px] text-amber-200" title={s.statusNote ?? undefined}>Too short for ideas</span>
  }
  if (s.status === 'FAILED') {
    return <span className="shrink-0 rounded-full bg-red-500/15 px-2 py-0.5 text-[11px] text-red-300" title={s.statusNote ?? undefined}>Idea search failed</span>
  }
  return <span className="shrink-0 text-[11px] text-gray-500">{s.ideaCount} idea{s.ideaCount === 1 ? '' : 's'}</span>
}

function day(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}
