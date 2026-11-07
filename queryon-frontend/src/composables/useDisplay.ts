import { ref } from 'vue'
import type { DisplayMeta } from '../types'
import { api } from '../api/client'

// 展示元数据是静态目录，模块级缓存一次请求，所有表格／报表／交叉表共用同一套中文业务名。
const meta = ref<DisplayMeta>({ columnLabels: {}, metricUnits: {}, drillDown: {} })
let pending: Promise<void> | null = null

export function useDisplay() {
  if (!pending) {
    pending = api
      .display()
      .then((value) => {
        meta.value = value
      })
      .catch(() => {
        pending = null // 失败可重试，且 label 回退为原始编码，不影响取数
      })
  }
  const label = (code: string): string => meta.value.columnLabels[code] ?? code
  const unit = (code: string): string => meta.value.metricUnits[code] ?? ''
  const nextDim = (code: string): string => meta.value.drillDown[code] ?? ''
  return { meta, label, unit, nextDim }
}
