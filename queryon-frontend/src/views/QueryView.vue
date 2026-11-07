<script setup lang="ts">
import { ref, computed, watch, onMounted } from 'vue'
import type {
  QueryResponse,
  MetricDefinition,
  DimensionDefinition,
  DatasetSummary
} from '../types'
import { api } from '../api/client'
import { useDisplay } from '../composables/useDisplay'
import { TIME_WINDOWS, useGlobalFilters } from '../composables/useGlobalFilters'
import ResultTable from '../components/ResultTable.vue'
import ChartView from '../components/ChartView.vue'
import ExplanationPanel from '../components/ExplanationPanel.vue'
import PivotTable from '../components/PivotTable.vue'

const tab = ref<'sql' | 'builder'>('builder')
const viewMode = ref<'standard' | 'pivot'>('standard')
const sql = ref(
  'SELECT sales_territory AS sales_territory, SUM(pay_amount) AS net_revenue FROM fact_order GROUP BY sales_territory'
)
const selectedMetrics = ref<string[]>(['net_revenue'])
const selectedDimensions = ref<string[]>(['sales_territory'])
const baseDimensions = ref<string[]>(['sales_territory'])
// 时间窗与维度条件来自跨页面全局筛选器，与仪表板／多维分析／可视化共用。
const { timeWindow, filters: globalFilters, merge } = useGlobalFilters()
const limit = ref<number>(50)
const loading = ref(false)
const response = ref<QueryResponse | null>(null)
const metrics = ref<MetricDefinition[]>([])
const dimensions = ref<DimensionDefinition[]>([])
const lastPayload = ref<Record<string, unknown> | null>(null)
const drillPath = ref<{ column: string; value: string }[]>([])
const datasets = ref<DatasetSummary[]>([])
const datasetName = ref('')
const hint = ref('')
const reused = ref(false)
const { label, nextDim } = useDisplay()

const chartType = computed(() => {
  const rs = response.value?.resultSet
  if (!rs || rs.rows.length === 0) return null
  const first = rs.rows[0]
  if (!first) return null
  const hasNumber = rs.columns.some((c) => typeof first[c] === 'number')
  const hasCategory = rs.columns.some((c) => typeof first[c] !== 'number')
  return hasNumber && hasCategory ? 'bar' : null
})

async function runQuery(payload: Record<string, unknown>) {
  loading.value = true
  try {
    lastPayload.value = payload
    reused.value = false
    hint.value = ''
    response.value = await api.query(payload)
  } finally {
    loading.value = false
  }
}

function runSql() {
  if (!sql.value.trim()) return
  return runQuery({ sql: sql.value })
}

function drillFilters(): Record<string, string> {
  const result: Record<string, string> = {}
  drillPath.value.forEach((p) => {
    result[p.column] = p.value
  })
  return result
}

function runBuilder() {
  drillPath.value = []
  baseDimensions.value = [...selectedDimensions.value]
  return runQuery({
    metrics: selectedMetrics.value,
    dimensions: selectedDimensions.value,
    timeWindow: timeWindow.value,
    limit: limit.value,
    filters: merge()
  })
}

// 下钻：把点击的维度值固化为过滤条件，并把该维度替换为语义层定义的下一层维度。
// 全局维度条件与下钻条件合并下发，下钻条件优先，且不写回全局状态。
async function drill(column: string, value: string) {
  const next = nextDim(column)
  if (!next) return
  if (tab.value === 'sql') {
    hint.value = '下钻请在「无代码查询」中使用（SQL 编辑器保持原样执行）'
    return
  }
  drillPath.value.push({ column, value })
  const dims = selectedDimensions.value.filter((d) => d !== column && d !== next)
  dims.push(next)
  selectedDimensions.value = dims
  await runQuery({
    metrics: selectedMetrics.value,
    dimensions: dims,
    timeWindow: timeWindow.value,
    limit: limit.value,
    filters: merge(drillFilters())
  })
}

// 全局筛选变化后重跑结构化查询；SQL 编辑器原样执行，不受全局条件影响。
watch(
  [timeWindow, globalFilters],
  () => {
    const last = lastPayload.value
    if (!last || reused.value || loading.value || !Array.isArray(last.metrics)) return
    runQuery({
      metrics: last.metrics,
      dimensions: last.dimensions,
      timeWindow: timeWindow.value,
      limit: last.limit,
      filters: merge(drillFilters())
    })
  },
  { deep: true }
)

function resetDrill() {
  drillPath.value = []
  selectedDimensions.value = [...baseDimensions.value]
  runBuilder()
}

async function refreshDatasets() {
  datasets.value = await api.listDatasets()
}

async function saveDataset() {
  if (!lastPayload.value || reused.value) {
    hint.value = '请先执行一次查询再保存数据集'
    return
  }
  await api.saveDataset(datasetName.value || '未命名数据集', lastPayload.value)
  datasetName.value = ''
  await refreshDatasets()
  hint.value = '已保存为数据集，可在报表页复用（按当前身份隔离）'
}

async function loadDataset(id: string) {
  if (!id) return
  response.value = await api.dataset(id)
  reused.value = true
  hint.value = '当前展示已保存数据集的缓存结果，未重新查询'
}

function onDatasetChange(value: unknown) {
  const id = String(value ?? '')
  if (id) loadDataset(id)
}

onMounted(async () => {
  ;[metrics.value, dimensions.value] = await Promise.all([api.metrics(), api.dimensions()])
  await refreshDatasets()
})
</script>

<template>
  <div class="query q-stack">
    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">查询配置</div>
        <span class="q-chip q-chip--neutral">语义层统一入口</span>
      </div>
      <div class="query__pane">
        <el-tabs v-model="tab">
          <el-tab-pane label="SQL 编辑器" name="sql">
            <div class="sql-editor">
              <div class="sql-editor__bar">
                <span class="sql-editor__dots"><i /><i /><i /></span>
                <span class="sql-editor__file">semantic-query.sql</span>
                <span class="sql-editor__badge">只读</span>
              </div>
              <el-input v-model="sql" type="textarea" :rows="5" class="sql-editor__input" />
              <div class="query__actions">
                <span class="q-subtle">语句解析为 AST 后按白名单校验，写操作一律拒绝</span>
                <el-button type="primary" :loading="loading" @click="runSql">执行 SQL</el-button>
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane label="无代码查询" name="builder">
            <div class="builder">
              <el-form label-width="88px" class="builder__form">
                <el-form-item label="指标">
                  <el-select v-model="selectedMetrics" multiple filterable placeholder="选择指标">
                    <el-option v-for="m in metrics" :key="m.code" :value="m.code" :label="m.businessName" />
                  </el-select>
                </el-form-item>
                <el-form-item label="维度">
                  <el-select v-model="selectedDimensions" multiple filterable placeholder="选择维度">
                    <el-option v-for="d in dimensions" :key="d.code" :value="d.code" :label="d.businessName" />
                  </el-select>
                </el-form-item>
                <el-form-item label="时间窗">
                  <el-select v-model="timeWindow">
                    <el-option v-for="w in TIME_WINDOWS" :key="w.value" :label="w.label" :value="w.value" />
                  </el-select>
                </el-form-item>
                <el-form-item label="行数上限">
                  <el-input-number v-model="limit" :min="1" :max="1000" controls-position="right" />
                </el-form-item>
              </el-form>
              <div class="query__actions query__actions--border">
                <span class="q-subtle">行列级权限由系统按提问者身份注入，无需在查询中声明</span>
                <el-button type="primary" :loading="loading" @click="runBuilder">执行查询</el-button>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </section>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">数据集</div>
        <span class="q-subtle">保存后可跨页面复用，按当前身份隔离</span>
      </div>
      <div class="q-card__body q-toolbar dataset-bar">
        <el-input v-model="datasetName" placeholder="数据集名称" size="small" class="dataset-bar__name" />
        <el-button size="small" @click="saveDataset">保存为数据集</el-button>
        <span class="q-divider" />
        <el-select
          placeholder="复用数据集（不重新查询）"
          size="small"
          clearable
          class="dataset-bar__list"
          @change="onDatasetChange"
        >
          <el-option
            v-for="d in datasets"
            :key="d.id"
            :value="d.id"
            :label="d.name + '（' + d.rows + ' 行）'"
          />
        </el-select>
        <el-button v-if="drillPath.length" size="small" @click="resetDrill">重置钻取</el-button>
      </div>
    </section>

    <el-alert v-if="hint" type="info" :closable="false" show-icon :title="hint" />

    <div v-if="drillPath.length" class="crumb q-card">
      <span class="crumb__label">钻取路径</span>
      <template v-for="(p, i) in drillPath" :key="i">
        <span class="crumb__sep">›</span>
        <span class="q-chip">{{ label(p.column) }}={{ p.value }}</span>
      </template>
    </div>

    <section v-if="response" class="q-card q-card--flush result">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">查询结果</div>
          <div class="q-subtle result__sub">共 {{ response.resultSet.rows.length }} 行 · 口径与追踪同源</div>
        </div>
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button value="standard">标准视图</el-radio-button>
          <el-radio-button value="pivot">交叉表</el-radio-button>
        </el-radio-group>
      </div>
      <div class="q-card__body result__body">
        <template v-if="viewMode === 'standard'">
          <ChartView :chart-type="chartType" :result-set="response.resultSet" />
          <ResultTable :result-set="response.resultSet" :drillable="true" @drill="drill" />
          <ExplanationPanel
            :definitions="response.metricDefinitions"
            :sql="response.sql"
            :steps="response.steps"
            :error="null"
          />
        </template>
        <PivotTable v-else :result-set="response.resultSet" />
      </div>
    </section>

    <div v-else class="placeholder q-card">
      <div class="placeholder__icon">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M3.5 5h17v14h-17z M3.5 10h17 M3.5 14.5h17 M10 5v14"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
        </svg>
      </div>
      <div class="placeholder__title">还没有查询结果</div>
      <p class="q-subtle">配置指标与维度后执行查询，图表、明细与全链路追踪会在这里统一呈现。</p>
    </div>
  </div>
</template>

<style scoped>
.query__pane {
  padding: 4px 22px 18px;
}
.query__actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-top: 16px;
}
.query__actions--border {
  padding-top: 16px;
  border-top: 1px solid var(--q-line-soft);
}

.sql-editor {
  border: 1px solid var(--q-line);
  border-radius: var(--q-radius-sm);
  overflow: hidden;
  background: #fbfcfe;
}
.sql-editor__bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  background: #f4f6fb;
  border-bottom: 1px solid var(--q-line);
}
.sql-editor__dots {
  display: inline-flex;
  gap: 5px;
}
.sql-editor__dots i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d3daea;
}
.sql-editor__dots i:first-child {
  background: #f2b8b8;
}
.sql-editor__dots i:nth-child(2) {
  background: #f2d5a4;
}
.sql-editor__file {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 11.5px;
  color: var(--q-muted);
}
.sql-editor__badge {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 5px;
  background: #eaf8f3;
  color: #0e8a72;
  font-size: 10.5px;
  font-weight: 700;
}
.sql-editor__input :deep(.el-textarea__inner) {
  border: none;
  border-radius: 0;
  box-shadow: none;
  background: #fbfcfe;
  padding: 14px 16px;
  color: #2c3c66;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12.5px;
  line-height: 1.8;
  resize: none;
}
.sql-editor__input :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}
.builder__form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 26px;
  padding-top: 4px;
}
.builder__form :deep(.el-select),
.builder__form :deep(.el-input-number) {
  width: 100%;
}

.dataset-bar__name {
  width: 190px;
}
.dataset-bar__list {
  width: 240px;
}

.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 12px 18px;
  box-shadow: var(--q-shadow-sm);
}
.crumb__label {
  font-size: 12px;
  font-weight: 700;
  color: var(--q-ink-2);
}
.crumb__sep {
  color: #b9c3dc;
  font-size: 16px;
  line-height: 1;
}

.result__sub {
  margin-top: 3px;
  margin-left: 12px;
}
.result__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 46px 24px;
  text-align: center;
  border-style: dashed;
  background: rgba(255, 255, 255, 0.6);
  box-shadow: none;
}
.placeholder__icon {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  margin-bottom: 4px;
  border-radius: 14px;
  background: var(--q-brand-soft);
  color: var(--q-brand);
}
.placeholder__icon svg {
  width: 22px;
  height: 22px;
}
.placeholder__title {
  font-size: 14px;
  font-weight: 700;
  color: var(--q-ink-2);
}
.placeholder .q-subtle {
  max-width: 420px;
}

@media (max-width: 860px) {
  .builder__form {
    grid-template-columns: 1fr;
  }
  .query__actions {
    flex-direction: column;
    align-items: stretch;
  }
  .dataset-bar__name,
  .dataset-bar__list {
    width: 100%;
  }
}
</style>
