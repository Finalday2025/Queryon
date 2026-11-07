<script setup lang="ts">
import { ref, computed } from 'vue'
import AskView from './views/AskView.vue'
import QueryView from './views/QueryView.vue'
import SemanticView from './views/SemanticView.vue'
import EvaluationView from './views/EvaluationView.vue'
import FeedbackView from './views/FeedbackView.vue'
import ReportView from './views/ReportView.vue'
import MultidimensionalView from './views/MultidimensionalView.vue'
import VisualizationView from './views/VisualizationView.vue'
import DashboardView from './views/DashboardView.vue'
import AuditView from './views/AuditView.vue'
import ConnectionView from './views/ConnectionView.vue'
import DatasetDefinitionView from './views/DatasetDefinitionView.vue'
import GlobalFilterBar from './components/GlobalFilterBar.vue'
import { useSession } from './composables/useSession'

const active = ref('ask')
const { userId, setUser } = useSession()

const accounts = [
  { value: 'admin_bi', label: 'admin_bi（全量基准）' },
  { value: 'analyst_east', label: 'analyst_east（华东·敏感屏蔽）' }
]

const titles: Record<string, string> = {
  connections: '连接管理',
  datasets: '逻辑数据集',
  ask: '智能问数',
  query: '统一查询',
  report: '报表',
  dashboard: '仪表板',
  pivot: '多维分析',
  visualization: '可视化分析',
  semantic: '口径中心',
  evaluation: '评测与回归',
  feedback: '反馈闭环',
  audit: '审计台账'
}

const subtitles: Record<string, string> = {
  connections: '连接参数、凭据密文与连通性测试，维护操作仅限全量基准身份',
  datasets: '裁剪受管指标与维度形成分析单元，共享后仍按提问者身份取数',
  ask: '用自然语言提问，答案来自受管指标与已批准的关联路径',
  query: 'SQL 编辑器与无代码构建器共用同一语义层与治理横切',
  report: '复用统一查询的结果集与口径，导出、打印保持一致',
  dashboard: '全局时间窗下的受治理卡片，与问数、SQL 编辑器同口径',
  pivot: '切片、切块、行列维交换与逐层下钻，一次取数多处复用',
  visualization: '按度量生成多图，可切换柱／线／饼图，口径与明细同源',
  semantic: '指标、维度与关联路径的唯一认证来源',
  evaluation: '黄金样本一键回归，评测不过阻断发布',
  feedback: '纠偏样本回流，驱动下一轮评测回归',
  audit: '成功取数与护栏拦截的结构化事件，可检索、不含敏感内容'
}

// 统一管理导航分组与图标，图标为 24×24 线性路径，避免引入图标库依赖。
const icons: Record<string, string> = {
  connections: 'M4 6.5c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3Z M4 6.5v11c0 1.7 3.6 3 8 3s8-1.3 8-3v-11 M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  datasets: 'M12 3 3 8l9 5 9-5-9-5Z M3 12l9 5 9-5 M3 16l9 5 9-5',
  ask: 'M12 2.5l1.9 4.8 4.8 1.9-4.8 1.9L12 16l-1.9-4.9-4.8-1.9 4.8-1.9L12 2.5Z M18.5 15.5l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8.8-2Z M5.5 16l.6 1.5 1.5.6-1.5.6L5.5 20l-.6-1.3-1.5-.6 1.5-.6.6-1.5Z',
  query: 'M3.5 5h17v14h-17z M3.5 10h17 M3.5 14.5h17 M10 5v14',
  report: 'M6 2.8h8l4.2 4.2v14H6z M14 2.8V7h4.2 M9 11.5h6 M9 15h6 M9 18.5h4',
  pivot: 'M4 4h7v7H4z M13 4h7v4h-7z M13 10h7v10h-7z M4 13h7v7H4z M4 9.5h7 M8.5 13v7',
  visualization: 'M4 20V9 M9.5 20V4 M15 20v-7 M20.5 20V7 M2.5 20h19',
  dashboard: 'M3.5 3.5h7v7h-7z M13.5 3.5h7v4h-7z M13.5 10.5h7v10h-7z M3.5 13.5h7v7h-7z',
  audit: 'M6 3h9l3 3v15H6z M9 9h6 M9 13h6 M9 17h4',
  semantic: 'M12 2.8 3 7.4l9 4.6 9-4.6-9-4.6Z M3 12.2l9 4.6 9-4.6 M3 16.8l9 4.6 9-4.6',
  evaluation: 'M12 2.8l8 3v6.1c0 4.6-3.2 8-8 9.2-4.8-1.2-8-4.6-8-9.2V5.8l8-3Z M8.5 12l2.4 2.4 4.6-4.7',
  feedback: 'M4 12a8 8 0 0 1 13.7-5.6L20 9 M20 3.8V9h-5.2 M20 12a8 8 0 0 1-13.7 5.6L4 15 M4 20.2V15h5.2'
}

const groups = [
  {
    label: '数据接入',
    items: [
      { key: 'connections', label: '连接管理' },
      { key: 'datasets', label: '逻辑数据集' }
    ]
  },
  {
    label: '分析工作台',
    items: [
      { key: 'ask', label: '智能问数' },
      { key: 'query', label: '统一查询' },
      { key: 'report', label: '报表' },
      { key: 'dashboard', label: '仪表板' },
      { key: 'pivot', label: '多维分析' },
      { key: 'visualization', label: '可视化分析' }
    ]
  },
  {
    label: '治理与可信',
    items: [
      { key: 'semantic', label: '口径中心' },
      { key: 'evaluation', label: '评测与回归' },
      { key: 'feedback', label: '反馈闭环' },
      { key: 'audit', label: '审计台账' }
    ]
  }
]

// 全局筛选条覆盖真正消费时间窗／维度条件的分析页；SQL 与报表按原语句执行，不显示。
const filterPages = ['query', 'dashboard', 'pivot', 'visualization']

const groupLabel = computed(
  () => groups.find((g) => g.items.some((i) => i.key === active.value))?.label ?? ''
)
const actorLabel = computed(
  () => accounts.find((a) => a.value === userId.value)?.value ?? userId.value
)

function setActive(index: string) {
  active.value = index
}

// 模板内联箭头函数的事件参数会被推断为 any，改由具名函数接收。
function setUserFromSelect(value: unknown) {
  setUser(String(value ?? ''))
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <svg class="brand__mark" viewBox="0 0 40 40" aria-hidden="true">
          <defs>
            <linearGradient id="q-brand-gradient" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#6b78ff" />
              <stop offset="100%" stop-color="#3b49d8" />
            </linearGradient>
          </defs>
          <rect width="40" height="40" rx="12" fill="url(#q-brand-gradient)" />
          <circle cx="18.5" cy="18" r="8" fill="none" stroke="#fff" stroke-width="3" />
          <path d="M24 23.5 30 30" stroke="#fff" stroke-width="3" stroke-linecap="round" />
          <circle cx="18.5" cy="18" r="2.4" fill="#fff" />
        </svg>
        <div class="brand__text">
          <div class="brand__name">Queryon</div>
          <div class="brand__tag">语义驱动的问数平台</div>
        </div>
      </div>

      <nav class="nav" aria-label="主导航">
        <div v-for="group in groups" :key="group.label" class="nav-group">
          <div class="nav-group__label">{{ group.label }}</div>
          <button
            v-for="item in group.items"
            :key="item.key"
            type="button"
            class="nav-item"
            :class="{ 'is-active': active === item.key }"
            :aria-current="active === item.key ? 'page' : undefined"
            @click="setActive(item.key)"
          >
            <svg class="nav-item__icon" viewBox="0 0 24 24" aria-hidden="true">
              <path
                :d="icons[item.key]"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <span class="nav-item__label">{{ item.label }}</span>
            <span class="nav-item__dot" />
          </button>
        </div>
      </nav>

      <div class="sidebar-foot">
        <div class="identity-card">
          <div class="identity-card__head">
            <span class="avatar">{{ actorLabel.charAt(0).toUpperCase() }}</span>
            <div class="identity-card__text">
              <div class="identity-card__label">当前身份</div>
              <div class="identity-card__hint">X-Queryon-User</div>
            </div>
          </div>
          <el-select
            :model-value="userId"
            size="small"
            class="identity-select"
            @update:model-value="setUserFromSelect"
          >
            <el-option v-for="a in accounts" :key="a.value" :value="a.value" :label="a.label" />
          </el-select>
          <div class="identity-card__note">行列级权限随提问者身份注入</div>
        </div>
        <div class="sidebar-foot__version">Queryon · 社区版</div>
      </div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <div class="topbar__text">
          <div class="q-eyebrow">QUERYON / {{ groupLabel }}</div>
          <h1 class="topbar__title">{{ titles[active] }}</h1>
          <p class="topbar__subtitle">{{ subtitles[active] }}</p>
        </div>
        <div class="topbar__right">
          <span class="guard-chip">
            <span class="guard-chip__dot" />
            四道护栏 · 只读取数
          </span>
          <span class="actor-chip">
            <span class="actor-chip__avatar">{{ actorLabel.charAt(0).toUpperCase() }}</span>
            <span>{{ actorLabel }}</span>
          </span>
        </div>
      </header>

      <GlobalFilterBar v-if="filterPages.includes(active)" />

      <main class="content" :class="{ 'content--fill': active === 'ask' }">
        <Transition name="q-view" mode="out-in">
          <div :key="active" class="view-shell">
            <ConnectionView v-if="active === 'connections'" />
            <DatasetDefinitionView v-else-if="active === 'datasets'" />
            <AskView v-else-if="active === 'ask'" />
            <QueryView v-else-if="active === 'query'" />
            <ReportView v-else-if="active === 'report'" />
            <DashboardView v-else-if="active === 'dashboard'" />
            <MultidimensionalView v-else-if="active === 'pivot'" />
            <VisualizationView v-else-if="active === 'visualization'" />
            <SemanticView v-else-if="active === 'semantic'" />
            <EvaluationView v-else-if="active === 'evaluation'" />
            <FeedbackView v-else-if="active === 'feedback'" />
            <AuditView v-else-if="active === 'audit'" />
          </div>
        </Transition>
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--q-canvas);
}

/* ---------- 侧边栏 ---------- */
.sidebar {
  position: relative;
  z-index: 2;
  width: 248px;
  flex: 0 0 248px;
  display: flex;
  flex-direction: column;
  color: var(--q-nav-text);
  background: radial-gradient(120% 60% at 0% 0%, #1a2a4e 0%, transparent 60%),
    linear-gradient(170deg, var(--q-nav-bg) 0%, var(--q-nav-bg-2) 100%);
  border-right: 1px solid rgba(255, 255, 255, 0.06);
  box-shadow: 12px 0 40px -32px rgba(5, 12, 30, 0.9);
}

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 24px 20px 26px;
}
.brand__mark {
  width: 38px;
  height: 38px;
  flex: 0 0 38px;
  filter: drop-shadow(0 6px 14px rgba(79, 95, 245, 0.5));
}
.brand__name {
  color: #fff;
  font-size: 19px;
  font-weight: 750;
  letter-spacing: -0.02em;
  line-height: 1.2;
}
.brand__tag {
  margin-top: 3px;
  font-size: 10.5px;
  letter-spacing: 0.04em;
  color: #7789ae;
  white-space: nowrap;
}

.nav {
  flex: 1;
  overflow-y: auto;
  padding: 0 12px;
}
.nav-group {
  margin-bottom: 20px;
}
.nav-group__label {
  padding: 0 12px 9px;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.14em;
  color: #62739b;
}
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  height: 42px;
  margin-bottom: 4px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: var(--q-nav-text);
  font-family: inherit;
  font-size: 13.5px;
  font-weight: 550;
  text-align: left;
  cursor: pointer;
  transition: color 0.22s ease, background-color 0.22s ease, border-color 0.22s ease,
    transform 0.22s var(--q-ease);
}
.nav-item__icon {
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  opacity: 0.85;
}
.nav-item__label {
  flex: 1;
  white-space: nowrap;
}
.nav-item__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--q-accent);
  opacity: 0;
  transform: scale(0.4);
  transition: opacity 0.22s ease, transform 0.22s var(--q-ease);
}
.nav-item:hover {
  color: #e8edfa;
  background: rgba(255, 255, 255, 0.055);
}
.nav-item.is-active {
  color: var(--q-nav-text-active);
  background: linear-gradient(90deg, rgba(79, 95, 245, 0.28), rgba(79, 95, 245, 0.08));
  border-color: rgba(129, 141, 255, 0.22);
  box-shadow: inset 2px 0 0 #7c8aff;
  font-weight: 650;
}
.nav-item.is-active .nav-item__icon {
  opacity: 1;
  color: #a9b3ff;
}
.nav-item.is-active .nav-item__dot {
  opacity: 1;
  transform: scale(1);
  box-shadow: 0 0 0 3px rgba(18, 184, 166, 0.18);
}

.sidebar-foot {
  padding: 14px 14px 16px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}
.identity-card {
  padding: 13px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.045);
}
.identity-card__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 11px;
}
.avatar,
.actor-chip__avatar {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #6b78ff, #12b8a6);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}
.identity-card__label {
  font-size: 12.5px;
  font-weight: 650;
  color: #e5eaf7;
  line-height: 1.3;
}
.identity-card__hint {
  font-size: 10.5px;
  color: #7285ad;
  letter-spacing: 0.02em;
}
.identity-card__note {
  margin-top: 9px;
  font-size: 10.5px;
  line-height: 1.5;
  color: #7285ad;
}
.identity-select {
  width: 100%;
}
.identity-select :deep(.el-select__wrapper) {
  min-height: 30px;
  background: rgba(9, 16, 34, 0.72);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.1) inset;
  border-radius: 8px;
  font-size: 12px;
}
.identity-select :deep(.el-select__placeholder),
.identity-select :deep(.el-select__selected-item) {
  color: #dbe3f6;
}
.sidebar-foot__version {
  margin-top: 12px;
  text-align: center;
  font-size: 10.5px;
  letter-spacing: 0.06em;
  color: #55668d;
}

/* ---------- 工作区 ---------- */
.workspace {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  min-height: 100px;
  padding: 18px 34px;
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--q-line);
}
.topbar__text {
  min-width: 0;
}
.topbar__title {
  margin-top: 3px;
  font-size: 22px;
  font-weight: 750;
  letter-spacing: -0.025em;
  line-height: 1.3;
  color: var(--q-ink);
}
.topbar__subtitle {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--q-muted);
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 0 0 auto;
}
.guard-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border: 1px solid #d9f0e9;
  border-radius: 999px;
  background: #f0faf7;
  color: #0e8a72;
  font-size: 11.5px;
  font-weight: 650;
  white-space: nowrap;
}
.guard-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--q-accent);
  box-shadow: 0 0 0 3px rgba(18, 184, 166, 0.16);
}
.actor-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 13px 5px 5px;
  border: 1px solid var(--q-line);
  border-radius: 999px;
  background: #fff;
  box-shadow: var(--q-shadow-sm);
  font-size: 12px;
  font-weight: 650;
  color: var(--q-ink-2);
  white-space: nowrap;
}
.actor-chip__avatar {
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  border-radius: 7px;
  font-size: 11px;
}

.content {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 26px 34px 40px;
  background: radial-gradient(90% 55% at 100% 0%, rgba(79, 95, 245, 0.045), transparent 70%),
    var(--q-canvas);
}
.content--fill {
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding-bottom: 20px;
}
.view-shell {
  width: 100%;
  max-width: 1360px;
  margin: 0 auto;
  min-height: 100%;
  animation: q-rise 0.4s var(--q-ease) both;
}
.content--fill .view-shell {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

@media (max-width: 1100px) {
  .sidebar {
    width: 72px;
    flex-basis: 72px;
  }
  .brand {
    justify-content: center;
    padding: 20px 0 24px;
  }
  .brand__text,
  .nav-group__label,
  .nav-item__label,
  .nav-item__dot,
  .identity-card__text,
  .identity-card__note,
  .sidebar-foot__version {
    display: none;
  }
  .nav {
    padding: 0 10px;
  }
  .nav-item {
    justify-content: center;
    padding: 0;
  }
  .sidebar-foot {
    padding: 12px 10px;
  }
  .identity-card {
    padding: 8px;
  }
  .identity-card__head {
    justify-content: center;
    margin-bottom: 8px;
  }
  .identity-select :deep(.el-select__selected-item) {
    font-size: 11px;
  }
  .topbar {
    padding: 16px 22px;
  }
  .content {
    padding: 20px 22px 32px;
  }
  .guard-chip {
    display: none;
  }
}

@media (max-width: 640px) {
  .topbar__subtitle,
  .actor-chip {
    display: none;
  }
  .topbar {
    min-height: 76px;
  }
}
</style>
