// 规划 store：表单输入、报价结果、当前任务、事件列表、lastSequenceNo、错误与降级。
// 前端不计算积分价格/余额：余额、报价、预扣分别来自 /points/balance、/points/quotes、TaskView.reserved_points。

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import { toErrorInfo } from '@/api/http'
import type { ErrorInfo } from '@/api/http'
import * as pointsApi from '@/api/points'
import { isTerminalStatus } from '@/api/taskStatus'
import * as tasksApi from '@/api/tasks'
import type {
  CreateTaskRequest,
  PlanView,
  PlanningRequestPayload,
  QuoteResponse,
  TaskEvent,
  TaskView,
} from '@/api/types'
import { acceptEvents } from '@/composables/sseParser'

export interface PlanningFormState {
  city: string
  startDate: string
  endDate: string
  partySize: number
  /** 预算总额（元，整数），提交时换算为 currency/amount_minor。 */
  budgetYuan: number
  mustVisit: string[]
  preference: string
  transport: string
  planCount: number
}

/** 元 → 最小单位（CNY 分）。仅整元输入，避免浮点误差。 */
export function yuanToAmountMinor(yuan: number): number {
  return Math.round(yuan) * 100
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID()
}

export const defaultPlanningForm: PlanningFormState = {
  city: '北京',
  startDate: '',
  endDate: '',
  partySize: 2,
  budgetYuan: 3000,
  mustVisit: [],
  preference: '',
  transport: '混合',
  planCount: 1,
}

export function buildCreateTaskRequest(form: PlanningFormState): CreateTaskRequest {
  const payload: PlanningRequestPayload = {
    city: form.city,
    start_date: form.startDate,
    end_date: form.endDate,
    days: 3,
    party_size: form.partySize,
    budget: { currency: 'CNY', amount_minor: yuanToAmountMinor(form.budgetYuan) },
    must_visit: [...form.mustVisit],
    preferences: form.preference,
    transport: form.transport,
    plan_count: form.planCount,
  }
  return { task_type: 'GENERATION', plan_count: form.planCount, request: payload }
}

export const usePlanningStore = defineStore('planning', () => {
  const form = ref<PlanningFormState>({ ...defaultPlanningForm, mustVisit: [] })

  const quote = ref<QuoteResponse | null>(null)
  const quoteError = ref<ErrorInfo | null>(null)
  const quoteLoading = ref(false)

  const task = ref<TaskView | null>(null)
  const taskError = ref<ErrorInfo | null>(null)
  const taskLoading = ref(false)

  const plan = ref<PlanView | null>(null)
  const planError = ref<ErrorInfo | null>(null)
  const planLoading = ref(false)

  const events = ref<TaskEvent[]>([])
  const lastSequenceNo = ref(0)
  const streamConnected = ref(false)
  const degradedFields = ref<string[]>([])

  const balancePoints = ref<number | null>(null)
  const balanceError = ref<ErrorInfo | null>(null)

  const cancelling = ref(false)
  const cancelError = ref<ErrorInfo | null>(null)

  let quoteKey: string | null = null
  let quotedPlanCount: number | null = null
  let taskKey: string | null = null
  let submittedPayloadJson: string | null = null

  /** 实时状态：终态以 TaskView.status 为准；否则以最新事件状态为准，再回退 TaskView.status。 */
  const activeStatus = computed<string | null>(() => {
    if (task.value && isTerminalStatus(task.value.status)) return task.value.status
    const latest = events.value[events.value.length - 1]
    return latest?.status ?? task.value?.status ?? null
  })

  function setForm(partial: Partial<PlanningFormState>): void {
    form.value = { ...form.value, ...partial }
  }

  function resetPlanning(): void {
    quote.value = null
    quoteError.value = null
    task.value = null
    taskError.value = null
    events.value = []
    lastSequenceNo.value = 0
    degradedFields.value = []
    cancelError.value = null
    plan.value = null
    planError.value = null
  }

  function applyEvent(event: TaskEvent): void {
    const accepted = acceptEvents([event], lastSequenceNo.value)
    if (accepted.length === 0) return
    events.value.push(...accepted)
    lastSequenceNo.value = accepted[accepted.length - 1]?.sequence_no ?? lastSequenceNo.value
    for (const item of accepted) {
      for (const field of item.degraded_fields ?? []) {
        if (!degradedFields.value.includes(field)) degradedFields.value.push(field)
      }
    }
  }

  async function requestQuote(): Promise<void> {
    quoteLoading.value = true
    quoteError.value = null
    if (quoteKey === null || quotedPlanCount !== form.value.planCount) {
      quoteKey = newIdempotencyKey()
      quotedPlanCount = form.value.planCount
    }
    try {
      const result = await pointsApi.quote({ planCount: form.value.planCount }, quoteKey)
      quote.value = result ?? null
    } catch (error) {
      quoteError.value = toErrorInfo(error)
      quote.value = null
      throw error
    } finally {
      quoteLoading.value = false
    }
  }

  async function createTask(forceNewKey = false): Promise<void> {
    if (taskLoading.value) return
    taskLoading.value = true
    taskError.value = null
    const request = buildCreateTaskRequest(form.value)
    const payloadJson = JSON.stringify(request)
    if (forceNewKey || taskKey === null || submittedPayloadJson !== payloadJson) {
      taskKey = newIdempotencyKey()
      submittedPayloadJson = payloadJson
    }
    try {
      const created = await tasksApi.create(request, taskKey)
      task.value = created ?? null
      events.value = []
      lastSequenceNo.value = 0
      degradedFields.value = []
      cancelError.value = null
      plan.value = null
      planError.value = null
    } catch (error) {
      taskError.value = toErrorInfo(error)
      throw error
    } finally {
      taskLoading.value = false
    }
  }

  async function refreshTask(): Promise<boolean> {
    if (!task.value) return false
    try {
      const fresh = await tasksApi.get(task.value.task_id)
      task.value = fresh ?? task.value
      if (task.value && isTerminalStatus(task.value.status)) {
        await fetchPlan()
      }
      return true
    } catch (error) {
      taskError.value = toErrorInfo(error)
      return false
    }
  }

  async function fetchPlan(): Promise<void> {
    if (planLoading.value) return
    if (!task.value || !isTerminalStatus(task.value.status)) return
    planLoading.value = true
    planError.value = null
    try {
      plan.value = (await tasksApi.getPlan(task.value.task_id)) ?? null
    } catch (error) {
      plan.value = null
      planError.value = toErrorInfo(error)
    } finally {
      planLoading.value = false
    }
  }

  async function cancelTask(): Promise<void> {
    if (!task.value) return
    cancelling.value = true
    cancelError.value = null
    try {
      // 取消前先刷新取最新 state_version（后端每次状态变更 +1，cancel 校验 expected_version）。
      await refreshTask()
      if (!task.value) return
      const cancelled = await tasksApi.cancel(
        task.value.task_id,
        { expected_version: task.value.state_version },
        newIdempotencyKey(),
      )
      task.value = cancelled ?? task.value
    } catch (error) {
      const info = toErrorInfo(error)
      if (info.status === 409) {
        // 版本已变化：再刷新同步最新状态；按刷新结果给不同文案，不说谎。
        const synced = await refreshTask()
        cancelError.value = {
          code: info.code,
          message: synced
            ? '状态已变化，已同步，请重试'
            : '状态已变化，且同步失败，请检查网络后重试',
          status: 409,
          traceId: info.traceId,
        }
      } else {
        cancelError.value = info
      }
      throw error
    } finally {
      cancelling.value = false
    }
  }

  async function fetchBalance(): Promise<void> {
    balanceError.value = null
    try {
      const result = await pointsApi.balance()
      balancePoints.value = result?.balancePoints ?? null
    } catch (error) {
      balanceError.value = toErrorInfo(error)
    }
  }

  return {
    form,
    quote,
    quoteError,
    quoteLoading,
    task,
    taskError,
    taskLoading,
    events,
    lastSequenceNo,
    streamConnected,
    degradedFields,
    plan,
    planError,
    planLoading,
    balancePoints,
    balanceError,
    cancelling,
    cancelError,
    activeStatus,
    setForm,
    resetPlanning,
    applyEvent,
    requestQuote,
    createTask,
    refreshTask,
    cancelTask,
    fetchBalance,
    fetchPlan,
  }
})
