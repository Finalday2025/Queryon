// 前端自有类型，与后端 REST 响应结构一一对应。

export interface DashboardCard {
  id: string
  title: string
  chartType: string
  resultSet: ResultSet
  metricDefinitions: string[]
  sql: string
  traceId: string
}

export interface Dashboard {
  cards: DashboardCard[]
  timeWindow: string
}

export interface AuditEvent {
  traceId: string
  userId: string
  role: string
  action: string
  outcome: string
  detail: string
}

export interface Subscription {
  id: string
  name: string
  frequency: string
  query: Record<string, unknown>
  actorId: string
  runCount: number
  lastOutcome: string
}

/** 逻辑数据集（D1-05）：受管指标／维度／关联路径的裁剪结果，不暴露物理列。 */
export interface LogicalDataset {
  id: string
  name: string
  description: string
  metrics: string[]
  dimensions: string[]
  joins: string[]
  shared: boolean
  defaultFilters: Record<string, string>
  ownerId: string
  createdAt: string
}

export interface LogicalDatasetInput {
  name: string
  description: string
  metrics: string[]
  dimensions: string[]
  joins: string[]
  shared: boolean
  defaultFilters: Record<string, string>
}

/** 字段值画像（D1-06）：只暴露受管字段的高频取值，供筛选器给候选。 */
export interface ColumnProfile {
  table: string
  column: string
  businessName: string
  totalRows: number
  nullRate: number
  sample: { value: unknown }[]
  topValues: { value: string; count: number }[]
  distribution: Record<string, number>
  timeRange: string
}

/** 连接管理（D1-02）：接口只回传连接参数与状态，不含任何密码字段。 */
export interface ConnectionInfo {
  id: string
  name: string
  type: string
  host: string
  port: number | null
  database: string
  username: string
  readOnly: boolean
  enabled: boolean
  hasCredential: boolean
  status: string
  statusDetail: string
  lastTestedAt: string
  latencyMs: number
}

export interface ConnectionInput {
  name: string
  type: string
  host: string
  port: number | null
  database: string
  username: string
  password: string
  readOnly: boolean
  enabled: boolean
}

/** 指标版本（D2-06）：DRAFT 草稿、APPROVED 生效、SUPERSEDED 已被替代。 */
export interface MetricVersion {
  id: number
  code: string
  version: string
  businessName: string
  definition: string
  expression: string
  defaultFilter: string
  unit: string
  aliases: string[]
  status: string
  note: string
  author: string
  createdAt: string
  active: boolean
}

export interface MetricVersionProposal {
  businessName: string
  definition: string
  expression: string
  defaultFilter: string
  unit: string
  aliases: string[]
  note: string
}

export interface DataSourceInfo {
  id: string
  name: string
  type: string
  tables: number
  columns: number
  rows: number
  metrics: number
  dimensions: number
  approvedJoins: number
}

export interface AsyncTaskView {
  id: string
  status: string
  result: QueryResponse | null
  error: string | null
}

export interface CacheSnapshot {
  enabled: boolean
  hits: number
  misses: number
  writes: number
  fallbacks: number
  hitRate: number
  ttlSeconds: number
}

export interface SharedQuery {
  token: string
  name: string
  query: Record<string, unknown>
  actorId: string
}

export interface ScheduleInfo {
  id: string
  name: string
  intervalSeconds: number
  runCount: number
}

export interface ResultSet {
  columns: string[]
  rows: Record<string, unknown>[]
  totalRows: number
  truncated: boolean
}

export interface TraceStep {
  stage: string
  detail: string
}

export interface StructuredError {
  code: string
  gate: string
  reason: string
  hint: string
}

export interface AskResponse {
  status: 'ANSWERED' | 'DEFINITION' | 'CLARIFICATION' | 'REJECTED'
  question: string
  semanticQuery: unknown | null
  resultSet: ResultSet
  chartType: string | null
  sqlSnapshot: string | null
  metricDefinitions: string[]
  clarification: string | null
  error: StructuredError | null
  traceId: string
  steps: TraceStep[]
  interpretation: string[]
  suggestions: string[]
}

export interface QueryResponse {
  resultSet: ResultSet
  sql: string
  metricDefinitions: string[]
  traceId: string
  steps: TraceStep[]
}

/** 展示元数据：列编码 → 中文业务名、指标编码 → 单位、维度 → 下一层维度。 */
export interface DisplayMeta {
  columnLabels: Record<string, string>
  metricUnits: Record<string, string>
  drillDown: Record<string, string>
}

export interface DatasetSummary {
  id: string
  name: string
  rows: number
  columns: string[]
}

export interface MetricDefinition {
  code: string
  businessName: string
  definition: string
  expression: string
  defaultFilter: string
  unit: string
  aliases: string[]
  version: string
}

export interface DimensionDefinition {
  code: string
  businessName: string
  table: string
  column: string
  aliases: string[]
}

export interface SemanticColumn {
  name: string
  businessName: string
  description: string
  synonyms: string[]
  sensitive: boolean
}

/** 元数据描述变更台账（D1-04）。 */
export interface MetadataChange {
  id: number
  scope: string
  table: string
  column: string
  beforeBusinessName: string
  beforeDescription: string
  beforeSynonyms: string[]
  beforeSensitive: boolean
  afterBusinessName: string
  afterDescription: string
  afterSynonyms: string[]
  afterSensitive: boolean
  author: string
  createdAt: string
}

/** 元数据快照与差异（D1-03）。 */
export interface MetadataSnapshotTable {
  name: string
  businessName: string
  columns: string[]
  rows: number
}

export interface MetadataDiff {
  firstSnapshot: boolean
  addedTables: string[]
  removedTables: string[]
  addedColumns: string[]
  removedColumns: string[]
  rowChanges: { table: string; before: number; after: number }[]
}

export interface MetadataCapture {
  snapshot: {
    id: string
    capturedAt: string
    tables: MetadataSnapshotTable[]
  }
  diff: MetadataDiff
}

export interface SemanticTable {
  name: string
  businessName: string
  description: string
  columns: SemanticColumn[]
}

export interface JoinPath {
  fromTable: string
  toTable: string
  fromKey: string
  toKey: string
}

export interface ActorAccount {
  userId: string
  role: string
  description: string
}

export interface GroupStat {
  total: number
  passed: number
  accuracy: number
}

export interface CaseResult {
  id: number
  question: string
  scene: string
  role: string
  dialect: string
  status: string
  fingerprint: string
  passed: boolean
  failures: string[]
}

/** 评测性能观测（D6-08 延迟）：与可重复报告分离，不参与门禁比较。 */
export interface EvaluationPerformance {
  version: string
  totalCases: number
  totalElapsedMs: number
  averageElapsedMs: number
  p95ElapsedMs: number
  averageMsByDialect: Record<string, number>
  slowestCase: string
  slowestMs: number
  costStatus: string
  note: string
}

export interface EvaluationReport {
  version: string
  total: number
  passed: number
  failed: number
  parsePassRate: number
  executionAccuracy: number
  guardInterceptions: number
  byDialect: Record<string, GroupStat>
  byScene: Record<string, number>
  cases: CaseResult[]
}

export interface FeedbackEntry {
  question: string
  correctedMetric: string
  note: string
  versionSource: string
}
