// 修订 store：S21 问答/修改入口、澄清、保护项、确认弹窗、affected scope、版本时间线、结构化 diff、
// 回滚与 409 冲突处理的状态机与全部副作用。确认前不乐观更新；confirmation_id 复用与重建按规则管理。
// 积分/余额只读服务端：仅提示「确认后由服务端结算」，成功后刷新余额，前端不计算、不预扣。

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { toErrorInfo } from '@/api/http'
import type { ErrorInfo } from '@/api/http'
import * as revisionsApi from '@/api/revisions'
import type { AtomicChange, PlanDay, PlanView, RevisionCommand, RevisionResult } from '@/api/types'
import { dayStatus, deepEqual, effectiveChanges } from '@/composables/revisionDiff'
import { usePlanningStore } from '@/stores/planning'

export type RevisionPhase =
  | 'idle'
  | 'previewing'
  | 'answered'
  | 'needsClarification'
  | 'awaitingConfirm'
  | 'confirming'
  | 'created'
  | 'conflict'
  | 'failed'

export type RevisionMessageKind = 'user' | 'query' | 'clarify' | 'change' | 'result' | 'error'

export interface RevisionMessage {
  id: string
  role: 'user' | 'assistant'
  kind: RevisionMessageKind
  text: string
  summary?: string
  createdAtLocal: string
}

export interface RevisionDraft {
  baseVersionId: string
  etag: string | null
  text: string
  lockedPaths: string[]
  confirmationId: string | null
}

export interface TimelineEntry {
  versionId: string
  parentVersionId: string | null
  /** base = 会话内首次变更前的「修改前版本」占位，仅用于提供可回滚入口。 */
  kind: 'change' | 'rollback' | 'base'
  summary: string
  affectedDays: number[]
  changeCount: number
  etag: string
  contentHash: string | null
  atLocal: string
  reused: boolean
}

export interface ConflictInfo {
  code: 'REVISION_BASE_MISMATCH'
  /** 触发冲突的操作类型，用于区分横幅动作（回滚冲突不渲染「重新预览」按钮）。 */
  kind: 'preview' | 'confirm' | 'rollback'
  serverVersionNo?: number
  serverAt?: string
  traceId?: string
  /** 与冲突前基准相比，内容已变化的天序号（1 起算，按天粒度）。 */
  changedDays: number[]
}

export interface AppliedResult {
  changes: AtomicChange[]
  affectedDays: number[]
  verifiedUnchanged: number[]
  unverified: number[]
  /** 确认前的 days 快照，用于结构化 diff 读取 replace/remove 的「原值」。 */
  beforeDays: PlanDay[]
  summary: string
  versionId: string
  versionNo: number
}

export interface RevisionError extends ErrorInfo {
  kind: 'preview' | 'confirm' | 'rollback'
}

function localNow(): string {
  return new Date().toLocaleString('zh-CN', { hour12: false })
}

function sameList(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  const sa = [...a].sort()
  const sb = [...b].sort()
  return sa.every((value, index) => value === sb[index])
}

function cloneDayList(days: PlanView['plan']['days'] | null): PlanView['plan']['days'] {
  return days ? JSON.parse(JSON.stringify(days)) : []
}

function cloneDays(plan: PlanView | null): PlanView['plan']['days'] {
  return cloneDayList(plan?.plan.days ?? null)
}

/** 按天粒度比较前后 days 快照，返回内容已变化的天序号（1 起算）。 */
function computeChangedDays(
  before: PlanView['plan']['days'],
  after: PlanView['plan']['days'],
): number[] {
  const changed: number[] = []
  const max = Math.max(before.length, after.length)
  for (let i = 0; i < max; i++) {
    if (!deepEqual(before[i], after[i])) changed.push(i + 1)
  }
  return changed
}

export const useRevisionStore = defineStore('revision', () => {
  const planning = usePlanningStore()

  const inputText = ref('')
  const lockedPaths = ref<string[]>([])
  const messages = ref<RevisionMessage[]>([])
  const draft = ref<RevisionDraft | null>(null)
  const pending = ref<RevisionResult | null>(null)
  const timeline = ref<TimelineEntry[]>([])
  const conflict = ref<ConflictInfo | null>(null)
  const lastApplied = ref<AppliedResult | null>(null)
  const error = ref<RevisionError | null>(null)

  const previewLoading = ref(false)
  const confirmLoading = ref(false)
  const rollbackLoading = ref(false)
  const refreshingPlan = ref(false)

  const phase = ref<RevisionPhase>('idle')

  let rollbackTargetVersionId: string | null = null
  let beforeDays: PlanView['plan']['days'] | null = null

  const baseVersion = computed(() => {
    const plan = planning.plan
    if (!plan) return ''
    return `v${plan.version_no}（${plan.version_id.slice(0, 8)}）`
  })

  const currentVersionId = computed(() => planning.plan?.version_id ?? null)

  function setInput(text: string): void {
    inputText.value = text
  }

  function setLockedPaths(paths: string[]): void {
    lockedPaths.value = paths
  }

  /** 进入某个 plan 时，把必去点所在 segment 路径预填为默认保护项（用户仍可取消）。 */
  function applyDefaultLockedPaths(plan: PlanView | null): void {
    if (!plan) return
    const paths: string[] = []
    plan.plan.days.forEach((day, dayIndex) => {
      day.segments.forEach((segment, segmentIndex) => {
        if (segment.is_must_visit) {
          paths.push(`/days/${dayIndex + 1}/segments/${segmentIndex}`)
        }
      })
    })
    lockedPaths.value = paths
  }

  function resetConflict(): void {
    conflict.value = null
  }

  function reset(): void {
    inputText.value = ''
    lockedPaths.value = []
    messages.value = []
    draft.value = null
    pending.value = null
    timeline.value = []
    conflict.value = null
    lastApplied.value = null
    error.value = null
    previewLoading.value = false
    confirmLoading.value = false
    rollbackLoading.value = false
    refreshingPlan.value = false
    rollbackTargetVersionId = null
    beforeDays = null
    phase.value = 'idle'
  }

  function addMessage(
    role: 'user' | 'assistant',
    kind: RevisionMessageKind,
    text: string,
    summary?: string,
  ): void {
    messages.value.push({
      id: crypto.randomUUID(),
      role,
      kind,
      text,
      summary,
      createdAtLocal: localNow(),
    })
  }

  function addUser(text: string): void {
    addMessage('user', 'user', text)
  }

  function addAssistant(kind: RevisionMessageKind, text: string, summary?: string): void {
    addMessage('assistant', kind, text, summary)
  }

  function buildCommand(confirmed: boolean): RevisionCommand {
    const current = draft.value
    if (!current) throw new Error('缺少草稿')
    const payload: RevisionCommand = {
      base_version_id: current.baseVersionId,
      etag: current.etag,
      revision_request: current.text,
      locked_paths: current.lockedPaths,
    }
    if (confirmed) {
      payload.confirmed = true
      payload.confirmation_id = current.confirmationId
    }
    return payload
  }

  async function refreshPlanAndBalance(): Promise<void> {
    refreshingPlan.value = true
    try {
      await planning.fetchPlan()
      await planning.fetchBalance()
    } finally {
      refreshingPlan.value = false
    }
  }

  async function refreshPlanOnly(): Promise<void> {
    refreshingPlan.value = true
    try {
      await planning.fetchPlan()
    } finally {
      refreshingPlan.value = false
    }
  }

  async function enterConflict(
    info: ErrorInfo,
    kind: 'preview' | 'confirm' | 'rollback',
  ): Promise<void> {
    phase.value = 'conflict'
    pending.value = null
    // 保留用户文本用于「重新预览」：409 时文本仍应可编辑/可复用。
    if (draft.value && inputText.value !== draft.value.text) {
      inputText.value = draft.value.text
    }
    const beforeDaysSnapshot = cloneDays(planning.plan)
    conflict.value = {
      code: 'REVISION_BASE_MISMATCH',
      kind,
      changedDays: [],
      traceId: info.traceId,
    }
    await refreshPlanOnly()
    conflict.value = {
      code: 'REVISION_BASE_MISMATCH',
      kind,
      serverVersionNo: planning.plan?.version_no ?? undefined,
      serverAt: planning.plan?.generated_at ?? undefined,
      traceId: info.traceId,
      changedDays: computeChangedDays(beforeDaysSnapshot, cloneDays(planning.plan)),
    }
    error.value = { ...info, kind }
  }

  function applyPreview(result: RevisionResult): void {
    if (result.intent === 'query') {
      phase.value = 'answered'
      pending.value = null
      // query 永不写状态：不生成 confirmationId、不写时间线、不刷新余额、不改 plan。
      addAssistant('query', result.answer ?? result.summary ?? '')
      inputText.value = ''
      return
    }

    if (result.intent === 'ambiguous') {
      phase.value = 'needsClarification'
      pending.value = null
      addAssistant('clarify', result.clarification_question ?? '请补充说明')
      inputText.value = ''
      return
    }

    // change：契约异常（无有效影响范围）不弹确认弹窗。
    if (
      !result.changes ||
      result.changes.length === 0 ||
      !result.affected_days ||
      result.affected_days.length === 0
    ) {
      phase.value = 'failed'
      pending.value = null
      error.value = {
        code: 'INVALID_RESPONSE',
        message: '服务端未返回有效影响范围，请重试',
        kind: 'preview',
      }
      addAssistant('error', '服务端未返回有效影响范围，请重试')
      return
    }

    if (!draft.value) return
    if (!draft.value.confirmationId) draft.value.confirmationId = crypto.randomUUID()
    pending.value = result
    phase.value = 'awaitingConfirm'
    addAssistant('change', result.summary || '已生成修改方案，请确认')
  }

  async function submit(): Promise<void> {
    if (previewLoading.value || confirmLoading.value || rollbackLoading.value) return
    const text = inputText.value.trim()
    if (!text) return
    const plan = planning.plan
    if (!plan) return

    const keepId =
      draft.value !== null &&
      draft.value.baseVersionId === plan.version_id &&
      draft.value.text === text &&
      sameList(draft.value.lockedPaths, lockedPaths.value)

    draft.value = {
      baseVersionId: plan.version_id,
      etag: plan.etag,
      text,
      lockedPaths: [...lockedPaths.value],
      confirmationId: keepId ? draft.value.confirmationId : null,
    }

    resetConflict()
    error.value = null
    addUser(text)
    previewLoading.value = true
    phase.value = 'previewing'
    try {
      const result = await revisionsApi.preview(plan.trip_id, buildCommand(false))
      if (!result) throw new Error('空响应')
      applyPreview(result)
    } catch (err) {
      const info = toErrorInfo(err)
      if (info.status === 409 && info.code === 'REVISION_BASE_MISMATCH') {
        await enterConflict(info, 'preview')
      } else {
        error.value = { ...info, kind: 'preview' }
        addAssistant('error', info.message)
        phase.value = 'failed'
      }
    } finally {
      previewLoading.value = false
    }
  }

  interface BaseSnapshot {
    versionId: string
    parentVersionId: string | null
    etag: string
    contentHash: string | null
  }

  /** 捕获「修改前版本」信息（须在刷新 plan 之前调用，此时 planning.plan 仍是旧版本）。 */
  function snapshotBase(result: RevisionResult): BaseSnapshot {
    const plan = planning.plan
    return {
      versionId: result.parent_version_id ?? plan?.version_id ?? '',
      parentVersionId: null,
      etag: plan?.etag ?? '',
      contentHash: plan?.content_hash ?? null,
    }
  }

  /** 若时间线尚无该父版本，补一条 kind='base' 占位，使「当前条目的父版本」可回滚。 */
  function ensureBaseEntry(base: BaseSnapshot): void {
    if (!base.versionId) return
    if (timeline.value.some((entry) => entry.versionId === base.versionId)) return
    timeline.value.unshift({
      versionId: base.versionId,
      parentVersionId: base.parentVersionId,
      kind: 'base',
      summary: '修改前版本',
      affectedDays: [],
      changeCount: 0,
      etag: base.etag,
      contentHash: base.contentHash,
      atLocal: localNow(),
      reused: false,
    })
  }

  async function applyConfirmed(result: RevisionResult): Promise<void> {
    if (result.reused) {
      const currentVersionId = planning.plan?.version_id
      if (result.version_id && result.version_id !== currentVersionId) {
        // 丢响应后的重试：服务端早已创建新版本，这里同步 plan 与时间线，未重复扣费。
        const base = snapshotBase(result)
        await refreshPlanAndBalance()
        ensureBaseEntry(base)
        timeline.value.push({
          versionId: result.version_id,
          parentVersionId: result.parent_version_id,
          kind: 'change',
          summary: '已同步原确认结果（未重复扣费）',
          affectedDays: [...(result.affected_days ?? [])],
          changeCount: effectiveChanges(result).length,
          etag: result.etag,
          contentHash: result.content_hash,
          atLocal: localNow(),
          reused: true,
        })
        pending.value = null
        draft.value = null
        inputText.value = ''
        phase.value = 'created'
        addAssistant('result', '已同步原确认结果（未重复扣费）。')
        return
      }

      // 真正的双击重复确认（version_id 与当前一致）：复用原版本，不追加时间线、余额刷新一次。
      // 契约允许 version_id 为 null：此时无法判断服务端版本，只刷新 plan 与余额兜底，不追加时间线。
      phase.value = 'created'
      pending.value = null
      draft.value = null
      lastApplied.value = null
      addAssistant('result', '已复用原版本，未重复扣费。')
      if (result.version_id) {
        await planning.fetchBalance()
      } else {
        await refreshPlanAndBalance()
      }
      return
    }

    const base = snapshotBase(result)
    const changes = effectiveChanges(result)
    const before = beforeDays ?? []
    await refreshPlanAndBalance()
    const status = dayStatus(before, cloneDays(planning.plan), result.affected_days ?? [])

    ensureBaseEntry(base)
    timeline.value.push({
      versionId: result.version_id ?? '',
      parentVersionId: result.parent_version_id,
      kind: 'change',
      summary: result.summary || '已创建新版本',
      affectedDays: [...(result.affected_days ?? [])],
      changeCount: changes.length,
      etag: result.etag,
      contentHash: result.content_hash,
      atLocal: localNow(),
      reused: false,
    })

    lastApplied.value = {
      changes,
      affectedDays: [...(result.affected_days ?? [])],
      verifiedUnchanged: status.verifiedUnchanged,
      unverified: status.unverified,
      beforeDays: cloneDayList(before),
      summary: result.summary || '已创建新版本',
      versionId: result.version_id ?? '',
      versionNo: planning.plan?.version_no ?? 0,
    }

    pending.value = null
    draft.value = null
    inputText.value = ''
    phase.value = 'created'
    addAssistant(
      'result',
      `已创建新版本 v${planning.plan?.version_no ?? ''}（${result.summary || '已创建新版本'}）`,
    )
  }

  async function confirmChange(): Promise<void> {
    if (confirmLoading.value || previewLoading.value || rollbackLoading.value) return
    if (!draft.value || !draft.value.confirmationId) return
    const plan = planning.plan
    if (!plan) return

    confirmLoading.value = true
    phase.value = 'confirming'
    error.value = null
    beforeDays = cloneDays(planning.plan)

    try {
      const result = await revisionsApi.confirm(
        plan.trip_id,
        buildCommand(true),
        draft.value.confirmationId,
      )
      if (!result) throw new Error('空响应')
      await applyConfirmed(result)
    } catch (err) {
      const info = toErrorInfo(err)
      if (info.status === 409 && info.code === 'REVISION_BASE_MISMATCH') {
        await enterConflict(info, 'confirm')
      } else {
        error.value = { ...info, kind: 'confirm' }
        pending.value = null
        addAssistant('error', info.message)
        phase.value = 'failed'
      }
    } finally {
      confirmLoading.value = false
    }
  }

  function declineConfirm(): void {
    if (confirmLoading.value) return
    pending.value = null
    draft.value = null
    phase.value = 'idle'
    addAssistant('result', '已取消本次修改')
  }

  async function applyRollback(result: RevisionResult): Promise<void> {
    const changes = effectiveChanges(result)
    await refreshPlanAndBalance()
    timeline.value.push({
      versionId: result.version_id ?? '',
      parentVersionId: result.parent_version_id,
      kind: 'rollback',
      summary: '以历史版本内容创建新版本',
      affectedDays: [],
      changeCount: changes.length,
      etag: result.etag,
      contentHash: result.content_hash,
      atLocal: localNow(),
      reused: false,
    })
    pending.value = null
    draft.value = null
    lastApplied.value = null
    inputText.value = ''
    phase.value = 'created'
    addAssistant('result', `已以历史版本内容创建新版本 v${planning.plan?.version_no ?? ''}`)
  }

  async function rollback(versionId: string): Promise<void> {
    if (rollbackLoading.value || previewLoading.value || confirmLoading.value) return
    const plan = planning.plan
    if (!plan) return
    if (!timeline.value.some((entry) => entry.versionId === versionId)) return

    rollbackTargetVersionId = versionId
    rollbackLoading.value = true
    error.value = null

    try {
      // 回滚前强制刷新取当前最新 etag（If-Match 是当前版本 etag，不是目标版本 etag）。
      await refreshPlanOnly()
      const currentPlan = planning.plan
      if (!currentPlan) throw new Error('刷新行程失败')
      const result = await revisionsApi.rollback(currentPlan.trip_id, versionId, currentPlan.etag)
      if (!result) throw new Error('空响应')
      await applyRollback(result)
    } catch (err) {
      const info = toErrorInfo(err)
      if (info.status === 409 && info.code === 'REVISION_BASE_MISMATCH') {
        await enterConflict(info, 'rollback')
      } else {
        error.value = { ...info, kind: 'rollback' }
        addAssistant('error', info.message)
        phase.value = 'failed'
      }
    } finally {
      rollbackLoading.value = false
    }
  }

  async function retry(): Promise<void> {
    const current = error.value
    if (!current) return
    if (current.kind === 'confirm') {
      if (draft.value?.confirmationId) {
        await confirmChange()
      } else {
        await submit()
      }
      return
    }
    if (current.kind === 'rollback') {
      if (rollbackTargetVersionId) await rollback(rollbackTargetVersionId)
      return
    }
    await submit()
  }

  async function rePreview(): Promise<void> {
    const text = inputText.value.trim() || draft.value?.text || ''
    resetConflict()
    if (!text) return
    await submit()
  }

  return {
    inputText,
    lockedPaths,
    messages,
    draft,
    pending,
    timeline,
    conflict,
    lastApplied,
    error,
    previewLoading,
    confirmLoading,
    rollbackLoading,
    refreshingPlan,
    phase,
    baseVersion,
    currentVersionId,
    setInput,
    setLockedPaths,
    applyDefaultLockedPaths,
    resetConflict,
    reset,
    submit,
    confirmChange,
    declineConfirm,
    rollback,
    retry,
    rePreview,
  }
})
