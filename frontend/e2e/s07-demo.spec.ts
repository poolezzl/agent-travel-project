import { mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { expect, test } from '@playwright/test'

// 默认写入 frontend/test-results/s07-artifacts/（已被根 .gitignore 的 test-results/ 覆盖）。
// 需要人工刷新证据时，用 S07_ARTIFACTS_DIR 指向 docs/progress/s07-artifacts/ 覆盖。
const defaultArtifactDir = fileURLToPath(new URL('../test-results/s07-artifacts/', import.meta.url))
const artifactDir = process.env.S07_ARTIFACTS_DIR ?? defaultArtifactDir
mkdirSync(artifactDir, { recursive: true })

test.describe('S07 开题演示页（Mock）', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
  })

  test('持续显示演示声明并渲染全部关键区块', async ({ page }) => {
    const notice = page.getByTestId('demo-notice')
    await expect(notice).toBeVisible()
    await expect(notice).toContainText('演示数据，未接入真实接口')

    for (const testId of [
      'planning-form',
      'mock-progress',
      'envelope-panel',
      'input-summary',
      'schedule-timeline',
      'map-placeholder',
      'plan-comparison',
      'community-samples',
    ]) {
      await expect(page.getByTestId(testId)).toBeVisible()
    }

    await expect(page.getByTestId('current-status')).toContainText('SUCCESS')
    await expect(page.getByTestId('map-placeholder')).toContainText('未接入地图服务')
    await expect(page.getByTestId('envelope-panel')).toContainText('有效期至')
    await expect(page.getByTestId('scenario-note')).toHaveCount(0)
  })

  test('桌面与 320px 窄屏都没有横向溢出', async ({ page }) => {
    const assertNoHorizontalOverflow = async (): Promise<void> => {
      const overflows = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
      )
      expect(overflows).toBe(false)
    }

    await assertNoHorizontalOverflow()

    await page.setViewportSize({ width: 320, height: 720 })
    await assertNoHorizontalOverflow()
    await expect(page.getByTestId('demo-notice')).toBeVisible()
    await expect(page.getByTestId('schedule-timeline')).toBeVisible()
  })

  test('键盘可以聚焦并激活提交按钮，驱动本地模拟进度', async ({ page }) => {
    const submit = page.getByRole('button', { name: '生成演示行程' })

    // 先切到空态，确保后面的成功态确实由键盘提交触发，而不是初始默认态。
    await page.getByText('空状态 · Mock').click()
    await expect(page.getByTestId('schedule-timeline')).toBeHidden()

    await submit.focus()
    await expect(submit).toBeFocused()
    await page.keyboard.press('Enter')

    await expect(page.getByTestId('loading-skeleton')).toBeVisible()
    await expect(page.getByTestId('current-status')).toContainText('SUCCESS', { timeout: 20_000 })
    await expect(page.getByTestId('schedule-timeline')).toBeVisible()
  })

  test('Mock 状态选择器可切换成功/加载/空/部分/错误', async ({ page }) => {
    await page.getByText('加载中 · Mock').click()
    await expect(page.getByTestId('loading-skeleton')).toBeVisible()

    await page.getByText('空状态 · Mock').click()
    await expect(page.getByTestId('schedule-timeline')).toBeHidden()
    await expect(page.getByTestId('result-state')).toContainText('还没有可展示的演示行程')

    await page.getByText('部分完成 · Mock').click()
    await expect(page.getByTestId('result-state')).toContainText('部分完成')
    await expect(page.getByTestId('envelope-degraded')).toContainText('provider_fields')

    await page.getByText('错误 · Mock').click()
    await expect(page.getByTestId('result-state')).toContainText('AGENT_UNAVAILABLE')

    await page.getByText('成功 · Mock').click()
    await expect(page.getByTestId('schedule-timeline')).toBeVisible()
  })

  test('生成桌面与移动端截图证据', async ({ page }, testInfo) => {
    await expect(page.getByTestId('schedule-timeline')).toBeVisible()
    await page.screenshot({
      path: `${artifactDir}${testInfo.project.name}-success.png`,
      fullPage: true,
    })

    await page.getByText('加载中 · Mock').click()
    await page.screenshot({
      path: `${artifactDir}${testInfo.project.name}-loading.png`,
      fullPage: true,
    })

    await page.getByText('空状态 · Mock').click()
    await page.screenshot({
      path: `${artifactDir}${testInfo.project.name}-empty.png`,
      fullPage: true,
    })

    await page.getByText('部分完成 · Mock').click()
    await page.screenshot({
      path: `${artifactDir}${testInfo.project.name}-partial.png`,
      fullPage: true,
    })

    await page.getByText('错误 · Mock').click()
    await page.screenshot({
      path: `${artifactDir}${testInfo.project.name}-error.png`,
      fullPage: true,
    })
  })
})
