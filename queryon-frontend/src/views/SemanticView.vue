<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  MetricDefinition,
  MetricVersion,
  DimensionDefinition,
  SemanticColumn,
  SemanticTable,
  JoinPath,
  ActorAccount,
  MetadataChange
} from '../types'
import { api } from '../api/client'
import { useSession } from '../composables/useSession'

const tab = ref('metrics')
const metrics = ref<MetricDefinition[]>([])
const dimensions = ref<DimensionDefinition[]>([])
const tables = ref<SemanticTable[]>([])
const joins = ref<JoinPath[]>([])
const actors = ref<ActorAccount[]>([])
const { userId } = useSession()

const canManage = computed(() => userId.value === 'admin_bi')

/** 业务描述维护（D1-04）：表／字段描述可编辑、可回退，仅 admin_bi 可写。 */
const descVisible = ref(false)
const descSaving = ref(false)
const descTarget = ref<{ table: string; column: string } | null>(null)
const descForm = reactive({ businessName: '', description: '', synonyms: '', sensitive: false })

const changesVisible = ref(false)
const changes = ref<MetadataChange[]>([])

function openTableDescription(row: SemanticTable) {
  descTarget.value = { table: row.name, column: '' }
  Object.assign(descForm, {
    businessName: row.businessName,
    description: row.description,
    synonyms: '',
    sensitive: false
  })
  descVisible.value = true
}

function openColumnDescription(table: SemanticTable, column: SemanticColumn) {
  descTarget.value = { table: table.name, column: column.name }
  Object.assign(descForm, {
    businessName: column.businessName,
    description: column.description,
    synonyms: (column.synonyms ?? []).join('、'),
    sensitive: column.sensitive
  })
  descVisible.value = true
}

async function saveDescription() {
  const target = descTarget.value
  if (!target) return
  descSaving.value = true
  try {
    if (target.column) {
      await api.updateColumnDescription(target.table, target.column, {
        businessName: descForm.businessName,
        description: descForm.description,
        synonyms: descForm.synonyms.split(/[、,，\s]+/).filter(Boolean),
        sensitive: descForm.sensitive
      })
    } else {
      await api.updateTableDescription(target.table, {
        businessName: descForm.businessName,
        description: descForm.description
      })
    }
    descVisible.value = false
    tables.value = await api.tables()
    if (changesVisible.value) {
      await loadChanges()
    }
    ElMessage.success('描述已更新，问数检索与展示同步生效')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    descSaving.value = false
  }
}

async function loadChanges() {
  try {
    changes.value = await api.metadataChanges()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function openChanges() {
  await loadChanges()
  changesVisible.value = true
}

async function revertChange(change: MetadataChange) {
  const object = change.table + (change.column ? '.' + change.column : '')
  try {
    await ElMessageBox.confirm('回退后「' + object + '」将恢复为本次变更前的描述，确认回退？', '回退描述变更', {
      type: 'warning',
      confirmButtonText: '回退',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await api.revertMetadataChange(change.id)
    tables.value = await api.tables()
    await loadChanges()
    ElMessage.success('已回退')
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

function changeSummary(name: string, description: string) {
  return [name, description].filter(Boolean).join(' · ') || '—'
}

/** 指标版本化与审批（D2-06）：历史只追加，审批通过才替换当前口径，回滚按历史内容追加新版本。 */
const versionVisible = ref(false)
const versionMetric = ref<MetricDefinition | null>(null)
const versions = ref<MetricVersion[]>([])
const versionLoading = ref(false)
const versionBusy = ref('')
const compareVersion = ref<MetricVersion | null>(null)
const proposal = reactive({
  definition: '',
  expression: '',
  defaultFilter: '',
  unit: '',
  note: ''
})

const activeVersion = computed(() => versions.value.find((item) => item.active) ?? null)

const statusText: Record<string, string> = {
  DRAFT: '草稿',
  APPROVED: '生效',
  SUPERSEDED: '已被替代'
}

type TagType = 'success' | 'info' | 'warning' | 'danger'

function statusType(status: string): TagType {
  if (status === 'APPROVED') return 'success'
  if (status === 'DRAFT') return 'warning'
  return 'info'
}

const diffRows = computed(() => {
  const current = activeVersion.value
  const target = compareVersion.value
  if (!current || !target) return []
  return [
    { field: '业务名', current: current.businessName, target: target.businessName },
    { field: '唯一认证口径', current: current.definition, target: target.definition },
    { field: '公式／过滤', current: current.expression, target: target.expression },
    { field: '指标级默认过滤', current: current.defaultFilter, target: target.defaultFilter },
    { field: '单位', current: current.unit, target: target.unit }
  ].map((row) => ({ ...row, changed: row.current !== row.target }))
})

const changedCount = computed(() => diffRows.value.filter((row) => row.changed).length)

function diffRowClass({ row }: { row: { changed: boolean } }): string {
  return row.changed ? 'version__row--changed' : ''
}

function errorText(error: unknown): string {
  const value = error as { error?: { reason?: string; hint?: string } } | null
  const reason = value?.error?.reason
  const hint = value?.error?.hint
  if (!reason) return '操作失败，请稍后重试'
  return hint ? reason + '｜' + hint : reason
}

async function loadVersions(code: string) {
  versionLoading.value = true
  try {
    versions.value = await api.metricVersions(code)
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    versionLoading.value = false
  }
}

function resetProposal() {
  proposal.definition = ''
  proposal.expression = ''
  proposal.defaultFilter = ''
  proposal.unit = ''
  proposal.note = ''
}

async function openVersions(row: MetricDefinition) {
  versionMetric.value = row
  compareVersion.value = null
  versionVisible.value = true
  resetProposal()
  await loadVersions(row.code)
}

async function submitProposal() {
  const metric = versionMetric.value
  if (!metric) return
  if (
    !proposal.definition.trim() &&
    !proposal.expression.trim() &&
    !proposal.defaultFilter.trim() &&
    !proposal.unit.trim()
  ) {
    ElMessage.warning('至少填写一项口径变更（留空表示沿用当前值）')
    return
  }
  versionBusy.value = 'propose'
  try {
    await api.proposeMetricVersion(metric.code, {
      businessName: '',
      definition: proposal.definition.trim(),
      expression: proposal.expression.trim(),
      defaultFilter: proposal.defaultFilter.trim(),
      unit: proposal.unit.trim(),
      aliases: [],
      note: proposal.note.trim()
    })
    await loadVersions(metric.code)
    resetProposal()
    ElMessage.success('草稿已提交，审批通过后才会生效')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    versionBusy.value = ''
  }
}

async function approve(version: MetricVersion) {
  const metric = versionMetric.value
  if (!metric) return
  versionBusy.value = version.version
  try {
    await api.approveMetricVersion(metric.code, version.version)
    await loadVersions(metric.code)
    metrics.value = await api.metrics()
    ElMessage.success(version.version + ' 已审批生效，后续查询使用新口径')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    versionBusy.value = ''
  }
}

async function rollback(version: MetricVersion) {
  const metric = versionMetric.value
  if (!metric) return
  try {
    await ElMessageBox.confirm(
      '将以 ' + version.version + ' 的口径内容追加一个新版本并立即生效，历史记录保留。确认回滚？',
      '回滚口径',
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  versionBusy.value = version.version
  try {
    await api.rollbackMetricVersion(metric.code, version.version)
    await loadVersions(metric.code)
    metrics.value = await api.metrics()
    ElMessage.success('已回滚并生效')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    versionBusy.value = ''
  }
}

function closeVersions() {
  versionVisible.value = false
  compareVersion.value = null
}

onMounted(async () => {
  ;[metrics.value, dimensions.value, tables.value, joins.value, actors.value] = await Promise.all([
    api.metrics(),
    api.dimensions(),
    api.tables(),
    api.joins(),
    api.actors()
  ])
})
</script>

<template>
  <div class="semantic q-stack">
    <div class="semantic__banner q-card">
      <span class="semantic__mark">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M12 2.8 3 7.4l9 4.6 9-4.6-9-4.6Z M3 12.2l9 4.6 9-4.6 M3 16.8l9 4.6 9-4.6"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </span>
      <div class="semantic__banner-text">
        <div class="semantic__banner-title">语义层是唯一口径来源</div>
        <p class="q-subtle">AI 只基于受管指标与已批准关联路径生成查询，不直接面对物理表；以下目录由后端统一提供。</p>
      </div>
      <span class="q-chip">地基 · 唯一认证</span>
    </div>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">语义目录</div>
        <div class="semantic__head-tools">
          <span class="q-subtle">指标 {{ metrics.length }} · 维度 {{ dimensions.length }} · 表 {{ tables.length }} · 关联 {{ joins.length }}</span>
          <el-button size="small" text type="primary" @click="openChanges">描述变更台账</el-button>
        </div>
      </div>
      <div class="semantic__pane">
        <el-tabs v-model="tab">
          <el-tab-pane label="指标" name="metrics">
            <el-table :data="metrics" border stripe>
              <el-table-column prop="code" label="编码" width="160" />
              <el-table-column prop="businessName" label="业务名" width="140" />
              <el-table-column prop="definition" label="唯一认证口径" min-width="200" />
              <el-table-column prop="expression" label="公式" width="220" />
              <el-table-column prop="defaultFilter" label="默认过滤" width="160" />
              <el-table-column prop="unit" label="单位" width="80" />
              <el-table-column prop="version" label="版本" width="80" />
              <el-table-column label="别名" min-width="140">
                <template #default="{ row }">{{ row.aliases.join('、') }}</template>
              </el-table-column>
              <el-table-column label="操作" width="92" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text type="primary" @click="openVersions(row)">版本</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="维度" name="dimensions">
            <el-table :data="dimensions" border stripe>
              <el-table-column prop="code" label="编码" width="180" />
              <el-table-column prop="businessName" label="业务名" width="160" />
              <el-table-column prop="table" label="物理表" width="200" />
              <el-table-column prop="column" label="物理字段" width="200" />
              <el-table-column label="别名" min-width="140">
                <template #default="{ row }">{{ row.aliases.join('、') }}</template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="表" name="tables">
            <el-table :data="tables" border stripe>
              <el-table-column type="expand">
                <template #default="{ row }">
                  <div class="nested">
                    <el-table :data="row.columns" size="small">
                      <el-table-column prop="name" label="字段" width="200" />
                      <el-table-column prop="businessName" label="业务名" width="160" />
                      <el-table-column prop="description" label="说明" min-width="180" />
                      <el-table-column label="同义词" min-width="140">
                        <template #default="{ row: col }">{{ col.synonyms.join('、') || '—' }}</template>
                      </el-table-column>
                      <el-table-column label="敏感" width="100">
                        <template #default="{ row: col }">
                          <el-tag v-if="col.sensitive" type="danger">敏感</el-tag>
                          <span v-else>否</span>
                        </template>
                      </el-table-column>
                      <el-table-column label="操作" width="86">
                        <template #default="{ row: col }">
                          <el-button
                            size="small"
                            text
                            type="primary"
                            :disabled="!canManage"
                            @click="openColumnDescription(row, col)"
                          >
                            编辑
                          </el-button>
                        </template>
                      </el-table-column>
                    </el-table>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="name" label="物理表" width="220" />
              <el-table-column prop="businessName" label="业务名" width="200" />
              <el-table-column prop="description" label="说明" min-width="180" />
              <el-table-column label="操作" width="86">
                <template #default="{ row }">
                  <el-button
                    size="small"
                    text
                    type="primary"
                    :disabled="!canManage"
                    @click="openTableDescription(row)"
                  >
                    编辑
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="关联路径" name="joins">
            <el-table :data="joins" border stripe>
              <el-table-column prop="fromTable" label="左表" width="200" />
              <el-table-column prop="fromKey" label="左键" width="200" />
              <el-table-column prop="toTable" label="右表" width="200" />
              <el-table-column prop="toKey" label="右键" width="200" />
            </el-table>
          </el-tab-pane>

          <el-tab-pane label="内置账号" name="actors">
            <el-table :data="actors" border stripe>
              <el-table-column prop="userId" label="账号" width="200" />
              <el-table-column prop="role" label="角色" width="200" />
              <el-table-column prop="description" label="说明" min-width="180" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </section>

    <el-drawer v-model="versionVisible" size="720px" @close="closeVersions">
      <template #header>
        <div class="version__header">
          <div>
            <div class="version__title">
              口径版本 · {{ versionMetric?.businessName }}
              <span class="q-chip q-chip--neutral">{{ versionMetric?.code }}</span>
            </div>
            <div class="q-subtle">历史只追加；审批通过才生效，回滚按历史内容追加新版本</div>
          </div>
          <span v-if="activeVersion" class="q-chip">当前 {{ activeVersion.version }}</span>
        </div>
      </template>

      <div class="version" v-loading="versionLoading">
        <el-table :data="versions" size="small" border>
          <el-table-column prop="version" label="版本" width="76" />
          <el-table-column label="状态" width="104">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)">{{ statusText[row.status] ?? row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="变更说明" min-width="150" show-overflow-tooltip />
          <el-table-column prop="author" label="提交人" width="100" />
          <el-table-column prop="createdAt" label="时间" width="150" />
          <el-table-column label="操作" width="164" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="compareVersion = row">
                对比
              </el-button>
              <el-button
                v-if="row.status === 'DRAFT'"
                size="small"
                text
                type="success"
                :disabled="!canManage"
                :loading="versionBusy === row.version"
                @click="approve(row)"
              >
                审批
              </el-button>
              <el-button
                v-else-if="!row.active"
                size="small"
                text
                type="warning"
                :disabled="!canManage"
                :loading="versionBusy === row.version"
                @click="rollback(row)"
              >
                回滚
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-if="compareVersion" class="version__diff">
          <div class="version__diff-head">
            <span class="version__diff-title">
              字段级差异：当前 {{ activeVersion?.version }} → {{ compareVersion.version }}
            </span>
            <span class="q-chip q-chip--neutral">{{ changedCount }} 项变化</span>
          </div>
          <el-table :data="diffRows" size="small" border :row-class-name="diffRowClass">
            <el-table-column prop="field" label="字段" width="140" />
            <el-table-column prop="current" label="当前生效" min-width="180" />
            <el-table-column prop="target" label="该版本" min-width="180" />
          </el-table>
          <p v-if="!changedCount" class="q-subtle version__same">所选版本与当前生效口径一致。</p>
        </div>

        <div class="version__propose">
          <div class="version__propose-head">
            <span class="version__diff-title">提交新版本草稿</span>
            <span class="q-subtle">留空表示沿用当前值；仅 admin_bi 可提交与审批</span>
          </div>
          <el-form label-width="96px" size="small">
            <el-form-item label="认证口径">
              <el-input
                v-model="proposal.definition"
                type="textarea"
                :rows="2"
                :placeholder="activeVersion?.definition ?? ''"
              />
            </el-form-item>
            <el-form-item label="公式／过滤">
              <el-input v-model="proposal.expression" :placeholder="activeVersion?.expression ?? ''" />
            </el-form-item>
            <el-form-item label="默认过滤">
              <el-input v-model="proposal.defaultFilter" :placeholder="activeVersion?.defaultFilter ?? ''" />
            </el-form-item>
            <el-form-item label="单位">
              <el-input v-model="proposal.unit" :placeholder="activeVersion?.unit ?? ''" />
            </el-form-item>
            <el-form-item label="变更说明">
              <el-input v-model="proposal.note" placeholder="例如：明确不含运费，与财务口径对齐" />
            </el-form-item>
          </el-form>
          <div class="version__propose-actions">
            <el-button
              type="primary"
              size="small"
              :loading="versionBusy === 'propose'"
              :disabled="!canManage"
              @click="submitProposal"
            >
              提交草稿
            </el-button>
          </div>
        </div>
      </div>
    </el-drawer>

    <el-dialog
      v-model="descVisible"
      :title="descTarget?.column ? '编辑字段描述' : '编辑表描述'"
      width="560px"
      destroy-on-close
    >
      <el-form label-width="96px">
        <el-form-item label="对象">
          <span class="desc__object">
            {{ descTarget?.table }}<template v-if="descTarget?.column">.{{ descTarget.column }}</template>
          </span>
        </el-form-item>
        <el-form-item label="业务名">
          <el-input v-model="descForm.businessName" placeholder="中文业务名，供问数检索与展示" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="descForm.description" type="textarea" :rows="2" placeholder="口径或用途说明" />
        </el-form-item>
        <template v-if="descTarget?.column">
          <el-form-item label="同义词">
            <el-input v-model="descForm.synonyms" placeholder="用、或逗号分隔，如：区域、片区" />
          </el-form-item>
          <el-form-item label="敏感字段">
            <el-switch v-model="descForm.sensitive" />
            <span class="q-subtle desc__hint">开启后按列级权限对受限身份拒绝或掩码</span>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="descVisible = false">取消</el-button>
        <el-button type="primary" :loading="descSaving" :disabled="!canManage" @click="saveDescription">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="changesVisible" size="760px" title="描述变更台账">
      <p class="q-subtle desc__ledger-hint">
        记录每次表／字段描述变更的 before／after，可按条回退；回退同样写入审计。
      </p>
      <el-table :data="changes" size="small" border>
        <el-table-column label="对象" min-width="150">
          <template #default="{ row }">
            {{ row.table }}<template v-if="row.column">.{{ row.column }}</template>
          </template>
        </el-table-column>
        <el-table-column label="变更前" min-width="170">
          <template #default="{ row }">
            {{ changeSummary(row.beforeBusinessName, row.beforeDescription) }}
          </template>
        </el-table-column>
        <el-table-column label="变更后" min-width="170">
          <template #default="{ row }">
            {{ changeSummary(row.afterBusinessName, row.afterDescription) }}
          </template>
        </el-table-column>
        <el-table-column prop="author" label="操作人" width="110" />
        <el-table-column prop="createdAt" label="时间" width="160" />
        <el-table-column label="操作" width="86" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              text
              type="warning"
              :disabled="!canManage"
              @click="revertChange(row)"
            >
              回退
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!changes.length" class="q-subtle desc__empty">暂无描述变更记录。</p>
    </el-drawer>
  </div>
</template>

<style scoped>
.semantic__banner {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px 22px;
  background: linear-gradient(110deg, #ffffff 25%, #f4f6ff 100%);
  border-color: #e2e6ff;
}
.semantic__mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  flex: 0 0 42px;
  border-radius: 12px;
  background: linear-gradient(140deg, #6b78ff, #3b49d8);
  color: #fff;
  box-shadow: 0 10px 22px -12px rgba(79, 95, 245, 0.9);
}
.semantic__mark svg {
  width: 22px;
  height: 22px;
}
.semantic__banner-text {
  flex: 1;
  min-width: 0;
}
.semantic__banner-title {
  font-size: 14.5px;
  font-weight: 750;
  color: var(--q-ink);
}
.semantic__banner-text .q-subtle {
  margin-top: 3px;
}
.semantic__pane {
  padding: 2px 20px 18px;
}
.nested {
  padding: 6px 12px;
}

.version__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}
.version__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 700;
  color: var(--q-ink);
}
.version {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.version__diff,
.version__propose {
  padding: 14px 16px;
  border: 1px solid var(--q-line);
  border-radius: var(--q-radius-sm);
  background: #fbfcfe;
}
.version__diff-head,
.version__propose-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.version__diff-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--q-ink-2);
}
.version__same {
  margin-top: 8px;
}
.version__propose-actions {
  display: flex;
  justify-content: flex-end;
}
.version :deep(.version__row--changed td) {
  background: #fff8e8;
}

.semantic__head-tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.desc__object {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  color: var(--q-ink-2);
}
.desc__hint {
  margin-left: 12px;
}
.desc__ledger-hint {
  margin: 0 0 10px;
}
.desc__empty {
  margin-top: 10px;
}

@media (max-width: 760px) {
  .semantic__banner .q-chip {
    display: none;
  }
}
</style>
