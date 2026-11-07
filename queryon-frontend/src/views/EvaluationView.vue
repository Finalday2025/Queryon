<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { EvaluationPerformance, EvaluationReport } from '../types'
import { api } from '../api/client'

const loading = ref(false)
const report = ref<EvaluationReport | null>(null)
const performance = ref<EvaluationPerformance | null>(null)
const textLoading = ref(false)
const text = ref('')

const stats = computed(() => {
  const r = report.value
  if (!r) return []
  return [
    { key: '样本总数', value: String(r.total), hint: '黄金评测集', tone: 'brand', bar: '' },
    { key: '通过', value: String(r.passed), hint: '断言全部满足', tone: 'ok', bar: '' },
    {
      key: '失败',
      value: String(r.failed),
      hint: '需修复后回归',
      tone: r.failed ? 'bad' : 'ok',
      bar: ''
    },
    {
      key: '解析通过率',
      value: (r.parsePassRate * 100).toFixed(1) + '%',
      hint: 'AST 与语义解析',
      tone: 'brand',
      bar: Math.min(100, Math.max(0, r.parsePassRate * 100)).toFixed(1) + '%'
    },
    {
      key: '执行准确率',
      value: (r.executionAccuracy * 100).toFixed(1) + '%',
      hint: '结果语义断言',
      tone: 'accent',
      bar: Math.min(100, Math.max(0, r.executionAccuracy * 100)).toFixed(1) + '%'
    },
    {
      key: '护栏拦截',
      value: String(r.guardInterceptions),
      hint: '写操作与越权',
      tone: 'accent',
      bar: ''
    }
  ]
})

async function loadPerformance() {
  try {
    const result = await api.evaluationPerformance()
    performance.value = result.available && result.performance ? result.performance : null
  } catch {
    performance.value = null
  }
}

async function run() {
  loading.value = true
  try {
    report.value = await api.runEvaluation()
  } finally {
    // 即使门禁未通过，也把本次运行的延迟观测取回；它不参与通过／失败判定。
    await loadPerformance()
    loading.value = false
  }
}

onMounted(loadPerformance)

async function showText() {
  textLoading.value = true
  try {
    text.value = await api.evaluationReport()
  } finally {
    textLoading.value = false
  }
}
</script>

<template>
  <div class="evaluation q-stack">
    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">评测控制台</div>
          <div class="q-subtle head__sub">按方言与场景分组，评测不过阻断发布</div>
        </div>
        <div class="q-toolbar">
          <el-button :loading="textLoading" @click="showText">可重复报告</el-button>
          <el-button type="primary" :loading="loading" @click="run">一键回归（24 条）</el-button>
        </div>
      </div>
      <div class="q-card__body">
        <div v-if="report" class="summary">
          <div v-for="s in stats" :key="s.key" class="stat" :class="'stat--' + s.tone">
            <div class="stat__key">{{ s.key }}</div>
            <div class="stat__value">{{ s.value }}</div>
            <div v-if="s.bar" class="stat__bar">
              <span :style="{ width: s.bar }" />
            </div>
            <div class="stat__hint">{{ s.hint }}</div>
          </div>
        </div>
        <div v-else class="summary-empty">
          <span class="summary-empty__mark">◎</span>
          <div class="summary-empty__title">运行回归以查看评测概览</div>
          <p class="q-subtle">24 条样本覆盖正常、口径冲突、歧义、多轮、越权、写操作诱导、字段幻觉与联合问答。</p>
        </div>
      </div>
    </section>

    <el-alert
      v-if="report"
      :type="report.failed > 0 ? 'error' : 'success'"
      :closable="false"
      show-icon
      :title="report.failed > 0 ? '存在失败样本，发布门禁未通过' : '全部通过，发布门禁通过'"
    />

    <section v-if="report" class="q-card groups">
      <div class="groups__row">
        <span class="groups__label">按方言</span>
        <el-tag v-for="(stat, key) in report.byDialect" :key="key" class="tag">
          {{ key }}（{{ stat.total }}/{{ stat.passed }}，{{ (stat.accuracy * 100).toFixed(0) }}%）
        </el-tag>
      </div>
      <div class="groups__row">
        <span class="groups__label">按场景</span>
        <el-tag v-for="(value, key) in report.byScene" :key="key" type="info" class="tag">
          {{ key }}: {{ value }}
        </el-tag>
      </div>
    </section>

    <section v-if="performance" class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">性能观测 · 延迟</div>
          <div class="q-subtle">本次运行观测，不进入可重复报告，也不参与发布门禁比较</div>
        </div>
        <span class="q-chip q-chip--neutral">D6-08 · 延迟</span>
      </div>
      <div class="q-card__body perf">
        <div class="perf__item">
          <span class="perf__key">平均耗时</span>
          <span class="perf__value">{{ performance.averageElapsedMs }} ms</span>
        </div>
        <div class="perf__item">
          <span class="perf__key">P95</span>
          <span class="perf__value">{{ performance.p95ElapsedMs }} ms</span>
        </div>
        <div class="perf__item">
          <span class="perf__key">最慢样本</span>
          <span class="perf__value">#{{ performance.slowestCase }} · {{ performance.slowestMs }} ms</span>
        </div>
        <div class="perf__item">
          <span class="perf__key">总耗时</span>
          <span class="perf__value">{{ performance.totalElapsedMs }} ms / {{ performance.totalCases }} 条</span>
        </div>
        <div class="perf__dialects">
          <span class="perf__key">按方言</span>
          <el-tag
            v-for="(value, key) in performance.averageMsByDialect"
            :key="key"
            size="small"
            type="info"
          >
            {{ key }} {{ value }} ms
          </el-tag>
        </div>
        <p class="q-subtle perf__note">
          {{ performance.note }}（成本状态：{{ performance.costStatus }}）
        </p>
      </div>
    </section>

    <section v-if="report" class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">样本明细</div>
        <span class="q-chip q-chip--neutral">{{ report.cases.length }} 条</span>
      </div>
      <div class="cases">
        <el-table :data="report.cases" border stripe>
          <el-table-column prop="id" label="#" width="62" />
          <el-table-column prop="question" label="问句" min-width="220" />
          <el-table-column prop="scene" label="场景" width="140" />
          <el-table-column prop="dialect" label="方言" width="90" />
          <el-table-column prop="status" label="状态" width="120" />
          <el-table-column label="通过" width="92">
            <template #default="{ row }">
              <el-tag :type="row.passed ? 'success' : 'danger'">{{ row.passed ? 'PASS' : 'FAIL' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="失败原因" min-width="180">
            <template #default="{ row }">{{ row.failures.join('；') }}</template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section v-if="text" class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">可重复报告</div>
        <span class="q-chip q-chip--neutral">纯文本</span>
      </div>
      <div class="q-card__body">
        <pre class="report-text">{{ text }}</pre>
      </div>
    </section>
  </div>
</template>

<style scoped>
.head__sub {
  margin-top: 3px;
  margin-left: 12px;
}
.summary {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}
.stat {
  position: relative;
  overflow: hidden;
  padding: 15px 16px 14px;
  border: 1px solid var(--q-line);
  border-radius: var(--q-radius-sm);
  background: linear-gradient(160deg, #fff, #fafbff);
}
.stat::before {
  content: '';
  position: absolute;
  inset: 0 auto 0 0;
  width: 3px;
  background: var(--q-brand);
}
.stat--ok::before {
  background: var(--el-color-success);
}
.stat--bad::before {
  background: var(--q-rose);
}
.stat--accent::before {
  background: var(--q-accent);
}
.stat__key {
  font-size: 11.5px;
  font-weight: 650;
  color: var(--q-muted);
}
.stat__value {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 750;
  letter-spacing: -0.03em;
  line-height: 1.15;
  color: var(--q-ink);
  font-variant-numeric: tabular-nums;
}
.stat--brand .stat__value {
  color: var(--q-brand-deep);
}
.stat--ok .stat__value {
  color: var(--el-color-success);
}
.stat--bad .stat__value {
  color: var(--q-rose);
}
.stat--accent .stat__value {
  color: #0e968a;
}
.stat__bar {
  height: 4px;
  margin-top: 9px;
  border-radius: 999px;
  background: #edf0f8;
  overflow: hidden;
}
.stat__bar span {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--q-brand), var(--q-accent));
  transition: width 0.6s var(--q-ease);
}
.stat__hint {
  margin-top: 8px;
  font-size: 10.5px;
  color: #a4afc6;
}
.summary-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 34px 16px;
  text-align: center;
}
.summary-empty__mark {
  font-size: 28px;
  line-height: 1;
  color: #c3cbf0;
}
.summary-empty__title {
  font-size: 14px;
  font-weight: 700;
  color: var(--q-ink-2);
}
.summary-empty .q-subtle {
  max-width: 560px;
}

.perf {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  align-items: center;
}
.perf__item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  border: 1px solid var(--q-line);
  border-radius: var(--q-radius-sm);
  background: linear-gradient(160deg, #fff, #fafbff);
}
.perf__key {
  font-size: 11.5px;
  font-weight: 650;
  color: var(--q-muted);
}
.perf__value {
  font-size: 18px;
  font-weight: 750;
  color: var(--q-ink);
  font-variant-numeric: tabular-nums;
}
.perf__dialects {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  grid-column: 1 / -1;
}
.perf__note {
  grid-column: 1 / -1;
  margin: 0;
}

.groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 20px;
}
.groups__row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.groups__label {
  min-width: 58px;
  font-size: 12px;
  font-weight: 700;
  color: var(--q-ink-2);
}
.tag {
  margin: 0;
}

.cases {
  padding: 16px 18px 18px;
}
.report-text {
  margin: 0;
  padding: 16px 18px;
  border-radius: var(--q-radius-sm);
  background: #141c31;
  color: #cdd8f5;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.75;
  white-space: pre-wrap;
  overflow-x: auto;
}

@media (max-width: 1200px) {
  .summary {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
