<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import type { QueryResponse, ResultSet, DatasetSummary, Subscription, ScheduleInfo } from '../types'
import { api } from '../api/client'
import { useDisplay } from '../composables/useDisplay'
import { exportExcel, exportPdf } from '../utils/export'

const sql = ref(
  'SELECT sales_territory AS sales_territory, SUM(pay_amount) AS net_revenue FROM fact_order GROUP BY sales_territory'
)
const title = ref('销售大区净营收报表')
const loading = ref(false)
const response = ref<QueryResponse | null>(null)
const datasets = ref<DatasetSummary[]>([])
const datasetName = ref('')
const hint = ref('')
const reused = ref(false)
const lastPayload = ref<Record<string, unknown> | null>(null)
const { label, unit } = useDisplay()

// 表头统一用语义层中文业务名；数值列附单位，便于识别不同度量不可混算。
function header(c: string): string {
  const u = unit(c)
  return label(c) + (u ? '（' + u + '）' : '')
}

async function run() {
  if (!sql.value.trim()) return
  loading.value = true
  try {
    lastPayload.value = { sql: sql.value }
    reused.value = false
    hint.value = ''
    response.value = await api.query(lastPayload.value)
  } finally {
    loading.value = false
  }
}

function numericColumns(rs: ResultSet): string[] {
  if (rs.rows.length === 0) return []
  return rs.columns.filter((c) => rs.rows.every((r) => typeof r[c] === 'number'))
}

function columnTotal(rs: ResultSet, col: string): number {
  return rs.rows.reduce((s, r) => s + Number(r[col] ?? 0), 0)
}

// 每个数值列各自合计，不再把「元」与「单」等不同单位相加。
const totals = computed<(string | number)[]>(() => {
  if (!response.value) return []
  const rs = response.value.resultSet
  const nums = numericColumns(rs)
  return rs.columns.map((c, i) =>
    i === 0 ? '合计' : nums.includes(c) ? columnTotal(rs, c) : ''
  )
})

function toCsv(rs: ResultSet): string {
  const nums = numericColumns(rs)
  const lines = [rs.columns.map(header).join(',')]
  rs.rows.forEach((r) => {
    lines.push(
      rs.columns
        .map((c) => {
          const s = r[c] == null ? '' : String(r[c])
          return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
        })
        .join(',')
    )
  })
  lines.push(
    rs.columns
      .map((c, i) => (i === 0 ? '合计' : nums.includes(c) ? String(columnTotal(rs, c)) : ''))
      .join(',')
  )
  return lines.join('\n')
}

function exportCsv() {
  if (!response.value) return
  const blob = new Blob([toCsv(response.value.resultSet)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = (title.value || 'report') + '.csv'
  a.click()
  URL.revokeObjectURL(url)
}

function buildHtml(rs: ResultSet): string {
  const cols = rs.columns
  const nums = numericColumns(rs)
  let html = '<h2>' + (title.value || '报表') + '</h2>'
  html +=
    '<p>数据行数：' +
    rs.rows.length +
    (rs.truncated ? '（已截断）' : '') +
    '</p>'
  html += '<table border="1" cellpadding="6" cellspacing="0" style="border-collapse:collapse">'
  html += '<thead><tr>' + cols.map((c) => '<th>' + header(c) + '</th>').join('') + '</tr></thead><tbody>'
  rs.rows.forEach((r) => {
    html += '<tr>' + cols.map((c) => '<td>' + (r[c] ?? '') + '</td>').join('') + '</tr>'
  })
  html +=
    '<tr><td>合计</td>' +
    cols
      .slice(1)
      .map((c) => '<td>' + (nums.includes(c) ? columnTotal(rs, c) : '') + '</td>')
      .join('') +
    '</tr>'
  html += '</tbody></table>'
  html += '<p style="color:#888;font-size:12px">口径以 Queryon 语义层为准；权限随提问者身份注入。</p>'
  return html
}

function printReport() {
  if (!response.value) return
  const w = window.open('', '_blank')
  if (!w) return
  w.document.write(
    '<!DOCTYPE html><html><head><meta charset="utf-8"><title>' +
      (title.value || '报表') +
      '</title></head><body>' +
      buildHtml(response.value.resultSet) +
      '</body></html>'
  )
  w.document.close()
  w.focus()
  w.print()
}

async function refreshDatasets() {
  datasets.value = await api.listDatasets()
}

async function saveDataset() {
  if (!lastPayload.value || reused.value) {
    hint.value = '请先生成报表再保存数据集'
    return
  }
  await api.saveDataset(datasetName.value || '未命名数据集', lastPayload.value)
  datasetName.value = ''
  await refreshDatasets()
  hint.value = '已保存为数据集，可直接复用而无需重新查询'
}

async function loadDataset(id: string) {
  if (!id) return
  response.value = await api.dataset(id)
  reused.value = true
  hint.value = '当前报表复用已保存数据集的缓存结果，未重新查询'
}

function onDatasetChange(value: unknown) {
  const id = String(value ?? '')
  if (id) loadDataset(id)
}

const subscriptions = ref<Subscription[]>([])
const subName = ref('')
const subFrequency = ref('每月')

function exportXls() {
  if (!response.value) return
  exportExcel(
    response.value.resultSet,
    response.value.resultSet.columns.map(header),
    title.value || 'report'
  )
}

function exportPdfFile() {
  if (!response.value) return
  exportPdf(buildHtml(response.value.resultSet), title.value || '报表')
}

async function refreshSubscriptions() {
  subscriptions.value = await api.subscriptions()
}

async function saveSubscription() {
  if (!lastPayload.value) {
    hint.value = '请先生成报表再保存订阅'
    return
  }
  await api.saveSubscription(subName.value || '未命名订阅', subFrequency.value, lastPayload.value)
  subName.value = ''
  await refreshSubscriptions()
  hint.value = '订阅已保存，可在下方点击「周期执行」注册进程内调度'
}

async function runSubscription(id: string) {
  response.value = await api.runSubscription(id)
  hint.value = '订阅已立即执行，结果与报表同源'
  await refreshSubscriptions()
}

const shareToken = ref('')
const schedules = ref<Record<string, ScheduleInfo>>({})

async function createShare() {
  if (!lastPayload.value) {
    hint.value = '请先生成报表再创建分享链接'
    return
  }
  const shared = await api.createShare('报表分享', lastPayload.value)
  shareToken.value = shared.token
  hint.value = '分享链接已生成（需相同身份才能访问）：' + window.location.origin + '/shares/' + shared.token
}

async function refreshSchedules() {
  schedules.value = await api.activeSchedules()
}

async function schedule(id: string) {
  await api.scheduleSubscription(id, 60)
  await refreshSchedules()
  hint.value = '已注册 60 秒周期执行（进程内调度，重启后需重新注册）'
}

async function cancelSchedule(id: string) {
  await api.cancelSchedule(id)
  await refreshSchedules()
  hint.value = '已取消周期调度'
}

onMounted(() => {
  refreshDatasets()
  refreshSubscriptions()
  refreshSchedules()
})
</script>

<template>
  <div class="report q-stack">
    <el-alert
      class="report__notice"
      type="info"
      :closable="false"
      show-icon
      title="社区版简单报表"
      description="复用统一查询的同一结果集与语义层口径；中文列名、分列合计、CSV 导出与打印；可保存并复用数据集。"
    />

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">报表配置</div>
        <span class="q-chip q-chip--neutral">同一语义层</span>
      </div>
      <div class="q-card__body config">
        <div class="field field--title">
          <label>报表标题</label>
          <el-input v-model="title" placeholder="报表标题" />
        </div>
        <div class="field field--sql">
          <label>查询 SQL</label>
          <el-input v-model="sql" type="textarea" :rows="3" class="field__code" />
        </div>
        <el-button type="primary" :loading="loading" class="config__run" @click="run">
          生成报表
        </el-button>
      </div>
    </section>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">数据集复用</div>
        <span class="q-subtle">按当前身份隔离，复用缓存结果不触发重新查询</span>
      </div>
      <div class="q-card__body q-toolbar">
        <el-select
          placeholder="复用数据集（不重新查询）"
          size="small"
          clearable
          class="dataset-select"
          @change="onDatasetChange"
        >
          <el-option
            v-for="d in datasets"
            :key="d.id"
            :value="d.id"
            :label="d.name + '（' + d.rows + ' 行）'"
          />
        </el-select>
        <el-input v-model="datasetName" placeholder="数据集名称" size="small" class="dataset-name" />
        <el-button size="small" @click="saveDataset">保存为数据集</el-button>
      </div>
    </section>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">订阅</div>
          <div class="q-subtle">保存取数口径，按身份隔离；支持立即执行与进程内周期调度</div>
        </div>
      </div>
      <div class="q-card__body q-toolbar">
        <el-input v-model="subName" placeholder="订阅名称" size="small" class="dataset-name" />
        <el-select v-model="subFrequency" size="small" style="width: 120px">
          <el-option label="每月" value="每月" />
          <el-option label="每周" value="每周" />
          <el-option label="每日" value="每日" />
          <el-option label="手动" value="手动" />
        </el-select>
        <el-button size="small" @click="saveSubscription">保存订阅</el-button>
        <el-button size="small" @click="createShare">生成分享链接</el-button>
      </div>
      <div v-if="shareToken" class="q-card__body q-subtle">
        分享标识 {{ shareToken }}（访问需相同身份，权限不越界）
      </div>
      <div v-if="subscriptions.length" class="q-card__body">
        <el-table :data="subscriptions" size="small" border>
          <el-table-column prop="name" label="名称" />
          <el-table-column prop="frequency" label="频率" width="100" />
          <el-table-column prop="runCount" label="执行次数" width="100" />
          <el-table-column prop="lastOutcome" label="最近结果" />
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="runSubscription(row.id)">
                立即执行
              </el-button>
              <el-button v-if="!schedules[row.id]" size="small" text type="primary" @click="schedule(row.id)">
                周期执行
              </el-button>
              <el-button v-else size="small" text type="warning" @click="cancelSchedule(row.id)">
                取消定时
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <el-alert v-if="hint" type="success" :closable="false" show-icon :title="hint" />

    <section v-if="response" class="q-card q-card--flush sheet">
      <div class="q-card__head sheet__head">
        <div>
          <div class="sheet__title">{{ title || '报表' }}</div>
          <div class="q-subtle sheet__meta">
            共 {{ response.resultSet.rows.length }} 行
            <template v-if="response.resultSet.truncated">（已截断）</template>
            · 口径以语义层为准
          </div>
        </div>
        <div class="q-toolbar">
          <el-button size="small" @click="exportCsv">导出 CSV</el-button>
          <el-button size="small" @click="exportXls">导出 Excel</el-button>
          <el-button size="small" @click="exportPdfFile">导出 PDF</el-button>
          <el-button size="small" @click="printReport">打印</el-button>
        </div>
      </div>
      <div class="sheet__scroll">
        <table class="report-table">
          <thead>
            <tr>
              <th v-for="c in response.resultSet.columns" :key="c">{{ header(c) }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, i) in response.resultSet.rows" :key="i">
              <td v-for="c in response.resultSet.columns" :key="c">{{ row[c] ?? '' }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr>
              <td v-for="(t, i) in totals" :key="i">{{ t }}</td>
            </tr>
          </tfoot>
        </table>
      </div>
    </section>

    <div v-else class="sheet-empty q-card">
      <span class="sheet-empty__mark">▤</span>
      <div class="sheet-empty__title">等待生成报表</div>
      <p class="q-subtle">配置查询后点击「生成报表」，中文列名与分列合计会按语义层口径自动呈现。</p>
    </div>
  </div>
</template>

<style scoped>
.report__notice :deep(.el-alert__icon) {
  font-size: 17px;
}
.config {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) auto;
  gap: 16px;
  align-items: end;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-width: 0;
}
.field label {
  font-size: 12px;
  font-weight: 650;
  color: var(--q-ink-2);
}
.field__code :deep(.el-textarea__inner) {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12.5px;
  line-height: 1.7;
  color: #2c3c66;
}
.config__run {
  height: 40px;
  padding-left: 22px;
  padding-right: 22px;
}
.dataset-select {
  width: 250px;
}
.dataset-name {
  width: 190px;
}

.sheet__title {
  font-size: 15px;
  font-weight: 700;
  color: var(--q-ink);
}
.sheet__meta {
  margin-top: 3px;
}
.sheet__scroll {
  overflow-x: auto;
  padding: 4px 0 6px;
}
.report-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.report-table th,
.report-table td {
  padding: 11px 18px;
  text-align: left;
  white-space: nowrap;
  border-bottom: 1px solid var(--q-line-soft);
}
.report-table thead th {
  position: sticky;
  top: 0;
  background: #f7f9fe;
  color: var(--q-ink-2);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  border-bottom: 1px solid var(--q-line);
}
.report-table thead th:first-child {
  border-top-left-radius: 0;
  padding-left: 22px;
}
.report-table thead th:last-child {
  padding-right: 22px;
}
.report-table tbody tr:nth-child(even) {
  background: #fafbfe;
}
.report-table tbody tr:hover {
  background: #f2f5ff;
}
.report-table tbody td:first-child {
  padding-left: 22px;
  font-weight: 600;
  color: var(--q-ink);
}
.report-table tbody td:last-child {
  padding-right: 22px;
}
.report-table tfoot td {
  padding-top: 13px;
  padding-bottom: 13px;
  border-top: 1px solid #dfe4f8;
  border-bottom: none;
  background: linear-gradient(90deg, #f4f6ff, #f9faff);
  color: var(--q-brand-deep);
  font-weight: 750;
}
.report-table tfoot td:first-child {
  padding-left: 22px;
}
.report-table tfoot td:last-child {
  padding-right: 22px;
}

.sheet-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 24px;
  text-align: center;
  border-style: dashed;
  background: rgba(255, 255, 255, 0.6);
  box-shadow: none;
}
.sheet-empty__mark {
  font-size: 30px;
  line-height: 1;
  color: #c3cbf0;
}
.sheet-empty__title {
  font-size: 14px;
  font-weight: 700;
  color: var(--q-ink-2);
}
.sheet-empty .q-subtle {
  max-width: 440px;
}

@media (max-width: 900px) {
  .config {
    grid-template-columns: 1fr;
    align-items: stretch;
  }
  .dataset-select,
  .dataset-name {
    width: 100%;
  }
}
</style>
