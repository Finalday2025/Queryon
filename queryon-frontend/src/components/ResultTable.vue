<script setup lang="ts">
import type { ResultSet } from '../types'
import { useDisplay } from '../composables/useDisplay'

const props = withDefaults(
  defineProps<{ resultSet: ResultSet; drillable?: boolean }>(),
  { drillable: false }
)
const emit = defineEmits<{ (e: 'drill', column: string, value: string): void }>()
const { label, nextDim } = useDisplay()

function onCellClick(row: unknown, column: unknown) {
  if (!props.drillable) return
  const col = (column as { property?: string } | undefined)?.property
  if (!col || !nextDim(col)) return
  const value = (row as Record<string, unknown>)[col]
  emit('drill', col, value == null ? '' : String(value))
}
</script>

<template>
  <div v-if="resultSet.columns.length" class="result-table q-card q-card--flush">
    <div class="q-card__head">
      <div class="q-card__title">明细数据</div>
      <span class="q-chip q-chip--neutral">{{ resultSet.rows.length }} 行</span>
    </div>
    <div class="result-table__wrap">
      <el-table
        :data="resultSet.rows"
        border
        stripe
        height="320"
        size="small"
        @cell-click="onCellClick"
      >
        <el-table-column
          v-for="col in resultSet.columns"
          :key="col"
          :prop="col"
          :label="label(col)"
          :class-name="drillable && nextDim(col) ? 'drillable-cell' : ''"
          min-width="120"
        >
          <template #header>
            <span>{{ label(col) }}</span>
            <span v-if="drillable && nextDim(col)" class="drill-mark" title="点击单元格下钻"> ▾</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div class="result-table__meta">
      <span>返回 {{ resultSet.rows.length }} 行</span>
      <template v-if="resultSet.truncated">
        <span>（已达上限，实际 {{ resultSet.totalRows }} 行）</span>
      </template>
      <span v-if="drillable" class="hint">带 ▾ 的维度列可点击单元格下钻</span>
    </div>
  </div>
  <el-empty v-else description="无结果" />
</template>

<style scoped>
.result-table__wrap {
  padding: 14px 16px 0;
}
.result-table__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 11px 18px 14px;
  color: var(--q-muted);
  font-size: 11.5px;
}
.hint {
  margin-left: auto;
  color: var(--q-brand-deep);
  font-weight: 600;
}
.result-table :deep(.drillable-cell) {
  cursor: pointer;
  color: var(--q-brand-deep);
  font-weight: 600;
}
.drill-mark {
  color: var(--q-brand);
}
</style>
