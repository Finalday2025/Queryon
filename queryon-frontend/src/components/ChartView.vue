<script setup lang="ts">
import { ref, computed, onMounted, watch, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import type { ResultSet } from '../types'
import { useDisplay } from '../composables/useDisplay'
import { exportChartPng } from '../utils/export'

type ChartType = 'bar' | 'line' | 'pie' | 'scatter'
type SortMode = 'none' | 'asc' | 'desc'

const props = withDefaults(
  defineProps<{
    chartType: string | null
    resultSet: ResultSet
    /** 指定度量列（多图工作台一图一列）；留空时自动取首个数值列。 */
    measure?: string
    /** 指定分类轴；留空时自动取首个非数值列。 */
    axis?: string
    /** 散点图 X 轴度量列；留空时自动取第一个非 Y 轴的数值列。 */
    xMeasure?: string
    sort?: SortMode
    /** 0 表示不截断。 */
    topN?: number
    showLegend?: boolean
    showValue?: boolean
    configurable?: boolean
    title?: string
  }>(),
  {
    measure: '',
    axis: '',
    xMeasure: '',
    sort: 'none',
    topN: 0,
    showLegend: true,
    showValue: false,
    configurable: false,
    title: '数据可视化'
  }
)

const emit = defineEmits<{
  (e: 'update:chartType', value: ChartType): void
  (e: 'update:axis', value: string): void
  (e: 'update:xMeasure', value: string): void
  (e: 'update:sort', value: SortMode): void
  (e: 'update:topN', value: number): void
  (e: 'update:showLegend', value: boolean): void
  (e: 'update:showValue', value: boolean): void
}>()

const { label, meta } = useDisplay()
const el = ref<HTMLElement | undefined>()
let chart: echarts.ECharts | null = null
let observer: ResizeObserver | null = null

const palette = ['#5a68f8', '#12b8a6', '#f0a83b', '#8b7cf6', '#ec6d84', '#3aa7e8', '#63c47a']

const typeLabels: Record<string, string> = {
  bar: '柱状图',
  line: '折线图',
  pie: '饼图',
  scatter: '散点图'
}
const typeLabel = computed(() => typeLabels[props.chartType ?? ''] ?? '图表')

function isChart(type: string | null): type is ChartType {
  return type === 'bar' || type === 'line' || type === 'pie' || type === 'scatter'
}

const rows = computed(() => props.resultSet.rows)
const columns = computed(() => props.resultSet.columns)

/** 数值列：至少有一个数值，且非空值也都是数值（null 视为缺失而非 0）。 */
const numericColumns = computed(() =>
  columns.value.filter(
    (c) =>
      rows.value.some((r) => typeof r[c] === 'number') &&
      rows.value.every((r) => r[c] == null || typeof r[c] === 'number')
  )
)

/** 分类列：存在非数值取值的列，用于分类轴候选。 */
const categoryColumns = computed(() =>
  columns.value.filter((c) => rows.value.some((r) => typeof r[c] !== 'number'))
)

const measureColumn = computed(() => {
  if (props.measure && columns.value.includes(props.measure)) return props.measure
  return numericColumns.value[0] ?? ''
})

const axisColumn = computed(() => {
  if (props.axis && categoryColumns.value.includes(props.axis)) return props.axis
  return categoryColumns.value[0] ?? columns.value[0] ?? ''
})

const xMeasureColumn = computed(() => {
  if (
    props.xMeasure &&
    numericColumns.value.includes(props.xMeasure) &&
    props.xMeasure !== measureColumn.value
  ) {
    return props.xMeasure
  }
  return numericColumns.value.find((column) => column !== measureColumn.value) ?? ''
})

const scatterXOptions = computed(() =>
  numericColumns.value.filter((column) => column !== measureColumn.value)
)

interface ChartPoint {
  name: string
  value: number
}

interface ScatterPoint {
  name: string
  value: [number, number]
}

/** 散点图需要两个数值列；缺失时返回空集合，由占位提示说明原因。 */
const scatterPoints = computed<ScatterPoint[]>(() => {
  const yColumn = measureColumn.value
  const xColumn = xMeasureColumn.value
  if (!yColumn || !xColumn) return []
  return rows.value
    .filter((row) => typeof row[xColumn] === 'number' && typeof row[yColumn] === 'number')
    .map((row) => ({
      name: String(row[axisColumn.value] ?? ''),
      value: [Number(row[xColumn]), Number(row[yColumn])]
    }))
})

const hasData = computed(() =>
  props.chartType === 'scatter' ? scatterPoints.value.length > 0 : points.value.length > 0
)

/** 排序与 TopN 只在展示层裁剪，不改动后端结果集与明细表。 */
const points = computed<ChartPoint[]>(() => {
  const measure = measureColumn.value
  const axis = axisColumn.value
  if (!measure || rows.value.length === 0) return []
  const data = rows.value.map((row) => ({
    name: String(row[axis] ?? ''),
    value: Number(row[measure] ?? 0)
  }))
  if (props.sort === 'asc') data.sort((a, b) => a.value - b.value)
  if (props.sort === 'desc') data.sort((a, b) => b.value - a.value)
  const limit = Math.max(0, Math.floor(props.topN || 0))
  return limit > 0 ? data.slice(0, limit) : data
})

function downloadPng() {
  exportChartPng(el.value, props.title || 'queryon-chart')
}

function onType(value: unknown) {
  if (value === 'bar' || value === 'line' || value === 'pie') emit('update:chartType', value)
}
function onAxis(value: unknown) {
  emit('update:axis', String(value ?? ''))
}
function onXMeasure(value: unknown) {
  emit('update:xMeasure', String(value ?? ''))
}
function onSort(value: unknown) {
  if (value === 'none' || value === 'asc' || value === 'desc') emit('update:sort', value)
}
function onTopN(value: unknown) {
  emit('update:topN', Number(value ?? 0))
}
function onLegend(value: unknown) {
  emit('update:showLegend', Boolean(value))
}
function onValueLabel(value: unknown) {
  emit('update:showValue', Boolean(value))
}

function render() {
  const type = props.chartType
  if (!el.value || !isChart(type) || !hasData.value) {
    chart?.clear()
    return
  }
  if (!chart || chart.isDisposed() || chart.getDom() !== el.value) {
    chart?.dispose()
    chart = echarts.init(el.value)
  }

  const tooltip = {
    backgroundColor: 'rgba(20, 28, 49, 0.94)',
    borderWidth: 0,
    padding: [9, 13],
    textStyle: { color: '#fff', fontSize: 12 },
    extraCssText: 'border-radius:9px;box-shadow:0 12px 32px -12px rgba(0,0,0,.45);'
  }
  const textStyle = {
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif"
  }
  const legendTextStyle = { color: '#8491ad', fontSize: 11 }
  const data = points.value

  if (type === 'scatter') {
    chart.setOption(
      {
        color: palette,
        textStyle,
        animationDuration: 420,
        tooltip: { ...tooltip, trigger: 'item', formatter: '{b}<br/>{c}' },
        grid: { left: 12, right: 26, top: 30, bottom: 8, containLabel: true },
        xAxis: {
          type: 'value',
          name: label(xMeasureColumn.value),
          nameTextStyle: { color: '#8491ad', fontSize: 10 },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#8491ad', fontSize: 11 },
          splitLine: { lineStyle: { color: '#eef1f7', type: 'dashed' } }
        },
        yAxis: {
          type: 'value',
          name: label(measureColumn.value),
          nameTextStyle: { color: '#8491ad', fontSize: 10 },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#8491ad', fontSize: 11 },
          splitLine: { lineStyle: { color: '#eef1f7', type: 'dashed' } }
        },
        series: [
          {
            type: 'scatter',
            symbolSize: 10,
            data: scatterPoints.value,
            itemStyle: { color: '#5a68f8', opacity: 0.82, borderColor: '#fff', borderWidth: 1 },
            emphasis: { focus: 'series', scale: 1.3 }
          }
        ]
      },
      true
    )
    chart.resize()
    return
  }

  if (type === 'pie') {
    chart.setOption(
      {
        color: palette,
        textStyle,
        animationDuration: 420,
        tooltip: { ...tooltip, trigger: 'item', formatter: '{b}<br/>{c} ({d}%)' },
        legend: {
          show: props.showLegend,
          type: 'scroll',
          bottom: 0,
          icon: 'circle',
          itemWidth: 8,
          itemHeight: 8,
          itemGap: 14,
          textStyle: legendTextStyle
        },
        series: [
          {
            type: 'pie',
            radius: ['42%', '68%'],
            center: ['50%', props.showLegend ? '45%' : '52%'],
            avoidLabelOverlap: true,
            minAngle: 2,
            itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 6 },
            label: {
              color: '#42506e',
              fontSize: 11,
              formatter: props.showValue ? '{b}  {c}' : '{b}  {d}%'
            },
            labelLine: { length: 10, length2: 10, lineStyle: { color: '#c9d2e6' } },
            data: data.map((point) => ({ name: point.name, value: point.value }))
          }
        ]
      },
      true
    )
  } else {
    const isLine = type === 'line'
    const barGradient = {
      type: 'linear' as const,
      x: 0,
      y: 0,
      x2: 0,
      y2: 1,
      colorStops: [
        { offset: 0, color: '#7884ff' },
        { offset: 1, color: '#4f5ff5' }
      ]
    }
    chart.setOption(
      {
        color: palette,
        textStyle,
        animationDuration: 420,
        tooltip: { ...tooltip, trigger: 'axis', axisPointer: { type: 'shadow' } },
        legend: {
          show: props.showLegend,
          top: 0,
          right: 10,
          icon: 'circle',
          itemWidth: 8,
          itemHeight: 8,
          textStyle: legendTextStyle
        },
        grid: {
          left: 12,
          right: 22,
          top: props.showLegend ? 38 : 24,
          bottom: 8,
          containLabel: true
        },
        xAxis: {
          type: 'category',
          data: data.map((point) => point.name),
          axisLine: { lineStyle: { color: '#e3e8f2' } },
          axisTick: { show: false },
          axisLabel: { color: '#8491ad', fontSize: 11, hideOverlap: true }
        },
        yAxis: {
          type: 'value',
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#8491ad', fontSize: 11 },
          splitLine: { lineStyle: { color: '#eef1f7', type: 'dashed' } }
        },
        series: [
          {
            type: isLine ? 'line' : 'bar',
            name: label(measureColumn.value),
            data: data.map((point) => point.value),
            barMaxWidth: 38,
            smooth: isLine,
            symbol: 'circle',
            symbolSize: 7,
            label: {
              show: props.showValue,
              position: 'top',
              color: '#6b7897',
              fontSize: 10
            },
            itemStyle: {
              color: isLine ? '#5a68f8' : barGradient,
              borderRadius: isLine ? 0 : [6, 6, 0, 0]
            },
            lineStyle: isLine ? { width: 2.6, color: '#5a68f8' } : undefined,
            areaStyle: isLine
              ? {
                  color: {
                    type: 'linear' as const,
                    x: 0,
                    y: 0,
                    x2: 0,
                    y2: 1,
                    colorStops: [
                      { offset: 0, color: 'rgba(90, 104, 248, 0.24)' },
                      { offset: 1, color: 'rgba(90, 104, 248, 0)' }
                    ]
                  }
                }
              : undefined,
            emphasis: { focus: 'series' }
          }
        ]
      },
      true
    )
  }
  chart.resize()
}

onMounted(() => {
  render()
  if (el.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => chart?.resize())
    observer.observe(el.value)
  }
})

watch(
  () => [
    props.chartType,
    props.resultSet,
    props.measure,
    props.axis,
    props.xMeasure,
    props.sort,
    props.topN,
    props.showLegend,
    props.showValue,
    meta.value
  ],
  render,
  { deep: true, flush: 'post' }
)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div v-if="isChart(chartType)" class="chart-card q-card q-card--flush">
    <div class="q-card__head">
      <div class="q-card__title">{{ title }}</div>
      <div class="chart-card__tools">
        <span class="q-chip q-chip--neutral">{{ typeLabel }}</span>
        <span v-if="topN > 0 && resultSet.rows.length > topN" class="q-chip">Top {{ topN }}</span>
        <el-popover v-if="configurable" placement="bottom-end" :width="268" trigger="click">
          <template #reference>
            <el-button size="small" text type="primary">图表配置</el-button>
          </template>
          <div class="chart-config">
            <div class="chart-config__row">
              <span class="chart-config__label">图形</span>
              <el-radio-group
                :model-value="chartType ?? 'bar'"
                size="small"
                @update:model-value="onType"
              >
                <el-radio-button value="bar">柱</el-radio-button>
                <el-radio-button value="line">线</el-radio-button>
                <el-radio-button value="pie">饼</el-radio-button>
                <el-radio-button value="scatter">散点</el-radio-button>
              </el-radio-group>
            </div>
            <div v-if="chartType === 'scatter'" class="chart-config__row">
              <span class="chart-config__label">X 轴</span>
              <el-select
                :model-value="xMeasureColumn"
                size="small"
                class="chart-config__control"
                @update:model-value="onXMeasure"
              >
                <el-option v-for="c in scatterXOptions" :key="c" :value="c" :label="label(c)" />
              </el-select>
            </div>
            <div class="chart-config__row">
              <span class="chart-config__label">分类轴</span>
              <el-select
                :model-value="axisColumn"
                size="small"
                class="chart-config__control"
                @update:model-value="onAxis"
              >
                <el-option label="自动识别" value="" />
                <el-option v-for="c in categoryColumns" :key="c" :value="c" :label="label(c)" />
              </el-select>
            </div>
            <div class="chart-config__row">
              <span class="chart-config__label">排序</span>
              <el-select
                :model-value="sort"
                size="small"
                class="chart-config__control"
                @update:model-value="onSort"
              >
                <el-option label="保持结果顺序" value="none" />
                <el-option label="度量升序" value="asc" />
                <el-option label="度量降序" value="desc" />
              </el-select>
            </div>
            <div class="chart-config__row">
              <span class="chart-config__label">Top N</span>
              <el-input-number
                :model-value="topN"
                :min="0"
                :max="200"
                size="small"
                controls-position="right"
                class="chart-config__control"
                @update:model-value="onTopN"
              />
            </div>
            <div class="chart-config__row">
              <span class="chart-config__label">图例</span>
              <el-switch :model-value="showLegend" @update:model-value="onLegend" />
            </div>
            <div class="chart-config__row">
              <span class="chart-config__label">数值标签</span>
              <el-switch :model-value="showValue" @update:model-value="onValueLabel" />
            </div>
            <p class="chart-config__note">Top N 填 0 表示不截断；排序与截断只影响图表，不改动明细。</p>
          </div>
        </el-popover>
        <el-button size="small" text type="primary" @click="downloadPng">导出图片</el-button>
      </div>
    </div>
    <div class="chart-card__body">
      <div v-if="hasData" ref="el" class="chart-canvas" />
      <div v-else class="chart-empty q-subtle">
        {{ chartType === 'scatter'
          ? '暂无可绘制的数据：散点图需要两个数值列，可在「图表配置」中指定 X 轴'
          : '暂无可绘制的数据：需要 1 个数值度量列与 1 个分类列' }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.chart-card__tools {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.chart-card__body {
  padding: 12px 16px 18px;
}
.chart-canvas {
  width: 100%;
  height: 300px;
}
.chart-empty {
  display: grid;
  place-items: center;
  height: 300px;
  text-align: center;
}
.chart-config {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.chart-config__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.chart-config__label {
  flex: 0 0 58px;
  font-size: 12px;
  color: var(--q-ink-2);
}
.chart-config__control {
  flex: 1;
  min-width: 0;
}
.chart-config__note {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.6;
  color: var(--q-muted);
}
</style>
