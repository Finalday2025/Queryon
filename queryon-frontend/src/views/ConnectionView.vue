<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type {
  ConnectionInfo,
  ConnectionInput,
  MetadataCapture,
  MetadataDiff
} from '../types'
import { api } from '../api/client'
import { useSession } from '../composables/useSession'

/**
 * 连接管理（D1-01、D1-02 社区版部分）：
 * 连接参数可配置、连通性可测试、启停可控；凭据只以密文保存，接口不回传明文。
 * 写操作由服务端校验全量基准身份，前端仅做可见性提示，不作为权限边界。
 */
const { userId } = useSession()

const connections = ref<ConnectionInfo[]>([])
const loading = ref(false)
const saving = ref(false)
const testingId = ref('')
const hint = ref('')

const canManage = computed(() => userId.value === 'admin_bi')

const snapshots = ref<MetadataCapture[]>([])
const snapshotLoading = ref(false)

function hasChange(diff: MetadataDiff) {
  return (
    diff.addedTables.length > 0 ||
    diff.removedTables.length > 0 ||
    diff.addedColumns.length > 0 ||
    diff.removedColumns.length > 0 ||
    diff.rowChanges.length > 0
  )
}

function diffText(diff: MetadataDiff) {
  if (diff.firstSnapshot) return '首份快照，作为后续比较基线'
  const parts: string[] = []
  if (diff.addedTables.length) parts.push('新增表：' + diff.addedTables.join('、'))
  if (diff.removedTables.length) parts.push('移除表：' + diff.removedTables.join('、'))
  if (diff.addedColumns.length) parts.push('新增字段 ' + diff.addedColumns.length + ' 个')
  if (diff.removedColumns.length) parts.push('移除字段 ' + diff.removedColumns.length + ' 个')
  if (diff.rowChanges.length) {
    parts.push(
      '行数变化：' +
        diff.rowChanges.map((item) => item.table + ' ' + item.before + '→' + item.after).join('，')
    )
  }
  return parts.length ? parts.join('；') : '与上一份快照一致'
}

const typeLabels: Record<string, string> = {
  POSTGRESQL: 'PostgreSQL',
  MYSQL: 'MySQL',
  CLICKHOUSE: 'ClickHouse',
  CSV: 'CSV 文件',
  API: 'API 数据源',
  IN_MEMORY_SAMPLE: '内置示例'
}
const createTypes = Object.entries(typeLabels)
  .filter(([value]) => value !== 'IN_MEMORY_SAMPLE')
  .map(([value, label]) => ({ value, label }))

const statusText: Record<string, string> = {
  READY: '可用',
  UNREACHABLE: '不可达',
  UNTESTED: '未测试'
}

type TagType = 'success' | 'info' | 'warning' | 'danger'

function statusType(row: ConnectionInfo): TagType {
  if (!row.enabled) return 'info'
  if (row.status === 'READY') return 'success'
  if (row.status === 'UNREACHABLE') return 'danger'
  return 'warning'
}

function statusLabel(row: ConnectionInfo): string {
  return row.enabled ? statusText[row.status] ?? row.status : '已停用'
}

function address(row: ConnectionInfo): string {
  if (!row.host) return '—'
  return row.port ? row.host + ':' + row.port : row.host
}

const summary = computed(() => {
  const total = connections.value.length
  const ready = connections.value.filter((item) => item.enabled && item.status === 'READY').length
  const unreachable = connections.value.filter(
    (item) => item.enabled && item.status === 'UNREACHABLE'
  ).length
  const disabled = connections.value.filter((item) => !item.enabled).length
  return { total, ready, unreachable, disabled }
})

const dialogVisible = ref(false)
const editingId = ref('')
const form = reactive({
  name: '',
  type: 'POSTGRESQL',
  host: 'localhost',
  port: undefined as number | undefined,
  database: '',
  username: '',
  password: '',
  readOnly: true,
  enabled: true
})

function errorText(error: unknown): string {
  const value = error as { error?: { reason?: string; hint?: string } } | null
  const reason = value?.error?.reason
  const hintText = value?.error?.hint
  if (!reason) return '操作失败，请稍后重试'
  return hintText ? reason + '｜' + hintText : reason
}

async function refresh() {
  loading.value = true
  try {
    connections.value = await api.connections()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = ''
  Object.assign(form, {
    name: '',
    type: 'POSTGRESQL',
    host: 'localhost',
    port: undefined,
    database: '',
    username: '',
    password: '',
    readOnly: true,
    enabled: true
  })
  dialogVisible.value = true
}

function openEdit(row: ConnectionInfo) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    type: row.type,
    host: row.host,
    port: row.port ?? undefined,
    database: row.database,
    username: row.username,
    password: '',
    readOnly: row.readOnly,
    enabled: row.enabled
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写连接名称')
    return
  }
  if (!form.type) {
    ElMessage.warning('请选择连接类型')
    return
  }
  saving.value = true
  try {
    const payload: ConnectionInput = {
      name: form.name.trim(),
      type: form.type,
      host: form.host.trim(),
      port: form.port ?? null,
      database: form.database.trim(),
      username: form.username.trim(),
      password: form.password,
      readOnly: form.readOnly,
      enabled: form.enabled
    }
    if (editingId.value) {
      await api.updateConnection(editingId.value, payload)
      hint.value = '连接已更新；如修改了主机或端口，请重新测试连通性'
    } else {
      await api.createConnection(payload)
      hint.value = '连接已创建；建议先执行一次测试连接再启用'
    }
    dialogVisible.value = false
    await refresh()
    ElMessage.success('已保存')
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    saving.value = false
  }
}

async function test(row: ConnectionInfo) {
  testingId.value = row.id
  try {
    const result = await api.testConnection(row.id)
    if (result.status === 'READY') {
      ElMessage.success('连接可达 · ' + result.latencyMs + 'ms')
    } else {
      ElMessage.warning(result.statusDetail || '连接不可达')
    }
    await refresh()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    testingId.value = ''
  }
}

async function toggle(row: ConnectionInfo, value: unknown) {
  try {
    await api.toggleConnection(row.id, Boolean(value))
    await refresh()
  } catch (error) {
    ElMessage.error(errorText(error))
    await refresh()
  }
}

async function remove(row: ConnectionInfo) {
  try {
    await ElMessageBox.confirm(
      '删除后该连接的配置与密文凭据将一并移除，且不可恢复。确认删除「' + row.name + '」？',
      '删除连接',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await api.deleteConnection(row.id)
    await refresh()
    ElMessage.success('已删除')
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

async function loadSnapshots() {
  snapshotLoading.value = true
  try {
    snapshots.value = await api.metadataSnapshots()
  } catch (error) {
    ElMessage.error(errorText(error))
  } finally {
    snapshotLoading.value = false
  }
}

async function syncMetadata() {
  try {
    const result = await api.syncMetadata()
    const diff = result.diff as MetadataDiff | undefined
    hint.value =
      '元数据已同步：' +
      String(result.tables ?? '') +
      ' 张表 / ' +
      String(result.columns ?? '') +
      ' 个字段；' +
      (diff ? diffText(diff) : '未返回差异')
    await loadSnapshots()
  } catch (error) {
    ElMessage.error(errorText(error))
  }
}

onMounted(() => {
  refresh()
  loadSnapshots()
})
</script>

<template>
  <div class="conn q-stack">
    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">数据源连接</div>
          <div class="q-subtle">连接参数可配置、连通性可测试、启停可控；凭据不回传、不落明文</div>
        </div>
        <div class="conn__head-tools">
          <span class="q-chip q-chip--neutral">D1-01 · D1-02</span>
          <span class="q-chip">可用 {{ summary.ready }} / {{ summary.total }}</span>
        </div>
      </div>
      <div class="q-card__body">
        <div class="q-toolbar conn__toolbar">
          <el-button type="primary" size="small" :disabled="!canManage" @click="openCreate">
            新增连接
          </el-button>
          <el-button size="small" :loading="loading" @click="refresh">刷新</el-button>
          <el-button size="small" @click="syncMetadata">重新同步元数据</el-button>
          <span class="q-divider" />
          <span class="q-subtle">
            不可达 {{ summary.unreachable }} · 已停用 {{ summary.disabled }} · 只读账号优先
          </span>
          <span v-if="!canManage" class="q-chip q-chip--neutral conn__readonly">
            当前身份只读，维护连接请切换到 admin_bi
          </span>
        </div>

        <el-table v-loading="loading" :data="connections" border size="small" class="conn__table">
          <el-table-column label="连接名称" min-width="190">
            <template #default="{ row }">
              <div class="conn__name">{{ row.name }}</div>
              <div class="conn__id">{{ row.id }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ typeLabels[row.type] ?? row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="地址" min-width="170">
            <template #default="{ row }">{{ address(row) }}</template>
          </el-table-column>
          <el-table-column prop="database" label="数据库" min-width="120">
            <template #default="{ row }">{{ row.database || '—' }}</template>
          </el-table-column>
          <el-table-column prop="username" label="账号" min-width="120">
            <template #default="{ row }">
              {{ row.username || '—' }}
              <el-tag v-if="row.hasCredential" size="small" type="info" effect="plain">已加密</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="只读" width="76">
            <template #default="{ row }">
              <el-tag v-if="row.readOnly" size="small" type="success" effect="plain">只读</el-tag>
              <span v-else class="q-subtle">可写</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tooltip :content="row.statusDetail || '尚未测试'" placement="top">
                <el-tag size="small" :type="statusType(row)">{{ statusLabel(row) }}</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="86">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                size="small"
                :disabled="!canManage"
                @update:model-value="toggle(row, $event)"
              />
            </template>
          </el-table-column>
          <el-table-column label="最近测试" width="180">
            <template #default="{ row }">
              <div class="conn__tested">{{ row.lastTestedAt || '—' }}</div>
              <div v-if="row.lastTestedAt" class="conn__latency">{{ row.latencyMs }}ms</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="196" fixed="right">
            <template #default="{ row }">
              <el-button
                size="small"
                text
                type="primary"
                :loading="testingId === row.id"
                @click="test(row)"
              >
                测试连接
              </el-button>
              <el-button
                size="small"
                text
                :disabled="!canManage || row.id === 'conn-sample'"
                @click="openEdit(row)"
              >
                编辑
              </el-button>
              <el-button
                size="small"
                text
                type="danger"
                :disabled="!canManage || row.id === 'conn-sample'"
                @click="remove(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-alert v-if="hint" class="conn__hint" type="info" :closable="false" show-icon :title="hint" />
      </div>
    </section>

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">元数据快照</div>
          <div class="q-subtle">每次采集与上一份比较：新增／移除的表与字段、行数变化，保留最近 20 份</div>
        </div>
        <div class="conn__head-tools">
          <span class="q-chip q-chip--neutral">D1-03 · 快照差异</span>
          <el-button size="small" @click="syncMetadata">采集快照</el-button>
        </div>
      </div>
      <div class="q-card__body">
        <el-table v-loading="snapshotLoading" :data="snapshots" size="small" border>
          <el-table-column prop="snapshot.id" label="快照" width="110" />
          <el-table-column prop="snapshot.capturedAt" label="采集时间" width="180" />
          <el-table-column label="表数" width="80">
            <template #default="{ row }">{{ row.snapshot.tables.length }}</template>
          </el-table-column>
          <el-table-column label="差异" min-width="320">
            <template #default="{ row }">
              <el-tag size="small" :type="hasChange(row.diff) ? 'warning' : 'info'">
                {{ hasChange(row.diff) ? '有变更' : '无变更' }}
              </el-tag>
              <span class="conn__diff">{{ diffText(row.diff) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="!snapshots.length" class="q-subtle conn__empty">
          尚未采集快照；点击「采集快照」生成首份基线。
        </p>
      </div>
    </section>

    <section class="q-card">
      <div class="q-card__title">凭据与安全约定</div>
      <ul class="conn__notes q-subtle">
        <li>密码只以进程内 AES-GCM 密文保存，任何接口都不回传明文；编辑时留空表示沿用原凭据。</li>
        <li>连通性测试只探测主机端口可达性，不校验口令、不执行任何业务 SQL。</li>
        <li>新增、编辑、启停、删除仅限 <code>admin_bi</code>；权限不足返回结构化错误并写审计。</li>
        <li>建议使用只读账号并显式开启「只读」标记，避免把分析账号配成可写身份。</li>
      </ul>
    </section>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑连接' : '新增连接'"
      width="560px"
      destroy-on-close
    >
      <el-form label-width="88px">
        <el-form-item label="连接名称">
          <el-input v-model="form.name" placeholder="例如：生产数仓（只读）" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" :disabled="Boolean(editingId)">
            <el-option
              v-for="t in createTypes"
              :key="t.value"
              :value="t.value"
              :label="t.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="主机">
          <el-input v-model="form.host" placeholder="localhost 或 IP" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number
            v-model="form.port"
            :min="1"
            :max="65535"
            controls-position="right"
            placeholder="5432"
          />
        </el-form-item>
        <el-form-item label="数据库">
          <el-input v-model="form.database" placeholder="数据库／Schema 名称" />
        </el-form-item>
        <el-form-item label="账号">
          <el-input v-model="form.username" placeholder="建议使用只读账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="editingId ? '留空表示不修改已保存凭据' : '仅保存密文，不回传'"
          />
        </el-form-item>
        <el-form-item label="只读账号">
          <el-switch v-model="form.readOnly" />
          <span class="conn__form-hint q-subtle">建议保持开启，禁止写操作</span>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canManage" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.conn__head-tools {
  display: flex;
  align-items: center;
  gap: 8px;
}
.conn__toolbar {
  margin-bottom: 12px;
}
.conn__readonly {
  margin-left: auto;
}
.conn__name {
  font-size: 13px;
  font-weight: 650;
  color: var(--q-ink);
}
.conn__id {
  margin-top: 2px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 10.5px;
  color: var(--q-muted);
}
.conn__tested {
  font-size: 11.5px;
  color: var(--q-ink-2);
}
.conn__latency {
  font-size: 10.5px;
  color: var(--q-muted);
}
.conn__hint {
  margin-top: 12px;
}
.conn__diff {
  margin-left: 8px;
  font-size: 11.5px;
  color: var(--q-ink-2);
}
.conn__empty {
  margin-top: 10px;
}
.conn__notes {
  margin: 10px 0 0;
  padding-left: 18px;
  line-height: 1.9;
}
.conn__notes code {
  padding: 1px 5px;
  border-radius: 4px;
  background: #f1f4fa;
  font-size: 11.5px;
}
.conn__form-hint {
  margin-left: 12px;
}
@media (max-width: 860px) {
  .conn__readonly {
    margin-left: 0;
  }
}
</style>
