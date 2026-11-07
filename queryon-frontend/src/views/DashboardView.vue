<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import type { Dashboard, DashboardCard, DataSourceInfo, CacheSnapshot } from '../types'
import { api } from '../api/client'
import { useDisplay } from '../composables/useDisplay'
import { useSession } from '../composables/useSession'
import { timeWindowLabel, useGlobalFilters } from '../composables/useGlobalFilters'
import ChartView from '../components/ChartView.vue'
import ResultTable from '../components/ResultTable.vue'

/**
 * 仪表板（D3-01～D3-04、D3-06）：所有卡片由 /api/dashboard 经统一查询入口生成，
 * 与问数、SQL 编辑器、开放 API 的同一指标口径一致（02 第 6.3 节判据 2）。
 */
const { userId } = useSession()
const { label, unit } = useDisplay()
// 时间窗取自跨页面全局筛选器；卡片口径由服务端固定，维度条件在查询类页面生效。
const { timeWindow, activeCount } = useGlobalFilters()
const dashboard = ref<Dashboard | null>(null)
const sources = ref<DataSourceInfo[]>([])
const cache = ref<CacheSnapshot | null>(null)
const loading = ref(false)
const hint = ref('')

async function load() {
  loading.value = true
  try {
    dashboard.value = await api.dashboard(timeWindow.value)
  } finally {
    loading.value = false
  }
}

async function refreshSources() {
  sources.value = await api.datasources()
}

async function refreshCache() {
  cache.value = await api.cacheStats()
}

async function syncMetadata() {
  const result = await api.syncMetadata()
  hint.value =
    '元数据已同步：' + String(result.tables ?? '') + ' 张表 / ' + String(result.columns ?? '') + ' 个字段'
  await refreshSources()
}

function metricCard(card: DashboardCard) {
  const rs = card.resultSet
  const col = rs.columns.find((c) => rs.rows.some((r) => typeof r[c] === 'number'))
  if (!col || !rs.rows.length) return null
  return {
    value: new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(rs.rows[0][col])),
    name: label(col),
    unit: unit(col)
  }
}

watch([timeWindow, userId], load)
onMounted(() => {
  load()
  refreshSources()
  refreshCache()
})
</script>

<template>
  <div class="dashboard q-stack">
    <section class="q-card q-card--flush">
      <div class="q-card__head">
      <div>
        <div class="q-card__title">全局范围</div>
        <div class="q-subtle">切换全局时间窗或身份，所有卡片按同一语义层与权限重新取数</div>
      </div>
      <div class="dashboard__tools">
        <span class="q-chip q-chip--neutral">时间窗：{{ timeWindowLabel(timeWindow) }}</span>
        <span v-if="activeCount" class="q-chip q-chip--neutral">
          {{ activeCount }} 个维度条件 · 查询类页面生效
        </span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
      </div>
    </section>

    <el-alert v-if="hint" type="success" :closable="false" show-icon :title="hint" />

    <div v-if="dashboard" class="dashboard__grid">
      <section v-for="card in dashboard.cards" :key="card.id" class="q-card q-card--flush">
        <div class="q-card__head">
          <div class="q-card__title">{{ card.title }}</div>
          <span class="q-chip q-chip--neutral">{{ card.metricDefinitions.length }} 个认证口径</span>
        </div>
        <div class="q-card__body dashboard__body">
          <div v-if="card.chartType === 'metric' && metricCard(card)" class="metric-card">
            <div class="metric-card__value">
              {{ metricCard(card)?.value }}<small>{{ metricCard(card)?.unit }}</small>
            </div>
            <div class="metric-card__label">{{ metricCard(card)?.name }}</div>
          </div>
          <template v-else>
            <ChartView :chart-type="card.chartType" :result-set="card.resultSet" :title="card.title" />
            <ResultTable :result-set="card.resultSet" />
          </template>
          <div class="dashboard__trace">
            traceId {{ card.traceId }} · 卡片由统一查询入口生成，口径与问数／SQL 编辑器一致
          </div>
        </div>
      </section>
    </div>
    <div v-else class="placeholder q-card">
      <div class="placeholder__title">正在加载仪表板</div>
    </div>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">数据源与元数据</div>
          <div class="q-subtle">口径信息来自语义层注册表；dev 模式下行数直查 PostgreSQL，默认模式回退内存示例数据集</div>
        </div>
        <el-button size="small" @click="syncMetadata">重新同步元数据</el-button>
      </div>
      <div class="q-card__body">
        <el-table :data="sources" border size="small">
          <el-table-column prop="name" label="数据源" />
          <el-table-column prop="type" label="类型" />
          <el-table-column prop="tables" label="受管表" />
          <el-table-column prop="columns" label="字段" />
          <el-table-column prop="rows" label="示例行数" />
          <el-table-column prop="metrics" label="指标" />
          <el-table-column prop="dimensions" label="维度" />
        </el-table>
      </div>
    </section>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">查询结果缓存</div>
          <div class="q-subtle">Redis 命中率与降级次数，dev 模式下生效</div>
        </div>
        <el-button size="small" @click="refreshCache">刷新</el-button>
      </div>
      <div v-if="cache" class="q-card__body">
        <div class="cache-grid">
          <div><span>状态</span><b>{{ cache.enabled ? '已启用' : '未启用（内存数据源）' }}</b></div>
          <div><span>命中率</span><b>{{ (cache.hitRate * 100).toFixed(1) }}%</b></div>
          <div><span>命中 / 未命中</span><b>{{ cache.hits }} / {{ cache.misses }}</b></div>
          <div><span>写入 / 降级</span><b>{{ cache.writes }} / {{ cache.fallbacks }}</b></div>
          <div><span>TTL</span><b>{{ cache.ttlSeconds }}s</b></div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.dashboard__tools {
  display: flex;
  gap: 8px;
  align-items: center;
}
.dashboard__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
  gap: 14px;
}
.dashboard__body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.metric-card {
  padding: 18px 6px;
  text-align: center;
}
.metric-card__value {
  font-size: 30px;
  font-weight: 700;
  color: var(--q-primary, #5a68f8);
}
.metric-card__value small {
  font-size: 14px;
  margin-left: 4px;
}
.metric-card__label {
  margin-top: 6px;
  font-size: 13px;
  color: var(--q-text-soft, #8491ad);
}
.dashboard__trace {
  font-size: 11px;
  color: var(--q-text-soft, #8491ad);
}
.cache-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}
.cache-grid div {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.cache-grid span {
  font-size: 12px;
  color: var(--q-text-soft, #8491ad);
}
.cache-grid b {
  font-size: 16px;
}
</style>
