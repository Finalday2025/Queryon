<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import type { AuditEvent } from '../types'
import { api } from '../api/client'
import { useSession } from '../composables/useSession'

/** 基础审计台账（D7-05）：成功取数与护栏拦截均可检索，事件已脱敏。 */
const { userId } = useSession()
const outcome = ref('')
const events = ref<AuditEvent[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    events.value = await api.auditEvents(outcome.value, 100)
  } finally {
    loading.value = false
  }
}

function shortTrace(id: string): string {
  return id.length > 12 ? id.slice(0, 12) + '…' : id
}

watch([outcome, userId], load)
onMounted(load)
</script>

<template>
  <div class="audit q-stack">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="基础审计台账"
      description="覆盖一次问数全链路的成功、拦截、澄清与订阅执行记录；只写脱敏后的结构化事件，不含问句、prompt 与凭据。"
    />

    <section class="q-card q-card--flush">
      <div class="q-card__head">
        <div>
          <div class="q-card__title">审计事件</div>
          <div class="q-subtle">按结果筛选，身份切换后立即刷新</div>
        </div>
        <div class="audit__tools">
          <el-select v-model="outcome" size="small" clearable placeholder="全部结果" class="audit__select">
            <el-option label="成功" value="成功" />
            <el-option label="拦截" value="拦截" />
            <el-option label="澄清" value="澄清" />
            <el-option label="口径说明" value="口径说明" />
          </el-select>
          <el-button size="small" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
      <div class="q-card__body">
        <el-table v-if="events.length" :data="events" border stripe size="small" max-height="520">
          <el-table-column label="链路标识" width="150">
            <template #default="{ row }">{{ shortTrace(row.traceId) }}</template>
          </el-table-column>
          <el-table-column prop="userId" label="身份" width="140" />
          <el-table-column prop="role" label="角色" width="140" />
          <el-table-column prop="action" label="动作" width="150" />
          <el-table-column prop="outcome" label="结果" width="100" />
          <el-table-column prop="detail" label="结构化原因" />
        </el-table>
        <el-empty v-else description="暂无审计事件，先执行一次问数或查询" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.audit__tools {
  display: flex;
  gap: 8px;
  align-items: center;
}
.audit__select {
  width: 150px;
}
</style>
