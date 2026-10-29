import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { GlassCard, PageHeader } from '../../components/ui/GlassCard'
import RequireRole from '../../app/guards/RequireRole'
import { apiErrorMessage } from '../../lib/api'
import { listCompanies } from '../companies/companiesApi'
import { assetBody, currentUsage, generateAsset, listAssets } from './assetsApi'
import type { AiUsage, Asset, AssetTypeKey, Company, GenerateResult } from '../../lib/types'

const TYPES: { key: AssetTypeKey; label: string }[] = [
  { key: 'EMAIL_SEQUENCE', label: 'Email Sequence' },
  { key: 'EBOOK', label: 'eBook' },
  { key: 'BLOG_POST', label: 'Blog Post' },
  { key: 'SOCIAL_MEDIA', label: 'Social Media' },
  { key: 'LANDING_PAGE', label: 'Landing Page' },
  { key: 'SMS_CAMPAIGN', label: 'SMS Campaign' },
  { key: 'CHATBOT_SCRIPT', label: 'Chatbot Script' },
]

export default function AssetGeneratorPage() {
  const qc = useQueryClient()
  const { data: companies } = useQuery<Company[]>({ queryKey: ['companies'], queryFn: listCompanies })
  const activeCompanies = useMemo(() => (companies ?? []).filter((c) => c.status === 'ACTIVE'), [companies])

  const [companyId, setCompanyId] = useState<number | null>(null)
  useEffect(() => {
    if (companyId === null && activeCompanies.length > 0) setCompanyId(activeCompanies[0].id)
  }, [activeCompanies, companyId])

  const [type, setType] = useState<AssetTypeKey>('EMAIL_SEQUENCE')
  const [brief, setBrief] = useState('')
  const [lang, setLang] = useState<'EN' | 'ES'>('EN')
  const [result, setResult] = useState<GenerateResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const { data: usage } = useQuery<AiUsage>({
    queryKey: ['usage', 'current'],
    queryFn: currentUsage,
    refetchInterval: 20_000,
  })

  const { data: assets } = useQuery<Asset[]>({
    queryKey: ['assets', companyId],
    queryFn: () => listAssets(companyId!),
    enabled: companyId !== null,
  })

  const generate = useMutation({
    mutationFn: () => generateAsset(companyId!, type, brief.trim()),
    onMutate: () => setError(null),
    onSuccess: (res) => {
      setResult(res)
      setLang('EN')
      qc.invalidateQueries({ queryKey: ['assets', companyId] })
      qc.invalidateQueries({ queryKey: ['usage', 'current'] })
    },
    onError: (err) => setError(apiErrorMessage(err, 'Generation failed')),
  })

  const shown = result ? (lang === 'EN' ? result.english : result.spanish) : null
  const usedPct = usage && usage.tokenCeiling > 0 ? Math.min(100, (usage.tokensUsed / usage.tokenCeiling) * 100) : 0

  return (
    <div>
      <PageHeader
        title="AI Asset Generator"
        subtitle="Generate on-brand content, auto-translated EN → ES, waiting for your approval before anything is published."
        action={
          <select className="input w-48" value={companyId ?? ''} onChange={(e) => setCompanyId(Number(e.target.value))}>
            {activeCompanies.length === 0 && <option value="">No companies</option>}
            {activeCompanies.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        }
      />

      {/* Token meter */}
      {usage && (
        <GlassCard className="mb-6">
          <div className="flex items-center justify-between text-sm">
            <span className="text-gray-300">AI tokens this period ({usage.period})</span>
            <span className="text-gray-400">
              {usage.tokensUsed.toLocaleString()} / {usage.tokenCeiling.toLocaleString()}
            </span>
          </div>
          <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-white/10">
            <div className={`h-full rounded-full ${usedPct > 90 ? 'bg-red-400' : 'bg-brand'}`} style={{ width: `${usedPct}%` }} />
          </div>
        </GlassCard>
      )}

      {activeCompanies.length === 0 ? (
        <GlassCard><p className="text-sm text-gray-300">Add a company first.</p></GlassCard>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[16rem_1fr]">
          {/* Type sidebar + config */}
          <div className="space-y-4">
            <GlassCard>
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-400">Asset type</div>
              <div className="space-y-1">
                {TYPES.map((t) => (
                  <button
                    key={t.key}
                    onClick={() => setType(t.key)}
                    className={`block w-full rounded-lg px-3 py-2 text-left text-sm transition ${
                      type === t.key ? 'bg-brand/20 text-white' : 'text-gray-400 hover:bg-white/5'
                    }`}
                  >
                    {t.label}
                  </button>
                ))}
              </div>
            </GlassCard>

            <GlassCard>
              <label className="label">Brief (optional)</label>
              <textarea
                className="input h-28 resize-none"
                placeholder="Audience, offer, tone…"
                value={brief}
                onChange={(e) => setBrief(e.target.value)}
              />
              <RequireRole allow={['OWNER', 'ADMIN', 'STRATEGIST']} fallback={
                <p className="mt-3 text-xs text-gray-500">Your role can view but not generate.</p>
              }>
                <button
                  className="btn-primary mt-3 w-full"
                  disabled={companyId === null || generate.isPending}
                  onClick={() => generate.mutate()}
                >
                  {generate.isPending ? 'Generating…' : '✦ Generate'}
                </button>
              </RequireRole>
            </GlassCard>
          </div>

          {/* Preview + deploy */}
          <div className="space-y-4">
            {error && (
              <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-sm text-red-300">{error}</div>
            )}

            {shown ? (
              <GlassCard>
                <div className="mb-3 flex items-center justify-between">
                  <div>
                    <h3 className="font-semibold text-white">{shown.title}</h3>
                    <p className="text-xs text-gray-500">
                      v{shown.version} · {shown.status} · {shown.tokensUsed.toLocaleString()} tokens
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <div className="flex rounded-lg border border-white/10 p-0.5 text-xs">
                      {(['EN', 'ES'] as const).map((l) => (
                        <button
                          key={l}
                          onClick={() => setLang(l)}
                          className={`rounded px-2 py-1 ${lang === l ? 'bg-brand text-white' : 'text-gray-400'}`}
                        >
                          {l}
                        </button>
                      ))}
                    </div>
                    <RequireRole allow={['OWNER', 'ADMIN']}>
                      <Link className="btn-primary" to={`/approvals?companyId=${shown.companyId}`}>
                        Review &amp; approve
                      </Link>
                    </RequireRole>
                  </div>
                </div>
                <pre className="max-h-[28rem] overflow-auto whitespace-pre-wrap rounded-lg bg-black/30 p-4 text-xs text-gray-200">
                  {assetBody(shown)}
                </pre>
              </GlassCard>
            ) : (
              <GlassCard>
                <div className="py-12 text-center text-sm text-gray-400">
                  Pick a type and click <span className="text-brand-glow">Generate</span> to preview EN &amp; ES versions.
                </div>
              </GlassCard>
            )}

            <GlassCard>
              <h2 className="mb-3 text-sm font-semibold text-white">Asset library</h2>
              {!assets || assets.length === 0 ? (
                <p className="text-sm text-gray-400">No assets yet.</p>
              ) : (
                <div className="divide-y divide-white/5">
                  {assets.map((a) => (
                    <div key={a.id} className="flex items-center justify-between py-2 text-sm">
                      <span className="text-gray-200">{a.title}</span>
                      <span className="text-xs text-gray-500">{a.language} · {a.type} · {a.status}</span>
                    </div>
                  ))}
                </div>
              )}
            </GlassCard>
          </div>
        </div>
      )}
    </div>
  )
}
