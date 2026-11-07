<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import type {
  QueryResponse,
  MetricDefinition,
  DimensionDefinition,
  DatasetSummary
} from '../types'
import { api } from '../api/client'
import { useDisplay } from '../composables/useDisplay'
import { TIME_WINDOWS, useGlobalFilters } from '../composables/useGlobalFilters'
import PivotTable from '../components/PivotTable.vue'
import ResultTable from '../components/ResultTable.vue'
import ExplanationPanel from '../components/ExplanationPanel.vue'

/** 多维分析工作台：一次取数后做切片／切块、行列维交换、逐层下钻与数据集复用。 */
const metrics = ref<MetricDefinition[]>([])
const dimensions = ref<DimensionDefinition[]>([])
const selectedMetrics = ref<string[]>(['net_revenue', 'order_count'])
const selectedDimensions = ref<string[]>(['sales_territory', 'category'])
const baseDimensions = ref<string[]>(['sales_territory', 'category'])
// 时间窗与维度条件来自跨页面全局筛选器，与问数／仪表板／可视化共用。
const { timeWindow, filters: globalFilters, merge } = useGlobalFilters()
const limit = ref<number>(50)
const loading = ref(false)
const response = ref<QueryResponse | null>(null)
const hint = ref('')
const drillPath = ref<{ column: string; value: string }[]>([])
const datasets = ref<DatasetSummary[]>([])
const datasetName = ref('')
const lastPayload = ref<Record<string, unknown> | null>(null)
const { label, nextDim } = useDisplay()

function filtersFromPath(): Record<string, string> {
  const filters: Record<string, string> = {}
  drillPath.value.forEach((p) => {
    filters[p.column] = p.value
  })
  return filters
}

async function run() {
  if (!selectedMetrics.value.length) {
    hint.value = '请至少选择一个受管指标'
    return
  }
  loading.value = true
  try {
    if (!drillPath.value.length) {
      baseDimensions.value = [...selectedDimensions.value]
    }
    const payload: Record<string, unknown> = {
      metrics: selectedMetrics.value,
      dimensions: selectedDimensions.value,
      timeWindow: timeWindow.value,
      limit: limit.value,
      filters: merge(filtersFromPath())
    }
    lastPayload.value = payload
    hint.value = ''
    response.value = await api.query(payload)
  } finally {
    loading.value = false
  }
}

// 全局筛选变化后，已在看结果的页面自动重取数；下钻条件保持优先。
watch(
  [timeWindow, globalFilters],
  () => {
    if (!lastPayload.value || loading.value) return
    run()
  },
  { deep: true }
)

// 下钻：把点击的维度值固化为过滤条件，并切到语义层定义的下一层维度。
async function drill(column: string, value: string) {
  const next = nextDim(column)
  if (!next) return
  drillPath.value.push({ column, value })
  const dims = selectedDimensions.value.filter((d) => d !== column && d !== next)
  dims.push(next)
  selectedDimensions.value = dims
  await run()
}

function resetDrill() {
  drillPath.value = []
  selectedDimensions.value = [...baseDimensions.value]
  run()
}

async function refreshDatasets() {
  datasets.value = await api.listDatasets()
}

async function saveDataset() {
  if (!lastPayload.value) {
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
  <div class="analysis">
    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">多维分析配置</div>
          <div class="q-subtle">一次取数，交叉透视、下钻与数据集复用共享同一结果集</div>
        </div>
        <span class="q-chip q-chip--neutral">D3-11 自助多维分析</span>
      </div>
      <div class="q-card__body">
        <el-form label-width="88px" class="analysis__form">
          <el-form-item label="指标">
            <el-select v-model="selectedMetrics" multiple filterable placeholder="选择指标">
              <el-option v-for="m in metrics" :key="m.code" :value="m.code" :label="m.businessName" />
            </el-select>
          </el-form-item>
          <el-form-item label="维度">
            <el-select v-model="selectedDimensions" multiple filterable placeholder="选择维度（建议 2 个以上）">
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
          <el-button type="primary" :loading="loading" @click="run">执行查询</el-button>
        </div>
      </div>
    </section>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">数据集</div>
        <span class="q-subtle">保存后可跨页面复用，按当前身份隔离</span>
      </div>
      <div class="q-card__body q-toolbar analysis__toolbar">
        <el-input v-model="datasetName" placeholder="数据集名称" size="small" class="analysis__name" />
        <el-button size="small" @click="saveDataset">保存为数据集</el-button>
        <span class="q-divider" />
        <el-select
          placeholder="复用数据集（不重新查询）"
          size="small"
          clearable
          class="analysis__list"
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

    <section v-if="response" class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">交叉表与明细</div>
        <span class="q-chip q-chip--neutral">共 {{ response.resultSet.rows.length }} 行</span>
      </div>
      <div class="q-card__body analysis__body">
        <PivotTable :result-set="response.resultSet" />
        <ResultTable :result-set="response.resultSet" :drillable="true" @drill="drill" />
        <ExplanationPanel
          :definitions="response.metricDefinitions"
          :sql="response.sql"
          :steps="response.steps"
          :error="null"
        />
      </div>
    </section>
    <div v-else class="placeholder q-card">
      <div class="placeholder__title">还没有分析结果</div>
      <p class="q-subtle">选择指标与多个维度后执行查询，交叉表、下钻与数据集复用会在这里统一呈现。</p>
    </div>
  </div>
</template>

<style scoped>
.analysis {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.analysis__form {
  padding: 4px 4px 0;
}
.analysis__toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.analysis__name {
  width: 180px;
}
.analysis__list {
  width: 220px;
}
.analysis__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  font-size: 13px;
}
.crumb__label {
  color: var(--q-text-soft, #8491ad);
}
.crumb__sep {
  color: var(--q-primary, #5a68f8);
}
</style>
