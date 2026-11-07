<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import type { AskResponse, MetricDefinition, ResultSet } from '../types'
import { api } from '../api/client'
import { useSession } from '../composables/useSession'
import { useSuggest } from '../composables/useSuggest'
import { useDisplay } from '../composables/useDisplay'
import ResultTable from '../components/ResultTable.vue'
import ChartView from '../components/ChartView.vue'
import ExplanationPanel from '../components/ExplanationPanel.vue'

interface Message {
  question: string
  response: AskResponse | null
  error: unknown | null
}

const { sessionId } = useSession()
const { label, unit } = useDisplay()
const question = ref('')
const loading = ref(false)
const messages = ref<Message[]>([])
const metrics = ref<MetricDefinition[]>([])
const historyEl = ref<HTMLElement | null>(null)

const feedbackVisible = ref(false)
const feedbackForm = ref({ question: '', correctedMetric: '', note: '' })

const examples = [
  { tag: '单指标查询', text: '上月净营收是多少' },
  { tag: '分组对比', text: '各销售大区的净营收' },
  { tag: '护栏验证', text: '帮我删除上个月取消的订单' }
]

const statusText: Record<string, string> = {
  ANSWERED: '已回答',
  DEFINITION: '口径说明',
  CLARIFICATION: '需要澄清',
  REJECTED: '护栏拦截'
}

// Vue 模板表达式不支持 TS 类型断言，错误原因在此解析后回传模板。
function errorReason(error: unknown): string {
  const value = error as { error?: { reason?: string } } | null
  return value?.error?.reason ?? '服务不可达'
}

function numericValue(rows: Record<string, unknown>[], cols: string[]) {
  if (!rows.length) return null
  const col = cols.find((c) => typeof rows[0][c] === 'number')
  if (!col) return null
  return { label: col, value: Number(rows[0][col] ?? 0) }
}

function metricDisplay(rs: ResultSet) {
  const v = numericValue(rs.rows, rs.columns)
  if (!v) return null
  const formatted = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(v.value)
  return { value: formatted, label: label(v.label), unit: unit(v.label) }
}

const { suggest } = useSuggest()

// D4-06 输入联想：把语义层术语补进问句，不改写用户已输入内容。
function applySuggestion(term: string) {
  const current = question.value.trim()
  question.value = current ? current + ' ' + term : term
}

// D4-06 后续推荐：一键把推荐问句继续送入同一问数链路。
function askSuggestion(text: string) {
  question.value = text
  send()
}

async function scrollToBottom() {
  await nextTick()
  const el = historyEl.value
  if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
}

async function send() {
  const q = question.value.trim()
  if (!q || loading.value) return
  loading.value = true
  const msg: Message = { question: q, response: null, error: null }
  messages.value.push(msg)
  question.value = ''
  await scrollToBottom()
  try {
    msg.response = await api.ask(q, sessionId.value)
  } catch (e) {
    msg.error = e
  } finally {
    loading.value = false
    await scrollToBottom()
  }
}

function askExample(text: string) {
  if (loading.value) return
  question.value = text
  send()
}

function openFeedback(response: AskResponse) {
  feedbackForm.value = { question: response.question, correctedMetric: '', note: '' }
  feedbackVisible.value = true
}

async function submitFeedback() {
  if (!feedbackForm.value.correctedMetric) {
    ElMessage.warning('请选择纠正后的认证指标')
    return
  }
  await api.submitFeedback({ ...feedbackForm.value })
  ElMessage.success('已回流到下一次评测回归')
  feedbackVisible.value = false
}

onMounted(async () => {
  try {
    metrics.value = await api.metrics()
  } catch {
    /* 口径列表仅用于反馈选择，缺失时不影响问数 */
  }
})
</script>

<template>
  <div class="ask">
    <div ref="historyEl" class="history">
      <div v-if="!messages.length" class="welcome q-rise">
        <div class="welcome__orb">
          <span class="welcome__orb-ring" />
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path
              d="M12 2.5l1.9 4.8 4.8 1.9-4.8 1.9L12 16l-1.9-4.9-4.8-1.9 4.8-1.9L12 2.5Z M18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8.8-2Z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </div>
        <h2 class="welcome__title">用一句话，问出可信赖的答案</h2>
        <p class="welcome__desc">
          答案只来自语义层的受管指标与已批准关联路径；解析、Schema、语义、执行四道护栏依次校验。
        </p>
        <div class="examples">
          <button
            v-for="(e, i) in examples"
            :key="e.text"
            type="button"
            class="example"
            @click="askExample(e.text)"
          >
            <span class="example__index">0{{ i + 1 }}</span>
            <span class="example__tag">{{ e.tag }}</span>
            <span class="example__text">{{ e.text }}</span>
            <span class="example__arrow">↗</span>
          </button>
        </div>
      </div>

      <div v-for="(m, i) in messages" :key="i" class="msg q-rise">
        <div class="msg__user">
          <div class="msg__bubble">{{ m.question }}</div>
          <span class="msg__user-avatar">我</span>
        </div>

        <div class="msg__answer">
          <div class="msg__ai">
            <span class="msg__ai-avatar">Q</span>
            <span class="msg__ai-name">Queryon</span>
            <span
              v-if="m.response"
              class="msg__status"
              :class="'msg__status--' + m.response.status.toLowerCase()"
            >
              {{ statusText[m.response.status] }}
            </span>
          </div>

          <div class="msg__body">
            <el-alert
              v-if="m.response?.status === 'REJECTED'"
              type="error"
              :closable="false"
              show-icon
              :title="(m.response.error?.code ?? '') + ' · ' + (m.response.error?.gate ?? '')"
              :description="(m.response.error?.reason ?? '') + (m.response.error?.hint ? '｜' + m.response.error?.hint : '')"
            />
            <el-alert
              v-else-if="m.response?.status === 'CLARIFICATION'"
              type="warning"
              :closable="false"
              show-icon
              title="需要澄清"
              :description="m.response.clarification ?? ''"
            />
            <el-alert
              v-else-if="m.response?.status === 'DEFINITION'"
              type="info"
              :closable="false"
              show-icon
              title="口径说明"
              :description="m.response.metricDefinitions.join('；')"
            />
            <template v-else-if="m.response">
              <div v-if="m.response.chartType === 'metric'" class="metric-card">
                <div class="metric-card__glow" />
                <div class="metric-card__label">
                  <span class="metric-card__dot" />
                  {{ metricDisplay(m.response.resultSet)?.label ?? '指标结果' }}
                </div>
                <div class="metric-card__value">
                  {{ metricDisplay(m.response.resultSet)?.value ?? '—' }}
                  <small v-if="metricDisplay(m.response.resultSet)?.unit">
                    {{ metricDisplay(m.response.resultSet)?.unit }}
                  </small>
                </div>
                <div class="metric-card__note">受管指标 · 口径与追踪见下方展开</div>
              </div>
              <div class="result-stack">
                <ChartView :chart-type="m.response.chartType" :result-set="m.response.resultSet" />
                <ResultTable :result-set="m.response.resultSet" />
              </div>
              <ExplanationPanel
                class="msg__explain"
                :definitions="m.response.metricDefinitions"
                :sql="m.response.sqlSnapshot"
                :steps="m.response.steps"
                :error="m.response.error"
              />
              <div v-if="m.response.interpretation?.length" class="insight">
                <div class="insight__title">数据解读</div>
                <ul>
                  <li v-for="(line, i) in m.response.interpretation" :key="i">{{ line }}</li>
                </ul>
              </div>
              <div v-if="m.response.suggestions?.length" class="suggest">
                <span class="suggest__label">继续追问</span>
                <el-button
                  v-for="s in m.response.suggestions"
                  :key="s"
                  size="small"
                  text
                  type="primary"
                  @click="askSuggestion(s)"
                >
                  {{ s }}
                </el-button>
              </div>
              <div class="msg__foot">
                <span class="msg__trace">trace · {{ m.response.traceId }}</span>
                <button type="button" class="feedback-btn" @click="openFeedback(m.response)">
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path
                      d="M4 12a8 8 0 0 1 13.7-5.6L20 9 M20 3.8V9h-5.2 M20 12a8 8 0 0 1-13.7 5.6L4 15 M4 20.2V15h5.2"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.7"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                  纠偏并回流
                </button>
              </div>
            </template>
            <el-alert
              v-else-if="m.error"
              type="error"
              :closable="false"
              show-icon
              title="请求失败"
              :description="errorReason(m.error)"
            />
            <div v-else class="thinking">
              <span class="thinking__dots"><i /><i /><i /></span>
              正在解析问句、校验语义与权限…
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="composer">
      <div class="composer__box">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          resize="none"
          placeholder="用自然语言提问，例如：各销售大区的净营收"
          @keydown.enter.exact.prevent="send()"
        />
        <div class="composer__foot">
          <span class="composer__hint">Enter 发送 · Shift + Enter 换行</span>
          <el-button type="primary" :loading="loading" :disabled="!question.trim()" @click="send()">
            发送
          </el-button>
        </div>
        <div class="composer__suggest">
          <span>联想</span>
          <el-button
            v-for="term in suggest(question)"
            :key="term"
            size="small"
            text
            type="primary"
            @click="applySuggestion(term)"
          >
            {{ term }}
          </el-button>
        </div>
      </div>
      <p class="composer__note">所有输出仅限查询与展示，任何写操作都会被护栏拦截。</p>
    </div>

    <el-dialog v-model="feedbackVisible" title="纠偏并回流" width="520px">
      <el-form label-width="96px">
        <el-form-item label="原问句">
          <el-input v-model="feedbackForm.question" disabled />
        </el-form-item>
        <el-form-item label="纠正后指标">
          <el-select v-model="feedbackForm.correctedMetric" filterable placeholder="选择认证指标">
            <el-option
              v-for="m in metrics"
              :key="m.code"
              :value="m.code"
              :label="m.businessName + '（' + m.code + '）'"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="纠正说明">
          <el-input v-model="feedbackForm.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="feedbackVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFeedback">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ask {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 14px;
}

.history {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 6px 8px 2px;
  scroll-behavior: smooth;
}

/* ---------- 欢迎态 ---------- */
.welcome {
  max-width: 820px;
  margin: min(6vh, 56px) auto 0;
  text-align: center;
}
.welcome__orb {
  position: relative;
  display: grid;
  place-items: center;
  width: 76px;
  height: 76px;
  margin: 0 auto 22px;
  border-radius: 24px;
  color: #fff;
  background: linear-gradient(140deg, #6b78ff, #414ee0 65%, #12b8a6);
  box-shadow: 0 18px 38px -16px rgba(79, 95, 245, 0.75);
}
.welcome__orb svg {
  width: 34px;
  height: 34px;
}
.welcome__orb-ring {
  position: absolute;
  inset: -13px;
  border: 1px dashed rgba(79, 95, 245, 0.3);
  border-radius: 32px;
  animation: q-fade 1.2s var(--q-ease) both;
}
.welcome__title {
  font-size: 24px;
  font-weight: 750;
  letter-spacing: -0.025em;
  color: var(--q-ink);
}
.welcome__desc {
  max-width: 560px;
  margin: 10px auto 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--q-muted);
}
.examples {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 32px;
  text-align: left;
}
.example {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-height: 122px;
  padding: 16px;
  border: 1px solid var(--q-line);
  border-radius: var(--q-radius);
  background: #fff;
  box-shadow: var(--q-shadow-sm);
  font-family: inherit;
  cursor: pointer;
  transition: transform 0.25s var(--q-ease), box-shadow 0.25s var(--q-ease),
    border-color 0.25s ease;
}
.example:hover {
  transform: translateY(-3px);
  border-color: #c8cfff;
  box-shadow: var(--q-shadow);
}
.example__index {
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.08em;
  color: #b9c3e6;
}
.example__tag {
  align-self: flex-start;
  padding: 2px 8px;
  border-radius: 5px;
  background: var(--q-brand-soft);
  color: var(--q-brand-deep);
  font-size: 10.5px;
  font-weight: 700;
}
.example__text {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.55;
  color: var(--q-ink);
  padding-right: 16px;
}
.example__arrow {
  position: absolute;
  right: 15px;
  bottom: 14px;
  color: #b6c0dd;
  font-size: 15px;
  transition: color 0.2s ease, transform 0.2s var(--q-ease);
}
.example:hover .example__arrow {
  color: var(--q-brand);
  transform: translate(2px, -2px);
}

/* ---------- 对话 ---------- */
.msg {
  max-width: 980px;
  margin: 0 auto 26px;
}
.msg__user {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 14px;
}
.msg__bubble {
  max-width: min(74%, 680px);
  padding: 10px 16px;
  border-radius: 14px 14px 4px 14px;
  background: linear-gradient(135deg, #5a68f8, #4553e6);
  color: #fff;
  font-size: 13.5px;
  font-weight: 550;
  line-height: 1.6;
  box-shadow: 0 8px 20px -12px rgba(79, 95, 245, 0.9);
  word-break: break-word;
}
.msg__user-avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 9px;
  border: 1px solid var(--q-line);
  background: #fff;
  color: var(--q-ink-2);
  font-size: 12px;
  font-weight: 700;
}
.msg__answer {
  padding-left: 2px;
}
.msg__ai {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.msg__ai-avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: linear-gradient(140deg, #6b78ff, #3b49d8);
  color: #fff;
  font-size: 13px;
  font-weight: 800;
  box-shadow: 0 5px 12px -6px rgba(79, 95, 245, 0.9);
}
.msg__ai-name {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--q-ink-2);
}
.msg__status {
  padding: 2px 9px;
  border-radius: 999px;
  background: #eaf8f3;
  color: #0e8a72;
  font-size: 10.5px;
  font-weight: 700;
}
.msg__status--rejected {
  background: #fdeef0;
  color: #c93f56;
}
.msg__status--clarification {
  background: #fdf5e9;
  color: #b9761a;
}
.msg__status--definition {
  background: var(--q-brand-soft);
  color: var(--q-brand-deep);
}
.msg__body {
  padding-left: 34px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: stretch;
}

.result-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.msg__explain {
  margin-top: 2px;
}
.msg__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.msg__trace {
  font-size: 10.5px;
  color: #a4afc6;
  font-family: 'SFMono-Regular', Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.feedback-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
  padding: 5px 11px;
  border: 1px solid var(--q-line);
  border-radius: 8px;
  background: #fff;
  color: var(--q-brand-deep);
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 650;
  cursor: pointer;
  transition: border-color 0.2s ease, background-color 0.2s ease, transform 0.2s var(--q-ease);
}
.feedback-btn svg {
  width: 14px;
  height: 14px;
}
.feedback-btn:hover {
  border-color: #c8cfff;
  background: var(--q-brand-soft);
  transform: translateY(-1px);
}

.thinking {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  align-self: flex-start;
  padding: 10px 15px;
  border: 1px solid var(--q-line);
  border-radius: 12px;
  background: #fff;
  box-shadow: var(--q-shadow-sm);
  color: var(--q-muted);
  font-size: 12.5px;
}
.thinking__dots {
  display: inline-flex;
  gap: 4px;
}
.thinking__dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--q-brand);
  animation: q-dots 1.2s infinite ease-in-out;
}
.thinking__dots i:nth-child(2) {
  animation-delay: 0.16s;
}
.thinking__dots i:nth-child(3) {
  animation-delay: 0.32s;
}

/* ---------- 指标卡 ---------- */
.metric-card {
  position: relative;
  overflow: hidden;
  padding: 24px 28px;
  border: 1px solid #e2e6ff;
  border-radius: var(--q-radius);
  background: linear-gradient(120deg, #ffffff 20%, #f6f7ff 100%);
  box-shadow: var(--q-shadow-sm);
}
.metric-card__glow {
  position: absolute;
  right: -50px;
  top: -80px;
  width: 220px;
  height: 220px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(79, 95, 245, 0.13), transparent 68%);
  pointer-events: none;
}
.metric-card__label {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  font-weight: 650;
  color: var(--q-ink-2);
}
.metric-card__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--q-accent);
  box-shadow: 0 0 0 4px rgba(18, 184, 166, 0.14);
}
.metric-card__value {
  position: relative;
  margin-top: 8px;
  font-size: 42px;
  font-weight: 750;
  letter-spacing: -0.035em;
  line-height: 1.15;
  color: var(--q-brand-deep);
  font-variant-numeric: tabular-nums;
}
.metric-card__value small {
  margin-left: 8px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0;
  color: var(--q-muted);
}
.metric-card__note {
  position: relative;
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--q-muted);
}

/* ---------- 输入区 ---------- */
.composer {
  flex: 0 0 auto;
  max-width: 980px;
  width: 100%;
  margin: 0 auto;
}
.composer__box {
  border: 1px solid #dfe4f3;
  border-radius: var(--q-radius);
  background: #fff;
  box-shadow: 0 10px 30px -18px rgba(24, 39, 82, 0.45);
  transition: border-color 0.22s ease, box-shadow 0.22s ease;
}
.composer__box:focus-within {
  border-color: #b9c2fc;
  box-shadow: 0 0 0 3px rgba(79, 95, 245, 0.09), 0 12px 30px -18px rgba(24, 39, 82, 0.5);
}
.composer__box :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 14px 16px 4px;
  background: transparent;
  color: var(--q-ink);
  font-size: 13.5px;
  line-height: 1.7;
  resize: none;
}
.composer__box :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}
.composer__box :deep(.el-textarea__inner::placeholder) {
  color: #a9b3ca;
}
.composer__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 6px 12px 12px 16px;
}
.composer__hint {
  font-size: 11.5px;
  color: #a4afc6;
}
.composer__note {
  margin-top: 9px;
  text-align: center;
  font-size: 11px;
  color: #a4afc6;
}

@media (max-width: 860px) {
  .examples {
    grid-template-columns: 1fr;
  }
  .example {
    min-height: 90px;
  }
  .msg__body {
    padding-left: 0;
  }
  .msg__bubble {
    max-width: 84%;
  }
}
.composer__suggest {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px 6px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 12px;
  color: var(--q-text-soft, #8491ad);
}
</style>
