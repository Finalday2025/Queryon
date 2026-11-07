import { computed, ref, watch } from 'vue'

/**
 * 跨页面全局筛选器（D3-05）：时间窗与维度等值条件由模块级单例持有，
 * 问数／统一查询／仪表板／多维分析／可视化共用同一份状态，刷新后从 localStorage 恢复。
 * 纯前端实现：只组装统一查询请求的 timeWindow／filters 字段，不新增后端接口。
 */

export type TimeWindowCode = 'LAST_MONTH' | 'YEAR_TO_DATE' | 'ALL'

export const TIME_WINDOWS: { value: TimeWindowCode; label: string }[] = [
  { value: 'LAST_MONTH', label: '上月' },
  { value: 'YEAR_TO_DATE', label: '年初至今' },
  { value: 'ALL', label: '全部' }
]

/** 仪表板等只接受时间窗的入口，用它展示当前全局范围。 */
export function timeWindowLabel(code: TimeWindowCode | string): string {
  return TIME_WINDOWS.find((item) => item.value === code)?.label ?? String(code)
}

const STORAGE_KEY = 'queryon-global-filters'

interface PersistedState {
  timeWindow: TimeWindowCode
  filters: Record<string, string>
}

function readPersisted(): PersistedState {
  const fallback: PersistedState = { timeWindow: 'LAST_MONTH', filters: {} }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return fallback
    const parsed = JSON.parse(raw) as Partial<PersistedState>
    const timeWindow = TIME_WINDOWS.some((item) => item.value === parsed.timeWindow)
      ? (parsed.timeWindow as TimeWindowCode)
      : fallback.timeWindow
    const filters: Record<string, string> = {}
    Object.entries(parsed.filters ?? {}).forEach(([code, value]) => {
      if (typeof value === 'string' && value.trim() !== '') filters[code] = value
    })
    return { timeWindow, filters }
  } catch {
    return fallback
  }
}

const initial = readPersisted()
const timeWindow = ref<TimeWindowCode>(initial.timeWindow)
const filters = ref<Record<string, string>>(initial.filters)
const activeCount = computed(() => Object.keys(filters.value).length)

// 变更即持久化，避免刷新后丢掉用户已选的全局条件。
watch(
  [timeWindow, filters],
  () => {
    try {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ timeWindow: timeWindow.value, filters: filters.value })
      )
    } catch {
      /* 隐私模式下 localStorage 不可写时静默降级为会话内生效 */
    }
  },
  { deep: true }
)

export function useGlobalFilters() {
  function setTimeWindow(value: TimeWindowCode) {
    timeWindow.value = value
  }

  function setFilter(dimension: string, value: string) {
    const code = dimension.trim()
    const text = value.trim()
    if (!code || !text) return
    filters.value = { ...filters.value, [code]: text }
  }

  function removeFilter(dimension: string) {
    if (!(dimension in filters.value)) return
    const next = { ...filters.value }
    delete next[dimension]
    filters.value = next
  }

  function clearFilters() {
    filters.value = {}
  }

  function reset() {
    timeWindow.value = 'LAST_MONTH'
    filters.value = {}
  }

  /** 合并全局条件与调用方自有条件（如下钻固定值），后者优先且不写回全局状态。 */
  function merge(extra?: Record<string, string>): Record<string, string> {
    return { ...filters.value, ...(extra ?? {}) }
  }

  return {
    timeWindow,
    filters,
    activeCount,
    setTimeWindow,
    setFilter,
    removeFilter,
    clearFilters,
    reset,
    merge
  }
}
