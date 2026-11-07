import type {
  AskResponse,
  QueryResponse,
  MetricDefinition,
  DimensionDefinition,
  SemanticTable,
  JoinPath,
  ActorAccount,
  EvaluationPerformance,
  EvaluationReport,
  FeedbackEntry,
  DisplayMeta,
  DatasetSummary,
  Dashboard,
  AuditEvent,
  DataSourceInfo,
  ConnectionInfo,
  ConnectionInput,
  MetricVersion,
  MetricVersionProposal,
  ColumnProfile,
  LogicalDataset,
  LogicalDatasetInput,
  MetadataCapture,
  MetadataChange,
  Subscription,
  AsyncTaskView,
  CacheSnapshot,
  SharedQuery,
  ScheduleInfo
} from '../types'

const API = '/api'

function currentUser(): string {
  return localStorage.getItem('queryon-user') || 'admin_bi'
}

export interface ApiError {
  status: number
  error: unknown
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(API + path, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      'X-Queryon-User': currentUser(),
      ...(init?.headers ?? {})
    }
  })
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    throw { status: res.status, error: data } as ApiError
  }
  return data as T
}

async function getText(path: string): Promise<string> {
  const res = await fetch(API + path, { headers: { 'X-Queryon-User': currentUser() } })
  return res.text()
}

export const api = {
  ask: (question: string, sessionId: string) =>
    request<AskResponse>('/ask', {
      method: 'POST',
      body: JSON.stringify({ question, sessionId })
    }),
  query: (payload: Record<string, unknown>) =>
    request<QueryResponse>('/queries', { method: 'POST', body: JSON.stringify(payload) }),
  metrics: () => request<MetricDefinition[]>('/semantic/metrics'),
  dimensions: () => request<DimensionDefinition[]>('/semantic/dimensions'),
  tables: () => request<SemanticTable[]>('/semantic/tables'),
  joins: () => request<JoinPath[]>('/semantic/joins'),
  actors: () => request<ActorAccount[]>('/semantic/actors'),
  display: () => request<DisplayMeta>('/semantic/display'),
  profile: (table: string, column: string) =>
    request<ColumnProfile>(
      '/semantic/profile?table=' + encodeURIComponent(table) + '&column=' + encodeURIComponent(column)
    ),
  saveDataset: (name: string, query: Record<string, unknown>) =>
    request<DatasetSummary>('/datasets', { method: 'POST', body: JSON.stringify({ name, query }) }),
  listDatasets: () => request<DatasetSummary[]>('/datasets'),
  dataset: (id: string) => request<QueryResponse>('/datasets/' + encodeURIComponent(id)),
  runEvaluation: () => request<EvaluationReport>('/evaluations/run', { method: 'POST' }),
  evaluationReport: () => getText('/evaluations/report'),
  evaluationPerformance: () =>
    request<{ available: boolean; performance?: EvaluationPerformance }>('/evaluations/performance'),
  submitFeedback: (payload: Record<string, unknown>) =>
    request<FeedbackEntry>('/feedback', { method: 'POST', body: JSON.stringify(payload) }),
  listFeedback: () => request<FeedbackEntry[]>('/feedback'),
  dashboard: (timeWindow: string) =>
    request<Dashboard>('/dashboard?timeWindow=' + encodeURIComponent(timeWindow)),
  auditEvents: (outcome: string, limit = 50) =>
    request<AuditEvent[]>('/audit/events?outcome=' + encodeURIComponent(outcome) + '&limit=' + limit),
  datasources: () => request<DataSourceInfo[]>('/datasources'),
  syncMetadata: () => request<Record<string, unknown>>('/datasources/sync', { method: 'POST' }),
  metadataSnapshots: () => request<MetadataCapture[]>('/datasources/snapshots'),
  metadataChanges: () => request<MetadataChange[]>('/semantic/metadata/changes'),
  updateTableDescription: (table: string, payload: Record<string, unknown>) =>
    request<MetadataChange>('/semantic/tables/' + encodeURIComponent(table) + '/description', {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  updateColumnDescription: (table: string, column: string, payload: Record<string, unknown>) =>
    request<MetadataChange>(
      '/semantic/tables/' + encodeURIComponent(table) + '/columns/' + encodeURIComponent(column) + '/description',
      { method: 'PUT', body: JSON.stringify(payload) }
    ),
  revertMetadataChange: (id: number) =>
    request<MetadataChange>('/semantic/metadata/changes/' + id + '/revert', { method: 'POST' }),
  logicalDatasets: () => request<LogicalDataset[]>('/logical-datasets'),
  createLogicalDataset: (payload: LogicalDatasetInput) =>
    request<LogicalDataset>('/logical-datasets', { method: 'POST', body: JSON.stringify(payload) }),
  updateLogicalDataset: (id: string, payload: LogicalDatasetInput) =>
    request<LogicalDataset>('/logical-datasets/' + encodeURIComponent(id), {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  deleteLogicalDataset: (id: string) =>
    request<Record<string, string>>('/logical-datasets/' + encodeURIComponent(id), {
      method: 'DELETE'
    }),
  queryLogicalDataset: (id: string, payload: Record<string, unknown>) =>
    request<QueryResponse>('/logical-datasets/' + encodeURIComponent(id) + '/query', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  connections: () => request<ConnectionInfo[]>('/connections'),
  createConnection: (payload: ConnectionInput) =>
    request<ConnectionInfo>('/connections', { method: 'POST', body: JSON.stringify(payload) }),
  updateConnection: (id: string, payload: ConnectionInput) =>
    request<ConnectionInfo>('/connections/' + encodeURIComponent(id), {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  deleteConnection: (id: string) =>
    request<Record<string, string>>('/connections/' + encodeURIComponent(id), { method: 'DELETE' }),
  testConnection: (id: string) =>
    request<ConnectionInfo>('/connections/' + encodeURIComponent(id) + '/test', { method: 'POST' }),
  toggleConnection: (id: string, enabled: boolean) =>
    request<ConnectionInfo>(
      '/connections/' + encodeURIComponent(id) + '/toggle?enabled=' + enabled,
      { method: 'POST' }
    ),
  metricVersions: (code: string) =>
    request<MetricVersion[]>('/semantic/metrics/' + encodeURIComponent(code) + '/versions'),
  proposeMetricVersion: (code: string, payload: MetricVersionProposal) =>
    request<MetricVersion>('/semantic/metrics/' + encodeURIComponent(code) + '/versions', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  approveMetricVersion: (code: string, version: string) =>
    request<MetricVersion>(
      '/semantic/metrics/' + encodeURIComponent(code) + '/versions/' + encodeURIComponent(version) + '/approve',
      { method: 'POST' }
    ),
  rollbackMetricVersion: (code: string, version: string) =>
    request<MetricVersion>(
      '/semantic/metrics/' + encodeURIComponent(code) + '/versions/' + encodeURIComponent(version) + '/rollback',
      { method: 'POST' }
    ),
  dashboardCards: () => request<Dashboard>('/dashboard'),
  subscriptions: () => request<Subscription[]>('/subscriptions'),
  saveSubscription: (name: string, frequency: string, query: Record<string, unknown>) =>
    request<Subscription>('/subscriptions', {
      method: 'POST',
      body: JSON.stringify({ name, frequency, query })
    }),
  runSubscription: (id: string) =>
    request<QueryResponse>('/subscriptions/' + encodeURIComponent(id) + '/run', { method: 'POST' }),
  submitAsyncQuery: (query: Record<string, unknown>) =>
    request<AsyncTaskView>('/queries/async', { method: 'POST', body: JSON.stringify(query) }),
  asyncQuery: (id: string) => request<AsyncTaskView>('/queries/async/' + encodeURIComponent(id)),
  cancelAsyncQuery: (id: string) =>
    request<AsyncTaskView>('/queries/async/' + encodeURIComponent(id), { method: 'DELETE' }),
  cacheStats: () => request<CacheSnapshot>('/observability/cache'),
  shares: () => request<SharedQuery[]>('/shares'),
  createShare: (name: string, query: Record<string, unknown>) =>
    request<SharedQuery>('/shares', { method: 'POST', body: JSON.stringify({ name, query }) }),
  resolveShare: (token: string) =>
    request<QueryResponse>('/shares/' + encodeURIComponent(token)),
  revokeShare: (token: string) =>
    request<Record<string, string>>('/shares/' + encodeURIComponent(token), { method: 'DELETE' }),
  scheduleSubscription: (id: string, intervalSeconds: number) =>
    request<ScheduleInfo>('/subscriptions/' + encodeURIComponent(id) + '/schedule?intervalSeconds=' + intervalSeconds, { method: 'POST' }),
  cancelSchedule: (id: string) =>
    request<ScheduleInfo>('/subscriptions/' + encodeURIComponent(id) + '/schedule', { method: 'DELETE' }),
  activeSchedules: () => request<Record<string, ScheduleInfo>>('/subscriptions/schedules')
}
