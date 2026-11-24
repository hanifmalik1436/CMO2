import { useEffect, useState, type ReactNode } from 'react'
import { useMutation } from '@tanstack/react-query'
import { apiErrorMessage } from '../../lib/api'
import { createSource, linkSource } from './studioApi'
import type { ContentSourceDetail, ContentSourceKind } from '../../lib/types'

const MAX_CHARS = 100_000

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/60 p-4" role="dialog" aria-modal="true" aria-label={title}
         onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="glass w-full max-w-2xl bg-ink/95 p-5">
        <div className="mb-4 flex items-center justify-between">
          <h3 className="font-semibold text-white">{title}</h3>
          <button className="text-sm text-gray-400 hover:text-white" onClick={onClose} aria-label="Close">✕</button>
        </div>
        {children}
      </div>
    </div>
  )
}

/** Paste Text (pick what it is) or Blank Canvas (write from scratch, saved as notes). */
export function TextSourceDialog({ companyId, blank, onClose, onSaved }: {
  companyId: number
  blank: boolean
  onClose: () => void
  onSaved: (d: ContentSourceDetail) => void
}) {
  const [title, setTitle] = useState('')
  const [kind, setKind] = useState<ContentSourceKind>(blank ? 'NOTES' : 'TRANSCRIPT')
  const [body, setBody] = useState('')
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => createSource(companyId, { title: title.trim(), kind, body: body.trim() }),
    onSuccess: onSaved,
    onError: (err) => setError(apiErrorMessage(err, 'Could not save')),
  })
  return (
    <Modal title={blank ? 'Blank canvas' : 'Paste text'} onClose={onClose}>
      <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate() }}>
        <div className="grid gap-3 sm:grid-cols-3">
          <div className={blank ? 'sm:col-span-3' : 'sm:col-span-2'}>
            <label className="label">Title</label>
            <input className="input" required maxLength={200} autoFocus value={title} onChange={(e) => setTitle(e.target.value)}
                   placeholder={blank ? 'e.g. Thoughts on this month’s offer' : 'e.g. Podcast with the founder'} />
          </div>
          {!blank && (
            <div>
              <label className="label">What it is</label>
              <select className="input" value={kind} onChange={(e) => setKind(e.target.value as ContentSourceKind)}>
                <option value="TRANSCRIPT">Transcript</option>
                <option value="NOTES">Notes</option>
                <option value="ARTICLE">Article</option>
              </select>
            </div>
          )}
        </div>
        <div>
          <label className="label">{blank ? 'Write your thoughts' : 'Text'}</label>
          <textarea className="input min-h-[240px] text-sm" required maxLength={MAX_CHARS} value={body} onChange={(e) => setBody(e.target.value)}
                    placeholder={blank ? 'Answer a question, share a story, argue a point. Rough is fine.' : 'Paste the transcript, notes or article here'} />
          <p className="mt-1 text-[11px] text-gray-500">{body.length.toLocaleString()} / {MAX_CHARS.toLocaleString()} characters</p>
        </div>
        <div className="flex items-center gap-3">
          <button type="submit" className="btn-primary" disabled={save.isPending || !title.trim() || !body.trim()}>
            {save.isPending ? 'Saving…' : 'Save and find ideas'}
          </button>
          {error && <span className="text-sm text-red-300">{error}</span>}
        </div>
      </form>
    </Modal>
  )
}

/** Add From Link: CMO² reads the page's main text. */
export function LinkSourceDialog({ companyId, onClose, onSaved }: {
  companyId: number
  onClose: () => void
  onSaved: (d: ContentSourceDetail) => void
}) {
  const [url, setUrl] = useState('')
  const [title, setTitle] = useState('')
  const [error, setError] = useState<string | null>(null)
  const save = useMutation({
    mutationFn: () => linkSource(companyId, url.trim(), title.trim() || undefined),
    onSuccess: onSaved,
    onError: (err) => setError(apiErrorMessage(err, 'Could not read that link')),
  })
  return (
    <Modal title="Add from link" onClose={onClose}>
      <form className="space-y-3" onSubmit={(e) => { e.preventDefault(); setError(null); save.mutate() }}>
        <div>
          <label className="label">Web page</label>
          <input className="input" required autoFocus maxLength={2048} placeholder="https://example.com/article" value={url}
                 onChange={(e) => setUrl(e.target.value)} />
          <p className="mt-1 text-[11px] text-gray-500">
            Articles and blog posts work best. Pages that need a login or JavaScript can't be read; paste their text instead.
          </p>
        </div>
        <div>
          <label className="label">Title (optional)</label>
          <input className="input" maxLength={200} placeholder="Uses the page's title" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>
        <div className="flex items-center gap-3">
          <button type="submit" className="btn-primary" disabled={save.isPending || !url.trim()}>
            {save.isPending ? 'Reading the page…' : 'Add link'}
          </button>
          {error && <span className="text-sm text-red-300">{error}</span>}
        </div>
      </form>
    </Modal>
  )
}
