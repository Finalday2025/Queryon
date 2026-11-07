import { ref } from 'vue'
import { api } from '../api/client'
import type { MetricDefinition, DimensionDefinition } from '../types'

/** 输入联想补全（D4-06）：候选词来自语义层指标/维度的中文业务名与编码，纯本地匹配。 */
const terms = ref<string[]>([])
let loaded: Promise<void> | null = null

export function useSuggest() {
  if (!loaded) {
    loaded = Promise.all([api.metrics(), api.dimensions()])
      .then(([metrics, dimensions]) => {
        const all: string[] = []
        metrics.forEach((m: MetricDefinition) => all.push(m.businessName, m.code))
        dimensions.forEach((d: DimensionDefinition) => all.push(d.businessName, d.code))
        terms.value = Array.from(new Set(all.filter((term) => term && term.trim())))
      })
      .catch(() => {
        loaded = null
      })
  }

  function suggest(input: string): string[] {
    const keyword = input.trim().toLowerCase()
    if (!keyword) return terms.value.slice(0, 8)
    return terms.value.filter((term) => term.toLowerCase().includes(keyword)).slice(0, 8)
  }

  return { terms, suggest }
}
