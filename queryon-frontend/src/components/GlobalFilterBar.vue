<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { DimensionDefinition } from '../types'
import { api } from '../api/client'
import { TIME_WINDOWS, useGlobalFilters } from '../composables/useGlobalFilters'
import { useDisplay } from '../composables/useDisplay'

/**
 * 全局筛选条：时间窗与维度等值条件跨页面共享，随统一查询请求下发。
 * 只做前端状态编排，不新增后端接口；权限过滤仍由服务端按身份注入。
 */
const { timeWindow, filters, activeCount, setFilter, removeFilter, clearFilters, reset } =
  useGlobalFilters()
const { label } = useDisplay()

const dimensions = ref<DimensionDefinition[]>([])
const draftDimension = ref('')
const draftValue = ref('')
const valueOptions = ref<{ value: string; count: number }[]>([])
const loadingValues = ref(false)
const valueCache = new Map<string, { value: string; count: number }[]>()

const chips = computed(() =>
  Object.entries(filters.value).map(([code, value]) => ({ code, value, name: label(code) }))
)

/** 维度取值候选来自受管字段画像（D1-06），只给高频值，仍允许手动输入。 */
async function loadValueOptions(code: string) {
  valueOptions.value = []
  if (!code) return
  const dimension = dimensions.value.find((item) => item.code === code)
  if (!dimension) return
  const cached = valueCache.get(code)
  if (cached) {
    valueOptions.value = cached
    return
  }
  loadingValues.value = true
  try {
    const profile = await api.profile(dimension.table, dimension.column || code)
    const options = (profile.topValues ?? []).filter((item) => item.value !== '')
    valueCache.set(code, options)
    valueOptions.value = options
  } catch {
    /* 画像不可用时回退为手动输入，不阻断筛选 */
  } finally {
    loadingValues.value = false
  }
}

watch(draftDimension, (code) => {
  loadValueOptions(code)
})

function addDraftFilter() {
  if (!draftDimension.value || !draftValue.value.trim()) return
  setFilter(draftDimension.value, draftValue.value)
  draftValue.value = ''
}

onMounted(async () => {
  try {
    dimensions.value = await api.dimensions()
  } catch {
    /* 维度目录不可用时仍可切换时间窗，不影响既有查询能力 */
  }
})
</script>

<template>
  <div class="gf">
    <div class="gf__lead">
      <span class="gf__dot" />
      <div class="gf__text">
        <div class="gf__title">全局筛选</div>
        <div class="gf__scope">跨页面保留；维度条件作用于无代码查询／多维分析／可视化，仪表板按时间窗生效</div>
      </div>
    </div>

    <div class="gf__controls">
      <el-select v-model="timeWindow" size="small" class="gf__window" aria-label="全局时间窗">
        <el-option v-for="w in TIME_WINDOWS" :key="w.value" :label="w.label" :value="w.value" />
      </el-select>
      <el-select
        v-model="draftDimension"
        size="small"
        filterable
        clearable
        placeholder="维度"
        class="gf__dim"
        aria-label="筛选维度"
      >
        <el-option v-for="d in dimensions" :key="d.code" :value="d.code" :label="d.businessName" />
      </el-select>
      <el-select
        v-model="draftValue"
        size="small"
        filterable
        allow-create
        default-first-option
        clearable
        :loading="loadingValues"
        placeholder="维度值，如 华东"
        class="gf__value"
        @change="addDraftFilter"
      >
        <el-option
          v-for="item in valueOptions"
          :key="item.value"
          :value="item.value"
          :label="item.value + '（' + item.count + '）'"
        />
      </el-select>
      <el-button
        size="small"
        :disabled="!draftDimension || !draftValue.trim()"
        @click="addDraftFilter"
      >
        添加条件
      </el-button>
      <el-button v-if="activeCount" size="small" text @click="clearFilters">清空条件</el-button>
      <el-button size="small" text type="primary" @click="reset">恢复默认</el-button>
    </div>

    <div v-if="chips.length" class="gf__chips">
      <el-tag
        v-for="c in chips"
        :key="c.code"
        size="small"
        type="info"
        closable
        @close="removeFilter(c.code)"
      >
        {{ c.name }} = {{ c.value }}
      </el-tag>
    </div>
  </div>
</template>

<style scoped>
.gf {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  padding: 10px 34px;
  background: rgba(255, 255, 255, 0.88);
  border-bottom: 1px solid var(--q-line);
  backdrop-filter: blur(12px);
}
.gf__lead {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.gf__dot {
  width: 7px;
  height: 7px;
  flex: 0 0 7px;
  border-radius: 50%;
  background: var(--q-accent);
  box-shadow: 0 0 0 4px rgba(18, 184, 166, 0.14);
}
.gf__title {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--q-ink-2);
  line-height: 1.3;
}
.gf__scope {
  font-size: 10.5px;
  color: var(--q-muted);
  line-height: 1.4;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gf__controls {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-left: auto;
}
.gf__window {
  width: 118px;
}
.gf__dim {
  width: 150px;
}
.gf__value {
  width: 168px;
}
.gf__chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  flex-basis: 100%;
}

@media (max-width: 1100px) {
  .gf {
    padding: 10px 22px;
  }
  .gf__scope {
    display: none;
  }
  .gf__controls {
    margin-left: 0;
  }
}
@media (max-width: 760px) {
  .gf__dim,
  .gf__value {
    width: 100%;
  }
}
</style>
