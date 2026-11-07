<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  DimensionDefinition,
  JoinPath,
  LogicalDataset,
  LogicalDatasetInput,
  MetricDefinition,
  QueryResponse
} from '../types'
import { api } from '../api/client'
import { useDisplay } from '../composables/useDisplay'
import { useSession } from '../composables/useSession'
import { TIME_WINDOWS } from '../composables/useGlobalFilters'
import ChartView from '../components/ChartView.vue'
import ResultTable from '../components/ResultTable.vue'
import ExplanationPanel from '../components/ExplanationPanel.vue'

/**
 * 逻辑数据集（D1-05）：从语义层裁剪受管指标、维度与已批准关联路径，形成可授权的分析单元。
 * 数据集不保存 SQL、不暴露物理列；试跑统一走受治理查询入口，权限随当前身份。
 */
const { label } = useDisplay()
const { userId } = useSession()

const datasets = ref<LogicalDataset[]>([])
const metrics = ref<MetricDefinition[]>([])
const dimensions = ref<DimensionDefinition[]>([])
const joins = ref<JoinPath[]>([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref('')

const form = reactive({
  name: '',
  description: '',
  metrics: [] as string[],
  dimensions: [] as string[],
  joins: [] as string[],
  shared: true,
  defaultFilters: [] as { column: string; value: string }[]
})

const joinOptions = computed(() =>
  joins.value.map((item) => ({
    value: joinKey(item),
    label: item.fromTable + ' → ' + item.toTable
  }))
)

const activeDataset = ref<LogicalDataset | null>(null)
const runDimensions = ref<string[]>([])
const runTimeWindow = ref('LAST_MONTH')
const runLimit = ref(50)
const response = ref<QueryResponse | null>(null)
const running = ref(false)

const runDimensionOptions = computed(() => {
  const codes = activeDataset.value?.dimensions ?? []
  return codes.map((code) => ({ code, name: label(code) }))
})

const numericCols = computed<string[]>(() => {
  const rs = response.value?.resultSet
  if (!rs || !rs.rows.length) return []
  return rs.columns.filter((column) => rs.rows.every((row) => typeof row[column] === 'number'))
})

function isAdmin() {
  return userId.value === 'admin_bi'
}

function canEdit(row: LogicalDataset) {
  return isAdmin() || row.ownerId === userId.value
}

function joinKey(item: JoinPath) {
  return item.fromTable + '->' + item.toTable
}

function joinLabel(key: string) {
  return key.replace('->', ' → ')
}

function errorText(error: unknown): string {
  const value = error as { error?: { reason?: string; hint?: string } } | null
  const reason = value?.error?.reason
  const hint = value?.error?.hint
  if (!reason) return '操作失败，请稍后重试'
  return hint ? reason + '｜' + hint : reason
}

function filterSummary(row: LogicalDataset) {
  const entries = Object.entries(row.defaultFilters ?? {})
  if (!entries.length) return '—'
  return entries.map(([column, value]) => label(column) + '=' + value).join('，')
}

async function refresh() {
  loading.value = true
  try {
    datasets.value = await api.logicalDatasets()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    name: '',
    description: '',
    metrics: [],
    dimensions: [],
    joins: [],
    shared: true,
    defaultFilters: []
  })
}

function openCreate() {
  editingId.value = ''
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: LogicalDataset) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    description: row.description,
    metrics: [...row.metrics],
    dimensions: [...row.dimensions],
    joins: [...row.joins],
    shared: row.shared,
    defaultFilters: Object.entries(row.defaultFilters ?? {}).map(([column, value]) => ({
      column,
      value
    }))
  })
  dialogVisible.value = true
}

function addFilterRow() {
  form.defaultFilters.push({ column: '', value: '' })
}

function removeFilterRow(index: number) {
  form.defaultFilters.splice(index, 1)
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写数据集名称')
    return
  }
  if (!form.metrics.length) {
    ElMessage.warning('请至少选择一个受管指标')
    return
  }
  const defaultFilters: Record<string, string> = {}
  for (const row of form.defaultFilters) {
    if (row.column && row.value.trim()) {
      defaultFilters[row.column] = row.value.trim()
    }
  }
  const payload: LogicalDatasetInput = {
    name: form.name.trim(),
    description: form.description.trim(),
    metrics: form.metrics,
    dimensions: form.dimensions,
    joins: form.joins,
    shared: form.shared,
    defaultFilters
  }
  saving.value = true
  try {
    if (editingId.value) {
      await api.updateLogicalDataset(editingId.value, payload)
    } else {
      await api.createLogicalDataset(payload)
    }
    dialogVisible.value = false
    await refresh()
    ElMessage.success('已保存逻辑数据集')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}

async function remove(row: LogicalDataset) {
  try {
    await ElMessageBox.confirm('删除后引用该数据集的分析入口将不可用，确认删除「' + row.name + '」？', '删除数据集', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await api.deleteLogicalDataset(row.id)
    if (activeDataset.value?.id === row.id) {
      activeDataset.value = null
      response.value = null
    }
    await refresh()
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function openRun(row: LogicalDataset) {
  activeDataset.value = row
  runDimensions.value = row.dimensions.slice(0, 1)
  runTimeWindow.value = 'LAST_MONTH'
  runLimit.value = 50
  response.value = null
}

async function runDataset() {
  const dataset = activeDataset.value
  if (!dataset) return
  running.value = true
  try {
    response.value = await api.queryLogicalDataset(dataset.id, {
      metrics: dataset.metrics,
      dimensions: runDimensions.value,
      timeWindow: runTimeWindow.value,
      filters: {},
      limit: runLimit.value
    })
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    running.value = false
  }
}

onMounted(async () => {
  ;[metrics.value, dimensions.value, joins.value] = await Promise.all([
    api.metrics(),
    api.dimensions(),
    api.joins()
  ])
  await refresh()
})
</script>

<template>
  <div class="lds">
    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">逻辑数据集</div>
          <div class="q-subtle">
            从语义层裁剪受管指标、维度与已批准关联路径，形成可共享、可授权的分析单元
          </div>
        </div>
        <div class="lds__head-tools">
          <span class="q-chip q-chip--neutral">D1-05 · 逻辑数据集</span>
          <span class="q-chip">{{ datasets.length }} 个数据集</span>
        </div>
      </div>
      <div class="q-card__body">
        <div class="q-toolbar lds__toolbar">
          <el-button type="primary" size="small" @click="openCreate">新建数据集</el-button>
          <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
          <span class="q-subtle">数据集只引用受管对象；执行时仍按当前身份注入行列级权限</span>
        </div>

        <el-table v-loading="loading" :data="datasets" border size="small">
          <el-table-column label="数据集" min-width="200">
            <template #default="{ row }">
              <div class="lds__name">{{ row.name }}</div>
              <div class="lds__id">{{ row.id }} · {{ row.createdAt }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="180">
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="指标" min-width="170">
            <template #default="{ row }">
              <el-tag
                v-for="code in row.metrics"
                :key="code"
                size="small"
                effect="plain"
                class="lds__tag"
              >
                {{ label(code) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="维度" min-width="170">
            <template #default="{ row }">
              <el-tag
                v-for="code in row.dimensions"
                :key="code"
                size="small"
                type="info"
                effect="plain"
                class="lds__tag"
              >
                {{ label(code) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="关联路径" min-width="150">
            <template #default="{ row }">
              <span v-if="!row.joins.length" class="q-subtle">按维度自动补齐</span>
              <div v-for="join in row.joins" :key="join" class="lds__join">{{ joinLabel(join) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="默认过滤" min-width="160">
            <template #default="{ row }">{{ filterSummary(row) }}</template>
          </el-table-column>
          <el-table-column label="共享" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.shared ? 'success' : 'info'">
                {{ row.shared ? '共享' : '私有' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="ownerId" label="创建者" width="110" />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openRun(row)">试跑</el-button>
              <el-button size="small" text :disabled="!canEdit(row)" @click="openEdit(row)">编辑</el-button>
              <el-button
                size="small"
                text
                type="danger"
                :disabled="!canEdit(row)"
                @click="remove(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <section v-if="activeDataset" class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">试跑 · {{ activeDataset.name }}</div>
          <div class="q-subtle">
            可用维度仅限本数据集裁剪范围；默认过滤已由服务端注入，当前身份的行列级权限照常生效
          </div>
        </div>
        <el-button size="small" text @click="activeDataset = null">关闭</el-button>
      </div>
      <div class="q-card__body lds__run">
        <el-form label-width="80px" size="small" class="lds__run-form">
          <el-form-item label="维度">
            <el-select v-model="runDimensions" multiple placeholder="可不选（只看总计）">
              <el-option
                v-for="item in runDimensionOptions"
                :key="item.code"
                :value="item.code"
                :label="item.name"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="时间窗">
            <el-select v-model="runTimeWindow">
              <el-option v-for="w in TIME_WINDOWS" :key="w.value" :label="w.label" :value="w.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="行数上限">
            <el-input-number v-model="runLimit" :min="1" :max="500" controls-position="right" />
          </el-form-item>
        </el-form>
        <div class="lds__run-actions">
          <span class="q-subtle">
            指标：{{ activeDataset.metrics.map((code) => label(code)).join('、') }}
          </span>
          <el-button type="primary" size="small" :loading="running" @click="runDataset">
            按数据集取数
          </el-button>
        </div>

        <template v-if="response">
          <div class="lds__charts">
            <ChartView
              v-for="col in numericCols"
              :key="col"
              chart-type="bar"
              :result-set="response.resultSet"
              :measure="col"
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
        </template>
      </div>
    </section>

    <section class="q-card">
      <div class="q-card__title">使用约定</div>
      <ul class="lds__notes q-subtle">
        <li>数据集只保存受管指标、受管维度、已批准关联路径与默认过滤，不保存 SQL、不暴露物理表。</li>
        <li>共享数据集对其他内置账号可读，但执行时仍以提问者身份注入行级过滤与敏感列掩码。</li>
        <li>编辑权归创建者与 <code>admin_bi</code>；删除数据集不影响已保存的查询结果缓存。</li>
      </ul>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑逻辑数据集' : '新建逻辑数据集'"
      width="720px"
      destroy-on-close
    >
      <el-form label-width="96px">
        <el-form-item label="数据集名称">
          <el-input v-model="form.name" placeholder="例如：华东订单分析" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" placeholder="用途、口径范围与适用场景" />
        </el-form-item>
        <el-form-item label="指标">
          <el-select v-model="form.metrics" multiple filterable placeholder="选择受管指标">
            <el-option v-for="m in metrics" :key="m.code" :value="m.code" :label="m.businessName" />
          </el-select>
        </el-form-item>
        <el-form-item label="维度">
          <el-select v-model="form.dimensions" multiple filterable placeholder="选择受管维度（可留空）">
            <el-option v-for="d in dimensions" :key="d.code" :value="d.code" :label="d.businessName" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联路径">
          <el-select v-model="form.joins" multiple filterable placeholder="留空表示按维度自动补齐已批准路径">
            <el-option
              v-for="item in joinOptions"
              :key="item.value"
              :value="item.value"
              :label="item.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="默认过滤">
          <div class="lds__filters">
            <div v-for="(row, index) in form.defaultFilters" :key="index" class="lds__filter-row">
              <el-select v-model="row.column" size="small" placeholder="维度" class="lds__filter-dim">
                <el-option
                  v-for="code in form.dimensions"
                  :key="code"
                  :value="code"
                  :label="label(code)"
                />
              </el-select>
              <el-input v-model="row.value" size="small" placeholder="取值，如 华东" class="lds__filter-value" />
              <el-button size="small" text type="danger" @click="removeFilterRow(index)">移除</el-button>
            </div>
            <el-button size="small" text type="primary" @click="addFilterRow">添加条件</el-button>
          </div>
        </el-form-item>
        <el-form-item label="共享">
          <el-switch v-model="form.shared" />
          <span class="lds__form-hint q-subtle">共享后其他账号可读并用自己的权限取数</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.lds {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.lds__head-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lds__toolbar {
  margin-bottom: 12px;
}
.lds__name {
  font-size: 13px;
  font-weight: 650;
  color: var(--q-ink);
}
.lds__id {
  margin-top: 2px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10.5px;
  color: var(--q-muted);
}
.lds__tag {
  margin: 0 4px 4px 0;
}
.lds__join {
  font-size: 11.5px;
  color: var(--q-ink-2);
}
.lds__run {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.lds__run-form {
  max-width: 560px;
}
.lds__run-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.lds__charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 14px;
}
.lds__notes {
  margin: 10px 0 0;
  padding-left: 18px;
  line-height: 1.9;
}
.lds__notes code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f1f4fa;
  font-size: 11.5px;
}
.lds__filters {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.lds__filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.lds__filter-dim {
  width: 180px;
}
.lds__filter-value {
  width: 200px;
}
.lds__form-hint {
  margin-left: 12px;
}
</style>
