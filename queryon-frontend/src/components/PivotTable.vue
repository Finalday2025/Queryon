<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { ResultSet } from '../types'
import { useDisplay } from '../composables/useDisplay'

const props = defineProps<{ resultSet: ResultSet }>()
const { label, unit } = useDisplay()

const dimensionColumns = computed(() =>
  props.resultSet.columns.filter((c) => props.resultSet.rows.some((r) => typeof r[c] !== 'number'))
)
const measureColumns = computed(() =>
  props.resultSet.columns.filter((c) => props.resultSet.rows.every((r) => typeof r[c] === 'number'))
)

const rowDim = ref('')
const colDim = ref('')
const measures = ref<string[]>([])

watch(
  () => props.resultSet,
  () => {
    rowDim.value = dimensionColumns.value[0] ?? ''
    colDim.value = dimensionColumns.value[1] ?? ''
    measures.value = [...measureColumns.value] // 多度量默认全选，可按需取消
  },
  { immediate: true }
)

const measurePairs = computed(() =>
  measures.value.map((m) => ({
    measure: m,
    label: label(m) + (unit(m) ? '（' + unit(m) + '）' : '')
  }))
)

const colValues = computed<string[]>(() => {
  if (!colDim.value) return ['全部']
  const set = new Set<string>()
  props.resultSet.rows.forEach((r) => set.add(String(r[colDim.value])))
  const values = Array.from(set)
  return values.length ? values : ['全部']
})

const rowValues = computed<string[]>(() => {
  if (!rowDim.value) return []
  const set = new Set<string>()
  props.resultSet.rows.forEach((r) => set.add(String(r[rowDim.value])))
  return Array.from(set)
})

const pivotColumns = computed(() =>
  colValues.value.flatMap((col) =>
    measurePairs.value.map((m) => ({
      key: col + '|' + m.measure,
      label: col === '全部' ? m.label : col + ' · ' + m.label,
      col,
      measure: m.measure
    }))
  )
)

function cell(row: string, col: string, measure: string): number {
  return props.resultSet.rows
    .filter(
      (r) =>
        String(r[rowDim.value]) === row &&
        (!colDim.value || col === '全部' || String(r[colDim.value]) === col)
    )
    .reduce((sum, r) => sum + Number(r[measure] ?? 0), 0)
}

function rowTotal(row: string, measure: string): number {
  return props.resultSet.rows
    .filter((r) => String(r[rowDim.value]) === row)
    .reduce((sum, r) => sum + Number(r[measure] ?? 0), 0)
}

const pivotRows = computed(() =>
  rowValues.value.map((r) => {
    const o: Record<string, unknown> = { __row: r }
    pivotColumns.value.forEach((c) => (o[c.key] = cell(r, c.col, c.measure)))
    measurePairs.value.forEach((m) => (o['__total|' + m.measure] = rowTotal(r, m.measure)))
    return o
  })
)

/** 行列维交换（D3-11）：切片／切块时互换行、列维度，不重新查询。 */
function swapAxes() {
  if (!colDim.value) return
  const previousRow = rowDim.value
  rowDim.value = colDim.value
  colDim.value = previousRow
}

// 每个度量单独总计，不把「元」与「单」等不同单位混算。
const grandTotals = computed(() =>
  measurePairs.value.map((m) => ({
    measure: m.measure,
    label: m.label,
    unit: unit(m.measure),
    value: props.resultSet.rows.reduce((sum, r) => sum + Number(r[m.measure] ?? 0), 0)
  }))
)
</script>

<template>
  <div v-if="resultSet.rows.length" class="pivot q-card q-card--flush">
    <div class="q-card__head">
      <div class="q-card__title">交叉表配置</div>
      <span class="q-chip q-chip--neutral">多维透视</span>
    </div>
    <div class="q-card__body pivot__body">
      <div class="controls">
        <div class="control">
          <label>行维度</label>
          <el-select v-model="rowDim" size="small" placeholder="选择行维度">
            <el-option v-for="c in dimensionColumns" :key="c" :value="c" :label="label(c)" />
          </el-select>
        </div>
        <div class="control">
          <label>列维度</label>
          <el-select v-model="colDim" size="small" clearable placeholder="可选">
            <el-option v-for="c in dimensionColumns" :key="c" :value="c" :label="label(c)" />
          </el-select>
        </div>
        <div class="control control--wide">
          <label>度量</label>
          <el-select v-model="measures" multiple size="small" placeholder="选择度量">
            <el-option v-for="c in measureColumns" :key="c" :value="c" :label="label(c)" />
          </el-select>
        </div>
        <div class="control">
          <label>&nbsp;</label>
          <el-button size="small" :disabled="!colDim" @click="swapAxes">交换行列</el-button>
        </div>
      </div>

      <el-table v-if="measures.length" :data="pivotRows" border size="small">
        <el-table-column prop="__row" :label="label(rowDim) || '行'" min-width="140" />
        <el-table-column
          v-for="c in pivotColumns"
          :key="c.key"
          :prop="c.key"
          :label="c.label"
          min-width="130"
        />
        <el-table-column
          v-for="m in measurePairs"
          :key="m.measure"
          :prop="'__total|' + m.measure"
          :label="'合计 · ' + m.label"
          min-width="140"
        />
      </el-table>
      <el-alert v-else title="未选择度量，无法生成交叉表" type="info" :closable="false" />

      <div class="grand">
        <span class="grand__label">总计</span>
        <span v-for="t in grandTotals" :key="t.measure" class="grand__item">
          {{ t.label }}：<strong>{{ t.value }}{{ t.unit }}</strong>
        </span>
      </div>
    </div>
  </div>
  <el-empty v-else description="暂无数据可透视" />
</template>

<style scoped>
.pivot__body {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.controls {
  display: flex;
  gap: 14px;
  align-items: flex-end;
  flex-wrap: wrap;
  padding: 14px 16px;
  border: 1px solid var(--q-line-soft);
  border-radius: var(--q-radius-sm);
  background: #fafbfe;
}
.control {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 180px;
}
.control--wide {
  width: 240px;
}
.control label {
  font-size: 11.5px;
  font-weight: 650;
  color: var(--q-ink-2);
}
.control :deep(.el-select) {
  width: 100%;
}
.grand {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px 16px;
  border: 1px solid #e2e6ff;
  border-radius: var(--q-radius-sm);
  background: linear-gradient(100deg, #f4f6ff, #fafbff);
}
.grand__label {
  font-size: 11.5px;
  font-weight: 700;
  color: var(--q-muted);
}
.grand__item {
  padding: 4px 11px;
  border-radius: 999px;
  background: #fff;
  border: 1px solid #e2e6ff;
  color: var(--q-ink-2);
  font-size: 12px;
}
.grand__item strong {
  color: var(--q-brand-deep);
  font-variant-numeric: tabular-nums;
}

@media (max-width: 760px) {
  .control,
  .control--wide {
    width: 100%;
  }
}
</style>
