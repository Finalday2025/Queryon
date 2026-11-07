<script setup lang="ts">
import { ref, computed, reactive, watch, onMounted } from 'vue'
import type { QueryResponse, MetricDefinition, DimensionDefinition } from '../types'
import { api } from '../api/client'
import { useDisplay } from '../composables/useDisplay'
import { TIME_WINDOWS, timeWindowLabel, useGlobalFilters } from '../composables/useGlobalFilters'
import ChartView from '../components/ChartView.vue'
import ResultTable from '../components/ResultTable.vue'
import ExplanationPanel from '../components/ExplanationPanel.vue'

type ChartType = 'bar' | 'line' | 'pie' | 'scatter'
type SortMode = 'none' | 'asc' | 'desc'

interface CardConfig {
  chartType: ChartType
  axis: string
  xMeasure: string
  sort: SortMode
  topN: number
  showLegend: boolean
  showValue: boolean
}

/**
 * 可视化分析工作台（D3-05）：一次取数按度量生成多图；
 * 每张图可独立配置图形、分类轴、排序、Top N 与图例，切换不触发重新查询。
 * 时间窗与维度条件来自跨页面全局筛选器，与其他入口同口径。
 */
const metrics = ref<MetricDefinition[]>([])
const dimensions = ref<DimensionDefinition[]>([])
const selectedMetrics = ref<string[]>(['net_revenue', 'order_count'])
const selectedDimensions = ref<string[]>(['sales_territory'])
const limit = ref<number>(50)
const chartType = ref<ChartType>('bar')
const loading = ref(false)
const response = ref<QueryResponse | null>(null)
const hint = ref('')
const datasetName = ref('')
const lastPayload = ref<Record<string, unknown> | null>(null)
const { label } = useDisplay()
const { timeWindow, filters, activeCount, merge } = useGlobalFilters()

const typeLabels: Record<ChartType, string> = {
  bar: '柱状图',
  line: '折线图',
  pie: '饼图',
  scatter: '散点图'
}

const cardConfigs = reactive<Record<string, CardConfig>>({})

// 配置在渲染前按度量列预先建好，避免在渲染函数里写响应式对象造成重复渲染。
function defaultCardConfig(): CardConfig {
  return {
    chartType: chartType.value,
    axis: '',
    xMeasure: '',
    sort: 'none',
    topN: 0,
    showLegend: true,
    showValue: false
  }
}

const fallbackConfig = defaultCardConfig()

function cfgOf(measure: string): CardConfig {
  return cardConfigs[measure] ?? fallbackConfig
}

const numericColumns = computed<string[]>(() => {
  const rs = response.value?.resultSet
  if (!rs || !rs.rows.length) return []
  return rs.columns.filter((c) => rs.rows.every((r) => typeof r[c] === 'number'))
})

const hasCategory = computed(() => {
  const rs = response.value?.resultSet
  if (!rs || !rs.rows.length) return false
  return rs.columns.some((c) => rs.rows.some((r) => typeof r[c] !== 'number'))
})

/** 时间型维度优先折线；类别少且度量少时饼图更易读；其余用柱状。 */
const recommendedType = computed<ChartType>(() => {
  const rs = response.value?.resultSet
  if (!rs || !rs.rows.length) return 'bar'
  const timeLike = selectedDimensions.value.some(
    (d) => /(date|time|month|week|year|day)/i.test(d) || /(日期|时间|月份|周|年)/.test(label(d))
  )
  if (timeLike && rs.rows.length > 1) return 'line'
  if (rs.rows.length <= 6 && numericColumns.value.length <= 2) return 'pie'
  return 'bar'
})

function applyRecommended() {
  chartType.value = recommendedType.value
}

async function run() {
  if (!selectedMetrics.value.length) {
    hint.value = '请至少选择一个受管指标'
    return
  }
  loading.value = true
  try {
    const payload: Record<string, unknown> = {
      metrics: selectedMetrics.value,
      dimensions: selectedDimensions.value,
      timeWindow: timeWindow.value,
      filters: merge(),
      limit: limit.value
    }
    lastPayload.value = payload
    hint.value = ''
    response.value = await api.query(payload)
  } finally {
    loading.value = false
  }
}

async function saveDataset() {
  if (!lastPayload.value) {
    hint.value = '请先执行一次查询再保存数据集'
    return
  }
  await api.saveDataset(datasetName.value || '未命名数据集', lastPayload.value)
  datasetName.value = ''
  hint.value = '已保存为数据集，可在报表页复用（按当前身份隔离）'
}

// 全局筛选变更后，已出图的页面按当前配置自动重取数；未查询过则保持空态。
watch(
  [timeWindow, filters],
  () => {
    if (!lastPayload.value || loading.value) return
    run()
  },
  { deep: true }
)

// 每次结果集变化后为新出现的度量列补齐默认配置（flush 早于渲染）。
watch(
  numericColumns,
  (cols) => {
    cols.forEach((col) => {
      if (!cardConfigs[col]) cardConfigs[col] = defaultCardConfig()
    })
  },
  { immediate: true, flush: 'pre' }
)

// 统一图形选择作用于所有卡片；单卡改动仍可覆盖。
watch(chartType, (value) => {
  Object.values(cardConfigs).forEach((cfg) => {
    cfg.chartType = value
  })
})

onMounted(async () => {
  ;[metrics.value, dimensions.value] = await Promise.all([api.metrics(), api.dimensions()])
})
</script>

<template>
  <div class="analysis">
    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">可视化分析配置</div>
          <div class="q-subtle">按度量一次取数生成多图，切换图形与排序无需重新查询</div>
        </div>
        <span class="q-chip q-chip--neutral">D3-05 图表配置与推荐</span>
      </div>
      <div class="q-card__body">
        <el-form label-width="88px" class="analysis__form">
          <el-form-item label="指标">
            <el-select v-model="selectedMetrics" multiple filterable placeholder="选择指标">
              <el-option v-for="m in metrics" :key="m.code" :value="m.code" :label="m.businessName" />
            </el-select>
          </el-form-item>
          <el-form-item label="维度">
            <el-select v-model="selectedDimensions" multiple filterable placeholder="选择维度（建议 1 个分类轴）">
              <el-option v-for="d in dimensions" :key="d.code" :value="d.code" :label="d.businessName" />
            </el-select>
          </el-form-item>
          <el-form-item label="时间窗">
            <el-select v-model="timeWindow">
              <el-option v-for="w in TIME_WINDOWS" :key="w.value" :label="w.label" :value="w.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="统一图形">
            <el-radio-group v-model="chartType" size="small">
              <el-radio-button value="bar">柱状图</el-radio-button>
              <el-radio-button value="line">折线图</el-radio-button>
              <el-radio-button value="pie">饼图</el-radio-button>
              <el-radio-button value="scatter">散点图</el-radio-button>
              </el-radio-group>
          </el-form-item>
          <el-form-item label="行数上限">
            <el-input-number v-model="limit" :min="1" :max="1000" controls-position="right" />
          </el-form-item>
        </el-form>
        <div class="query__actions query__actions--border">
          <span class="q-subtle">口径、权限与行列级过滤与问数／报表同源；每张图可再单独配置</span>
          <el-button type="primary" :loading="loading" @click="run">生成图表</el-button>
        </div>
      </div>
    </section>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">数据集</div>
        <span class="q-subtle">当前取数可保存，供报表与多维分析复用</span>
      </div>
      <div class="q-card__body q-toolbar analysis__toolbar">
        <el-input v-model="datasetName" placeholder="数据集名称" size="small" class="analysis__name" />
        <el-button size="small" @click="saveDataset">保存为数据集</el-button>
      </div>
    </section>

    <el-alert v-if="hint" type="info" :closable="false" show-icon :title="hint" />

    <section v-if="response" class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">图表与明细</div>
          <div class="q-subtle">每张图可独立选择分类轴、排序与 Top N；明细表始终保留完整结果</div>
        </div>
        <div class="analysis__meta">
          <span class="q-chip q-chip--neutral">{{ numericColumns.length }} 个度量 · 共 {{ response.resultSet.rows.length }} 行</span>
          <span class="q-chip q-chip--neutral">
            全局：{{ timeWindowLabel(timeWindow) }}<template v-if="activeCount"> · {{ activeCount }} 个条件</template>
          </span>
          <el-button
            v-if="recommendedType !== chartType"
            size="small"
            text
            type="primary"
            @click="applyRecommended"
          >
            推荐 {{ typeLabels[recommendedType] }} · 一键应用
          </el-button>
        </div>
      </div>
      <div class="q-card__body analysis__body">
        <el-alert
          v-if="!hasCategory"
          type="warning"
          :closable="false"
          title="结果中没有分类维度，图表会以数值列作为分类轴；建议选择销售大区／品类等维度"
        />
        <div class="analysis__charts">
          <ChartView
            v-for="col in numericColumns"
            :key="col"
            v-model:chart-type="cfgOf(col).chartType"
            v-model:axis="cfgOf(col).axis"
            v-model:x-measure="cfgOf(col).xMeasure"
            v-model:sort="cfgOf(col).sort"
            v-model:top-n="cfgOf(col).topN"
            v-model:show-legend="cfgOf(col).showLegend"
            v-model:show-value="cfgOf(col).showValue"
            :result-set="response.resultSet"
            :measure="col"
            configurable
            :title="'数据可视化 · ' + label(col)"
          />
        </div>
        <ResultTable :result-set="response.resultSet" />
        <ExplanationPanel
          :definitions="response.metricDefinitions"
          :sql="response.sql"
          :steps="response.steps"
          :error="null"
        />
      </div>
    </section>
    <div v-else class="placeholder q-card">
      <div class="placeholder__title">还没有可视化结果</div>
      <p class="q-subtle">选择指标与维度后生成图表；多个指标会一次取数生成多张图，切换图形不重新查询。</p>
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
.analysis__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.analysis__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.analysis__charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 14px;
}
</style>
