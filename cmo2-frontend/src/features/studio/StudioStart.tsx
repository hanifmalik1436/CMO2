import { useCallback, useEffect, useRef, useState } from 'react'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { useSession } from '../../stores/session'
import { recordSource, uploadSource } from './studioApi'
import { LinkSourceDialog, TextSourceDialog } from './SourceDialogs'
import { Icon, type IconName } from './icons'
import type { ContentSourceDetail } from '../../lib/types'

type Phase = 'idle' | 'recording' | 'paused' | 'saving'
const MAX_MS = 60 * 60 * 1000        // about 25 MB of speech, the most transcription accepts
const BARS = 40
const PINS_KEY = 'cmo2.studio.pins'

const RECORDER_TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus']

/**
 * The studio's start screen: record a voice note with the orb, or bring in something that already
 * exists (paste, upload, blank canvas, link). Every new source is searched for ideas in the
 * background, so this hands straight over to the Content Library.
 */
export function StudioStart({ companyId, onAdded }: { companyId: number; onAdded: (d: ContentSourceDetail) => void }) {
  const firstName = useSession((s) => s.user?.fullName?.split(' ')[0]) ?? ''
  const [phase, setPhase] = useState<Phase>('idle')
  const [elapsed, setElapsed] = useState(0)
  const parts = useRef(1)
  const [saving, setSaving] = useState({ title: '', sub: '' })
  const [error, setError] = useState<string | null>(null)
  const [dialog, setDialog] = useState<'paste' | 'blank' | 'link' | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  const recorder = useRef<MediaRecorder | null>(null)
  const stream = useRef<MediaStream | null>(null)
  const audioCtx = useRef<AudioContext | null>(null)
  const analyser = useRef<AnalyserNode | null>(null)
  const chunks = useRef<Blob[]>([])
  const raf = useRef(0)
  const runningSince = useRef(0)
  const banked = useRef(0)
  const bars = useRef<(HTMLElement | null)[]>([])

  const now = () => banked.current + (runningSince.current ? performance.now() - runningSince.current : 0)

  const release = useCallback(() => {
    cancelAnimationFrame(raf.current)
    stream.current?.getTracks().forEach((t) => t.stop())
    stream.current = null
    audioCtx.current?.close().catch(() => undefined)
    audioCtx.current = null
    analyser.current = null
  }, [])

  useEffect(() => () => {
    if (recorder.current && recorder.current.state !== 'inactive') {
      recorder.current.onstop = null
      recorder.current.stop()
    }
    release()
  }, [release])

  const tick = useCallback(function loop() {
    const levels = new Uint8Array(64)
    analyser.current?.getByteFrequencyData(levels)
    bars.current.forEach((el, i) => {
      if (el) el.style.height = `${4 + (levels[(i * 3) % levels.length] / 255) * 30}px`
    })
    const ms = now()
    setElapsed(ms)
    if (ms >= MAX_MS) {
      finish()
      return
    }
    raf.current = requestAnimationFrame(loop)
  }, [])  // reads only refs; finish() too

  async function start() {
    setError(null)
    if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
      setError("This browser can't record audio. Upload a recording instead.")
      return
    }
    try {
      stream.current = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch {
      setError('Microphone access was blocked. Allow it from the address bar, or upload a recording instead.')
      return
    }
    const mimeType = RECORDER_TYPES.find((t) => MediaRecorder.isTypeSupported(t))
    const rec = new MediaRecorder(stream.current, mimeType ? { mimeType } : undefined)
    chunks.current = []
    rec.ondataavailable = (e) => { if (e.data.size > 0) chunks.current.push(e.data) }
    rec.start(1000)
    recorder.current = rec
    try {
      audioCtx.current = new AudioContext()
      analyser.current = audioCtx.current.createAnalyser()
      analyser.current.fftSize = 128
      audioCtx.current.createMediaStreamSource(stream.current).connect(analyser.current)
    } catch {
      analyser.current = null   // the waveform stays flat; recording still works
    }
    banked.current = 0
    runningSince.current = performance.now()
    parts.current = 1
    setPhase('recording')
    raf.current = requestAnimationFrame(tick)
  }

  function pause() {
    recorder.current?.pause()
    banked.current = now()
    runningSince.current = 0
    cancelAnimationFrame(raf.current)
    bars.current.forEach((el) => { if (el) el.style.height = '4px' })
    setPhase('paused')
  }

  function resume() {
    recorder.current?.resume()
    runningSince.current = performance.now()
    parts.current += 1
    setPhase('recording')
    raf.current = requestAnimationFrame(tick)
  }

  function finish() {
    const rec = recorder.current
    if (!rec) return
    const length = now()
    runningSince.current = 0
    cancelAnimationFrame(raf.current)
    rec.onstop = async () => {
      release()
      recorder.current = null
      if (length < 2000) {
        setPhase('idle')
        setError('That recording was too short. Talk for a little longer, then press Finish & Save.')
        return
      }
      const blob = new Blob(chunks.current, { type: rec.mimeType || 'audio/webm' })
      const title = `Recording – ${new Date().toLocaleString([], { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })}`
      await save(() => recordSource(companyId, blob, title), 'Saving your recording…',
        `Turning ${parts.current} part${parts.current > 1 ? 's' : ''} of your recording into text. The audio isn't kept.`)
    }
    rec.stop()
  }

  async function save(run: () => Promise<ContentSourceDetail>, title: string, sub: string) {
    setSaving({ title, sub })
    setPhase('saving')
    setError(null)
    try {
      onAdded(await run())
      setPhase('idle')
    } catch (err) {
      setPhase('idle')
      setError(apiErrorMessage(err, 'Could not save it'))
    }
  }

  function onFile(files: FileList | null) {
    const file = files?.[0]
    if (!file) return
    if (file.size > 25 * 1024 * 1024) {
      setError('That file is larger than 25 MB.')
      return
    }
    const audio = file.type.startsWith('audio/')
    void save(() => uploadSource(companyId, file), 'Saving your upload…',
      audio ? `Turning ${file.name} into text. The audio isn't kept.` : `Reading the text in ${file.name}.`)
  }

  const live = phase === 'recording' || phase === 'paused'

  if (phase === 'saving') {
    return (
      <div className="flex min-h-[60vh] flex-col items-center justify-center text-center">
        <div className="grid h-28 w-28 animate-pulse place-items-center rounded-full bg-white/5 text-white">
          <Icon name="sparkles" className="h-12 w-12" />
        </div>
        <h3 className="mt-8 text-2xl font-medium text-white">{saving.title}</h3>
        <p className="mt-3 text-sm text-gray-400">{saving.sub}</p>
      </div>
    )
  }

  return (
    <div className="flex flex-col items-center px-2 pb-10 pt-4">
      <h2 className="text-center text-3xl font-bold tracking-tight text-white sm:text-4xl">
        {live ? `${firstName ? `${firstName}, it's` : "It's"} a great day to make content`
          : `Hey${firstName ? ` ${firstName}` : ''}, let's create together`}
      </h2>
      <p className="mt-2 text-center text-sm font-light text-gray-400">Answer questions, share your thoughts, talk about an idea</p>

      <RequireRole
        allow={['OWNER', 'ADMIN', 'STRATEGIST']}
        fallback={<p className="mt-16 text-sm text-gray-400">Only the owner, an admin or a strategist can add sources.</p>}
      >
        <div className="mt-12 flex flex-col items-center">
          <button
            type="button"
            className={`studio-orb ${phase === 'recording' ? 'recording' : ''} ${phase === 'paused' ? 'paused' : ''}`}
            aria-label={phase === 'idle' ? 'Tap to record' : phase === 'recording' ? 'Pause recording' : 'Resume recording'}
            onClick={() => (phase === 'idle' ? void start() : phase === 'recording' ? pause() : resume())}
          >
            <span className="layer l1" />
            <span className="layer l2" />
            <span className="layer l3" />
            <span className="rim" />
            <span className="absolute inset-0 grid place-items-center text-white/90">
              {live ? <span className="h-9 w-9 rounded-lg bg-white shadow-lg" /> : <Icon name="mic" className="h-10 w-10" />}
            </span>
          </button>
          <div className={`mt-8 text-xs font-medium uppercase tracking-[0.2em] ${phase === 'paused' ? 'text-red-400' : 'text-gray-400'}`}>
            {phase === 'idle' ? 'Tap to record' : phase === 'recording' ? 'Recording — tap to pause' : 'Paused — not saved yet'}
          </div>

          {live && (
            <div className="flex flex-col items-center">
              <div className="mt-8 flex h-9 items-center gap-1" aria-hidden="true">
                {Array.from({ length: BARS }).map((_, i) => (
                  <i key={i} ref={(el) => { bars.current[i] = el }}
                     className={`block w-1 rounded transition-[height] duration-75 ${phase === 'recording' ? 'bg-brand-glow' : 'bg-gray-600'}`}
                     style={{ height: 4 }} />
                ))}
              </div>
              <div className="mt-4 font-mono text-4xl font-bold tracking-widest text-white">{clock(elapsed)}</div>
              <div className="mt-6 flex gap-3">
                <button type="button" className="btn-primary h-12 px-7 text-base" onClick={finish}>
                  <Icon name="check" /> Finish &amp; Save
                </button>
                <button type="button" className="btn-ghost h-12 px-6 text-base" onClick={phase === 'paused' ? resume : pause}>
                  {phase === 'paused' ? 'Resume' : 'Pause'}
                </button>
              </div>
            </div>
          )}
        </div>

        {error && <p className="mt-6 max-w-lg text-center text-sm text-red-300">{error}</p>}

        {!live && (
          <>
            <p className="mb-5 mt-10 text-center text-sm text-gray-400">or bring in something you already have</p>
            <SourceDock
              onPick={(key) => {
                setError(null)
                if (key === 'upload') fileInput.current?.click()
                else setDialog(key)
              }}
            />
            <input ref={fileInput} type="file" hidden accept=".txt,.md,.markdown,.pdf,.docx,audio/*,.m4a,.mp3,.wav,.webm,.ogg"
                   onChange={(e) => { onFile(e.target.files); e.target.value = '' }} />
          </>
        )}
      </RequireRole>

      {dialog === 'paste' || dialog === 'blank' ? (
        <TextSourceDialog companyId={companyId} blank={dialog === 'blank'} onClose={() => setDialog(null)}
                          onSaved={(d) => { setDialog(null); onAdded(d) }} />
      ) : null}
      {dialog === 'link' && (
        <LinkSourceDialog companyId={companyId} onClose={() => setDialog(null)} onSaved={(d) => { setDialog(null); onAdded(d) }} />
      )}
    </div>
  )
}

type DockKey = 'paste' | 'upload' | 'blank' | 'link'
interface DockItem { key: string; label: string; icon: IconName; soon?: boolean }

const DOCK: DockItem[] = [
  { key: 'paste', label: 'Paste Text', icon: 'paste' },
  { key: 'upload', label: 'Upload File', icon: 'upload' },
  { key: 'blank', label: 'Blank Canvas', icon: 'pen' },
  { key: 'link', label: 'Add From Link', icon: 'link' },
  { key: 'video', label: 'Record Video', icon: 'video', soon: true },
  { key: 'reaction', label: 'Reaction', icon: 'chat', soon: true },
  { key: 'seo', label: 'Search Optimization', icon: 'search', soon: true },
  { key: 'group', label: 'Group Capture', icon: 'users', soon: true },
]

function readPins(): string[] {
  try {
    const raw = localStorage.getItem(PINS_KEY)
    return raw ? (JSON.parse(raw) as string[]) : []
  } catch {
    return []
  }
}

/** The row of ways in, scrollable, with favourites pinned to the front (remembered in this browser). */
function SourceDock({ onPick }: { onPick: (key: DockKey) => void }) {
  const [pins, setPins] = useState<string[]>(readPins)
  const track = useRef<HTMLDivElement>(null)
  const [edges, setEdges] = useState({ left: false, right: false })

  const measure = useCallback(() => {
    const t = track.current
    if (!t) return
    setEdges({ left: t.scrollLeft > 2, right: t.scrollLeft < t.scrollWidth - t.clientWidth - 2 })
  }, [])
  useEffect(() => {
    measure()
    window.addEventListener('resize', measure)
    return () => window.removeEventListener('resize', measure)
  }, [measure])

  function togglePin(key: string) {
    const next = pins.includes(key) ? pins.filter((p) => p !== key) : [...pins, key]
    setPins(next)
    try { localStorage.setItem(PINS_KEY, JSON.stringify(next)) } catch { /* not remembered */ }
  }

  const items = [...DOCK].sort((a, b) => Number(pins.includes(b.key)) - Number(pins.includes(a.key)))
  const scroll = (dir: number) => track.current?.scrollBy({ left: dir * Math.max(160, track.current.clientWidth * 0.6), behavior: 'smooth' })

  return (
    <div className="glass flex w-full max-w-3xl items-center gap-2 p-3">
      <button type="button" className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-gray-400 hover:bg-white/10 hover:text-white disabled:opacity-30"
              disabled={!edges.left} onClick={() => scroll(-1)} aria-label="Scroll left"><Icon name="left" /></button>
      <div ref={track} onScroll={measure} className="-my-2 flex min-w-0 items-center gap-3 overflow-x-auto px-1 py-2 [scrollbar-width:none]">
        {items.map((item) => (
          <div key={item.key} className="group relative shrink-0">
            <button
              type="button"
              disabled={item.soon}
              title={item.soon ? 'Coming soon' : undefined}
              onClick={() => onPick(item.key as DockKey)}
              className="flex items-center gap-2.5 whitespace-nowrap rounded-xl border border-transparent px-4 py-2.5 text-sm font-medium text-gray-400
                         transition hover:border-white/10 hover:bg-white/10 hover:text-white disabled:cursor-not-allowed disabled:opacity-60
                         disabled:hover:border-transparent disabled:hover:bg-transparent disabled:hover:text-gray-400"
            >
              <Icon name={item.icon} />
              {item.label}
              {item.soon && <span className="rounded-md bg-white/10 px-1.5 text-[10px] leading-[18px] text-gray-300">Soon</span>}
            </button>
            {!item.soon && (
              <button
                type="button"
                aria-label={`${pins.includes(item.key) ? 'Unpin' : 'Pin'} ${item.label}`}
                aria-pressed={pins.includes(item.key)}
                onClick={() => togglePin(item.key)}
                className={`absolute -right-1 -top-1 z-10 grid h-5 w-5 place-items-center rounded-full border text-gray-400 transition
                  ${pins.includes(item.key) ? 'border-brand bg-brand text-white opacity-100' : 'border-white/15 bg-ink opacity-0 group-hover:opacity-100 focus-visible:opacity-100'}`}
              >
                <Icon name="pin" className="h-3 w-3" filled={pins.includes(item.key)} />
              </button>
            )}
          </div>
        ))}
      </div>
      <button type="button" className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-gray-400 hover:bg-white/10 hover:text-white disabled:opacity-30"
              disabled={!edges.right} onClick={() => scroll(1)} aria-label="Scroll right"><Icon name="right" /></button>
    </div>
  )
}

function clock(ms: number): string {
  const t = Math.floor(ms / 1000)
  const h = Math.floor(t / 3600)
  const mm = String(Math.floor((t % 3600) / 60)).padStart(2, '0')
  const ss = String(t % 60).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`
}
