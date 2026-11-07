<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import type { MetricDefinition, FeedbackEntry } from '../types'
import { api } from '../api/client'

const metrics = ref<MetricDefinition[]>([])
const list = ref<FeedbackEntry[]>([])
const form = ref({ question: '', correctedMetric: '', note: '' })
const submitting = ref(false)

const flow = [
  { no: '01', title: '提交纠偏', desc: '把有偏差的问句指向语义层的认证指标' },
  { no: '02', title: '样本回流', desc: '纠正样本进入下一轮评测集合' },
  { no: '03', title: '评测回归', desc: '一键回归验证口径与护栏是否稳定' }
]

async function submit() {
  if (!form.value.correctedMetric) {
    ElMessage.warning('请选择纠正后的认证指标')
    return
  }
  submitting.value = true
  try {
    await api.submitFeedback({ ...form.value })
    ElMessage.success('已回流到下一次评测回归')
    form.value = { question: '', correctedMetric: '', note: '' }
    await refresh()
  } finally {
    submitting.value = false
  }
}

async function refresh() {
  list.value = await api.listFeedback()
}

onMounted(async () => {
  ;[metrics.value, list.value] = await Promise.all([api.metrics(), api.listFeedback()])
})
</script>

<template>
  <div class="feedback q-stack">
    <div class="feedback__grid">
      <section class="q-card q-card--flush">
        <div class="q-card__head">
          <div class="q-card__title">提交纠偏</div>
          <span class="q-chip q-chip--neutral">口径闭环</span>
        </div>
        <div class="q-card__body">
          <el-form label-width="96px" class="feedback-form">
            <el-form-item label="原问句">
              <el-input v-model="form.question" placeholder="可选，留空则按当前问句" />
            </el-form-item>
            <el-form-item label="纠正后指标">
              <el-select v-model="form.correctedMetric" filterable placeholder="选择认证指标">
                <el-option
                  v-for="m in metrics"
                  :key="m.code"
                  :value="m.code"
                  :label="m.businessName + '（' + m.code + '）'"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="纠正说明">
              <el-input v-model="form.note" type="textarea" :rows="3" placeholder="说明口径偏差或期望的解释" />
            </el-form-item>
            <div class="feedback-form__foot">
              <span class="q-subtle">纠正口径以语义层认证指标为准</span>
              <el-button type="primary" :loading="submitting" @click="submit">提交并回流</el-button>
            </div>
          </el-form>
        </div>
      </section>

      <aside class="q-card flow-card">
        <div class="q-eyebrow">FEEDBACK LOOP</div>
        <h3 class="flow-card__title">让每一次纠偏都进入回归</h3>
        <p class="q-subtle flow-card__desc">问数结果不确定时，纠正样本会回流到评测闭环，持续校验口径一致性。</p>
        <div class="flow">
          <div v-for="f in flow" :key="f.no" class="flow__item">
            <span class="flow__no">{{ f.no }}</span>
            <div>
              <div class="flow__name">{{ f.title }}</div>
              <div class="flow__desc">{{ f.desc }}</div>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <section v-if="list.length" class="q-card q-card--flush">
      <div class="q-card__head">
        <div class="q-card__title">回流记录</div>
        <span class="q-chip q-chip--neutral">{{ list.length }} 条</span>
      </div>
      <div class="records">
        <el-table :data="list" border stripe>
          <el-table-column prop="question" label="原问句" min-width="220" />
          <el-table-column prop="correctedMetric" label="纠正指标" width="200" />
          <el-table-column prop="note" label="说明" min-width="180" />
          <el-table-column prop="versionSource" label="版本来源" width="200" />
        </el-table>
      </div>
    </section>
    <div v-else class="q-card records-empty">
      <span class="records-empty__mark">↺</span>
      <div class="records-empty__title">暂无回流记录</div>
      <p class="q-subtle">提交一条纠偏后，记录会显示在这里并进入下一轮评测回归。</p>
    </div>
  </div>
</template>

<style scoped>
.feedback__grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(280px, 1fr);
  gap: 18px;
  align-items: stretch;
}
.feedback-form {
  max-width: 620px;
}
.feedback-form :deep(.el-select) {
  width: 100%;
}
.feedback-form__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-left: 96px;
  padding-top: 4px;
}

.flow-card {
  position: relative;
  overflow: hidden;
  background: linear-gradient(155deg, #ffffff 30%, #f5f7ff 100%);
}
.flow-card::after {
  content: '';
  position: absolute;
  right: -60px;
  bottom: -80px;
  width: 220px;
  height: 220px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(79, 95, 245, 0.1), transparent 68%);
  pointer-events: none;
}
.flow-card__title {
  position: relative;
  margin-top: 8px;
  font-size: 17px;
  font-weight: 750;
  letter-spacing: -0.02em;
  color: var(--q-ink);
}
.flow-card__desc {
  position: relative;
  margin-top: 8px;
  max-width: 340px;
}
.flow {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin-top: 24px;
}
.flow__item {
  display: flex;
  align-items: flex-start;
  gap: 13px;
}
.flow__no {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  border-radius: 10px;
  background: var(--q-brand-soft);
  color: var(--q-brand-deep);
  font-size: 11.5px;
  font-weight: 800;
}
.flow__name {
  font-size: 13px;
  font-weight: 700;
  color: var(--q-ink);
}
.flow__desc {
  margin-top: 2px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--q-muted);
}

.records {
  padding: 16px 18px 18px;
}
.records-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 40px 20px;
  text-align: center;
  border-style: dashed;
  background: rgba(255, 255, 255, 0.6);
  box-shadow: none;
}
.records-empty__mark {
  font-size: 26px;
  line-height: 1;
  color: #c3cbf0;
}
.records-empty__title {
  font-size: 14px;
  font-weight: 700;
  color: var(--q-ink-2);
}

@media (max-width: 1000px) {
  .feedback__grid {
    grid-template-columns: 1fr;
  }
  .feedback-form__foot {
    margin-left: 0;
  }
}
</style>
