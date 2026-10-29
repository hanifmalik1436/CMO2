// Shared API types — mirror the backend DTOs (com.cmo2.*.dto).

export type Role = 'OWNER' | 'ADMIN' | 'STRATEGIST' | 'ANALYST' | 'VIEWER'

export interface UserSummary {
  id: number
  tenantId: number
  email: string
  fullName: string
  emailVerified: boolean
  roles: Role[]
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  user: UserSummary
}

export interface TenantInfo {
  id: number
  name: string
  status: string
  planCode: string
  trialEndsAt: string | null
}

export type CompanyStatus = 'ACTIVE' | 'ARCHIVED'

export interface Company {
  id: number
  name: string
  industry: string | null
  brandVoice: string | null
  website: string | null
  timezone: string | null
  ghlLocationId: string | null
  status: CompanyStatus
  onboardingStep: number
  createdAt: string
}

export interface CompanyInput {
  name: string
  industry?: string
  brandVoice?: string
  website?: string
}

// ---- AI Asset Generator (Phase 4) ----

export type AssetTypeKey =
  | 'EMAIL_SEQUENCE'
  | 'EBOOK'
  | 'BLOG_POST'
  | 'SOCIAL_MEDIA'
  | 'LANDING_PAGE'
  | 'SMS_CAMPAIGN'
  | 'CHATBOT_SCRIPT'

export interface Asset {
  id: number
  companyId: number
  type: string
  language: 'EN' | 'ES'
  status: AssetStatus
  title: string | null
  contentJson: string | null
  version: number
  parentAssetId: number | null
  tokensUsed: number
  createdAt: string
  reviewedBy: number | null
  reviewedAt: string | null
  reviewNote: string | null
}

/** GENERATED and TRANSLATED only appear on drafts made before approvals existed. */
export type AssetStatus =
  | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'DEPLOYED' | 'ARCHIVED' | 'GENERATED' | 'TRANSLATED'

export interface ApprovalItem {
  asset: Asset
  companyName: string | null
  latestDeployment: AssetDeployment | null
}

export interface SocialAccount {
  id: string
  name: string | null
  platform: string | null
  expired: boolean
}

export interface SocialAccounts {
  live: boolean
  accounts: SocialAccount[]
  message: string | null
}

export interface PublishOptions {
  accountIds?: string[]
  scheduleAt?: string
  mediaUrls?: string[]
  confirmRetry?: boolean
}

export interface GenerateResult {
  english: Asset
  spanish: Asset
}

export interface AssetDeployment {
  id: number
  platform: string
  status: 'PENDING' | 'ACCEPTED' | 'FAILED' | 'UNCONFIRMED'
  externalRef: string | null
  errorMessage: string | null
  deployedAt: string | null
  scheduledFor: string | null
  targetAccounts: string[]
}

export interface AiUsage {
  period: string
  tokensUsed: number
  tokenCeiling: number
}

// ---- AI Strategists (Phase 5) ----

export interface StrategistAgent {
  code: string
  name: string
  phase: number
  deliverables: string[]
}

export interface StrategistSession {
  id: number
  companyId: number
  agentCode: string
  agentName: string
  status: 'ACTIVE' | 'COMPLETED' | 'APPROVED' | 'ARCHIVED'
  deliverablesJson: string | null
  createdAt: string
}

export interface StrategistMessage {
  id: number
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  tokensUsed: number
  createdAt: string
}

export interface SessionDetail {
  session: StrategistSession
  messages: StrategistMessage[]
}

export interface ReviewGate {
  gateCode: string
  phase: number
  status: 'PENDING' | 'APPROVED'
  approvedBy: number | null
  approvedAt: string | null
  notes: string | null
}

// ---- Strange Loop (Phase 2) ----

export type IterationStatus = 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface LoopIteration {
  id: number
  companyId: number
  iterationNo: number
  status: IterationStatus
  triggerType: string
  startedAt: string | null
  completedAt: string | null
}

export interface LoopStageRun {
  stageNo: number
  stageName: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  progressPct: number
  dataJson: string | null
  startedAt: string | null
  completedAt: string | null
}

export interface LoopIterationDetail {
  iteration: LoopIteration
  insightsJson: string | null
  gapsJson: string | null
  stages: LoopStageRun[]
}

// ---- Integrations (Phase 3) ----

export type ConnectionHealthLevel = 'GREEN' | 'YELLOW' | 'RED'

export interface PlatformConnection {
  id: number
  companyId: number
  platform: string
  platformName: string
  maskedKey: string
  locationId: string | null
  status: string
  health: ConnectionHealthLevel
  lastSyncAt: string | null
  lastError: string | null
}

export interface ConnectInput {
  platform: string
  apiKey: string
  locationId?: string
}

export interface SyncJob {
  id: number
  type: string
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  kpisIngested: number
  startedAt: string | null
  finishedAt: string | null
  errorMessage: string | null
}

export interface SyncTriggerResult {
  connectionId: number
  health: ConnectionHealthLevel
  kpisIngested: number
  message: string
}

export interface LatestKpi {
  metric: string
  value: number
  capturedAt: string
}

// ---- Analytics, Alerts & Reports (Phase 6) ----

export interface TimeseriesPoint {
  capturedAt: string
  value: number
}

export interface MetricAggregate {
  metric: string
  value: number
  companiesWithData: number
}

export type AlertSeverity = 'INFO' | 'WARNING' | 'CRITICAL'
export type AlertOperator = 'GT' | 'LT' | 'GTE' | 'LTE'
export type AlertStatus = 'FIRED' | 'ACKED' | 'RESOLVED'
export type AlertChannelType = 'EMAIL' | 'SMS' | 'SLACK' | 'IN_APP'

export interface ChannelSpec {
  type: AlertChannelType
  target?: string | null
}

export interface AlertRule {
  id: number
  companyId: number
  metric: string
  operator: AlertOperator
  threshold: number
  severity: AlertSeverity
  channelsJson: string | null
  active: boolean
  createdAt: string
}

export interface CreateRuleInput {
  metric: string
  operator: AlertOperator
  threshold: number
  severity: AlertSeverity
  channels: ChannelSpec[]
}

export interface Alert {
  id: number
  companyId: number
  ruleId: number | null
  metric: string
  value: number
  severity: AlertSeverity
  status: AlertStatus
  message: string | null
  dispatchedJson: string | null
  firedAt: string | null
  ackedAt: string | null
}

export type ReportType = 'WEEKLY' | 'ON_DEMAND'
export type ReportStatus = 'GENERATED' | 'FAILED'

export interface ReportSummary {
  id: number
  companyId: number | null
  type: ReportType
  title: string
  period: string | null
  format: string
  status: ReportStatus
  createdAt: string
}

// ---- Expansion & advanced AI (Phase 8) ----

export interface PlatformInfo {
  code: string
  name: string
  supported: boolean
  deploysAssets: boolean
}

export interface ForecastPoint {
  step: number
  value: number
}

export interface Forecast {
  metric: string
  history: number[]
  projection: ForecastPoint[]
  trend: 'up' | 'down' | 'flat' | 'insufficient_data'
  changePerPeriod: number
  pctPerPeriod: number
  confidence: number
  narrative: string
}

export interface WhatIfScenario {
  spendChangePct?: number | null
  cplChangePct?: number | null
  conversionChangePct?: number | null
}

export interface MetricDelta {
  metric: string
  label: string
  baseline: number
  projected: number
  changePct: number
}

export interface WhatIfResult {
  metrics: MetricDelta[]
  baselineSpend: number
  projectedSpend: number
  baselineRevenue: number
  projectedRevenue: number
  summary: string
}

// ---- Billing & Team (Phase 7) ----

export interface UsageMeter {
  key: string
  label: string
  used: number
  limit: number // -1 = unlimited
}

export interface PlanOption {
  code: string
  name: string
  priceCents: number
  maxCompanies: number
  maxConnections: number
  monthlyAiTokens: number
  maxSeats: number
  current: boolean
}

export interface BillingSummary {
  plan: string
  planName: string
  status: string
  demo: boolean
  trialEndsAt: string | null
  currentPeriodEnd: string | null
  hasPaymentMethod: boolean
  usage: UsageMeter[]
  plans: PlanOption[]
}

export interface CheckoutResponse {
  url: string
  demo: boolean
  plan: string
}

export interface TeamMember {
  id: number
  email: string
  fullName: string
  status: 'PENDING_VERIFICATION' | 'ACTIVE' | 'LOCKED' | 'DISABLED'
  emailVerified: boolean
  roles: Role[]
  lastLoginAt: string | null
  createdAt: string
}

export interface InviteInput {
  email: string
  fullName: string
  role: Role
}

/** Structured payload on a 402 plan-limit response (ApiError.details). */
export interface PlanLimitDetails {
  code: 'PLAN_LIMIT'
  limitType: string
  current: number
  max: number
  plan: string
  suggestedPlan: string | null
}

/** Real-time message pushed over STOMP (`/topic/tenant.{id}.loop`). */
export interface LoopUpdate {
  type:
    | 'ITERATION_STARTED'
    | 'STAGE_STARTED'
    | 'STAGE_COMPLETED'
    | 'ITERATION_COMPLETED'
    | 'ITERATION_FAILED'
  iterationId: number
  companyId: number
  iterationNo: number
  stageNo: number | null
  stageName: string | null
  stageStatus: string | null
  iterationStatus: IterationStatus
  progressPct: number
  message: string
  at: string
}

// ---- GoHighLevel agency + customers ----

export interface GhlAgencyStatus {
  appConfigured: boolean
  installUrl: string | null
  connected: boolean
  ghlCompanyId: string | null
  status: 'CONNECTED' | 'ERROR' | null
  tokenExpiresAt: string | null
  scope: string | null
  lastError: string | null
}

export interface GhlSubAccount {
  locationId: string
  name: string | null
  email: string | null
  phone: string | null
  website: string | null
  timezone: string | null
  city: string | null
  country: string | null
  enabled: boolean
  companyId: number | null
  connectionId: number | null
  health: ConnectionHealthLevel | null
  lastSyncAt: string | null
  lastError: string | null
}

// ---- Workspace tools (Notion / Confluence / Linear / Slack) ----

export type DocProviderKey = 'NOTION' | 'CONFLUENCE' | 'LINEAR' | 'SLACK'

export interface DocConnection {
  provider: DocProviderKey
  name: string
  configured: boolean
  maskedSecret: string | null
  settings: Record<string, string>
  status: 'CONNECTED' | 'ERROR' | null
  lastError: string | null
  lastCheckedAt: string | null
}

export interface SaveDocConnectionInput {
  secret?: string
  notionRootId?: string
  confluenceSiteUrl?: string
  confluenceEmail?: string
  confluenceSpaceKey?: string
  confluenceParentPageId?: string
  linearTeamId?: string
}

export interface DocTargets {
  companyId: number
  notionPageId: string | null
  notionUrl: string | null
  confluencePageId: string | null
  confluenceUrl: string | null
  linearProjectId: string | null
  linearUrl: string | null
  lastError: string | null
}

/** A customer's profile: the owner's notes plus the details GHL reports about the business. */
export interface CustomerProfile {
  companyId: number
  name: string
  industry: string | null
  brandVoice: string | null
  website: string | null
  timezone: string | null
  ghlLocationId: string | null
  offer: string | null
  audience: string | null
  bannedTopics: string | null
  notes: string | null
  ghlBusinessName: string | null
  city: string | null
  country: string | null
  phone: string | null
  email: string | null
  socialChannels: string[]
  factsSyncedAt: string | null
}

export interface CustomerProfileInput {
  name: string
  industry?: string
  brandVoice?: string
  website?: string
  timezone?: string
  offer?: string
  audience?: string
  bannedTopics?: string
  notes?: string
}

/** Last 7 days against the 7 before, as the backend computes it for the AI. */
export interface KpiTrend {
  metric: string
  daily: boolean
  current: number
  previous: number | null
}

export type CadenceKey = 'OFF' | 'DAILY' | 'WEEKLY' | 'MONTHLY'

/** A customer's Strange Loop cadence, in the customer's own timezone. */
export interface LoopSchedule {
  companyId: number
  companyName: string
  cadence: CadenceKey
  timeOfDay: string | null
  dayOfWeek: number | null
  dayOfMonth: number | null
  timezone: string
  nextRunAt: string | null
  lastScheduledRunAt: string | null
}

export interface LoopScheduleInput {
  cadence: CadenceKey
  timeOfDay?: string
  dayOfWeek?: number
  dayOfMonth?: number
}

export interface CycleTask {
  key: string
  title: string
  detail: string
}

export interface OwnerNote {
  source: string
  ref: string | null
  text: string
}

/** One cycle's record: what CMO² did and decided, and what it read back from the owner. */
export interface CycleRecord {
  iterationId: number
  iterationNo: number
  periodStart: string
  periodEnd: string
  summary: string | null
  decisions: string[]
  tasksCompleted: string[]
  tasksPending: CycleTask[]
  contentProduced: string[]
  operatorNotes: OwnerNote[]
  links: Record<string, string>
}

export interface AiInteraction {
  id: number
  iterationId: number | null
  stage: string | null
  kind: string
  tier: string
  model: string | null
  prompt: string | null
  response: string | null
  inputTokens: number
  outputTokens: number
  createdAt: string
}

export interface LearningHypothesis {
  statement: string
  metric: string
  expectedDirection: 'up' | 'down'
  confidence: number
}

/** Stage 2's reading of one cycle. source: ai, rules (AI unavailable) or no-data. */
export interface LearningRecord {
  iterationId: number
  iterationNo: number
  source: 'ai' | 'rules' | 'no-data'
  observations: string[]
  hypotheses: LearningHypothesis[]
  confidence: number
  note: string | null
  createdAt: string
}

export type ExperimentStatus = 'PROPOSED' | 'APPLIED' | 'MEASURED' | 'REJECTED' | 'FAILED'

export interface Experiment {
  id: number
  iterationId: number
  hypothesis: string
  action: 'DRAFT_SOCIAL_POST' | 'UPDATE_CUSTOM_VALUE' | 'SUGGESTION'
  riskTier: string
  status: ExperimentStatus
  metric: string
  metricLabel: string
  expectedDirection: 'up' | 'down'
  params: Record<string, string>
  baselineValue: number | null
  resultValue: number | null
  outcome: 'SUPPORTED' | 'NOT_SUPPORTED' | 'INCONCLUSIVE' | null
  resultSummary: string | null
  assetId: number | null
  decisionNote: string | null
  createdAt: string
  appliedAt: string | null
  measuredAt: string | null
}
