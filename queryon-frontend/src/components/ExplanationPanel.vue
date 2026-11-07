<script setup lang="ts">
import type { TraceStep, StructuredError } from '../types'

defineProps<{
  definitions: string[]
  sql: string | null
  steps: TraceStep[]
  error: StructuredError | null
}>()
</script>

<template>
  <div class="explain q-card q-card--flush">
    <div class="q-card__head">
      <div class="q-card__title">口径与全链路追踪</div>
      <span class="q-subtle">逐项展开查看定义、查询与护栏轨迹</span>
    </div>
    <div class="explain__body">
      <el-collapse>
        <el-collapse-item v-if="definitions.length" name="def">
          <template #title>
            <span class="explain__label">指标口径</span>
            <span class="explain__count">{{ definitions.length }}</span>
          </template>
          <ul class="def-list">
            <li v-for="(d, i) in definitions" :key="i">{{ d }}</li>
          </ul>
        </el-collapse-item>
        <el-collapse-item v-if="sql" name="sql">
          <template #title>
            <span class="explain__label">生成的查询</span>
            <span class="explain__count">SQL</span>
          </template>
          <pre class="sql">{{ sql }}</pre>
        </el-collapse-item>
        <el-collapse-item name="trace">
          <template #title>
            <span class="explain__label">全链路追踪</span>
            <span class="explain__count">{{ steps.length }} 步</span>
          </template>
          <el-steps direction="vertical" :active="steps.length" finish-status="success">
            <el-step v-for="(s, i) in steps" :key="i" :title="s.stage" :description="s.detail" />
          </el-steps>
        </el-collapse-item>
        <el-collapse-item v-if="error" name="err">
          <template #title>
            <span class="explain__label">拦截原因</span>
            <span class="explain__count explain__count--danger">{{ error.gate }}</span>
          </template>
          <el-alert
            type="error"
            :closable="false"
            :title="error.code + ' · ' + error.gate"
            :description="error.reason + (error.hint ? '｜' + error.hint : '')"
          />
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<style scoped>
.explain__body {
  padding: 12px 16px 14px;
}
.explain__label {
  display: inline-flex;
  align-items: center;
  font-size: 12.5px;
}
.explain__count {
  margin-left: 10px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--q-brand-soft);
  color: var(--q-brand-deep);
  font-size: 10.5px;
  font-weight: 700;
}
.explain__count--danger {
  background: #fdeef0;
  color: #c93f56;
}
.def-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.def-list li {
  position: relative;
  padding: 8px 12px 8px 26px;
  border-radius: 8px;
  background: #f5f7ff;
  color: var(--q-ink-2);
  font-size: 12.5px;
  line-height: 1.6;
}
.def-list li::before {
  content: '';
  position: absolute;
  left: 12px;
  top: 15px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--q-brand);
}
.sql {
  margin: 0;
  padding: 14px 16px;
  border-radius: var(--q-radius-sm);
  background: #141c31;
  color: #d3dcfa;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-x: auto;
}
.explain :deep(.el-step__title),
.explain :deep(.el-step__description) {
  font-size: 12.5px;
}
</style>
