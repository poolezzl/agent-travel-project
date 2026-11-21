import { beforeEach, describe, expect, it, vi } from 'vitest'

import { confirm, preview, rollback } from './revisions'

vi.mock('./http', () => ({
  request: vi.fn(),
}))

import { request } from './http'

const command = {
  base_version_id: 'version-1',
  etag: 'base-etag',
  revision_request: '换景点',
  locked_paths: ['/days/1/segments/0'],
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('revisions api', () => {
  it('preview 使用 POST /preview 并透传命令载荷', async () => {
    vi.mocked(request).mockResolvedValue(undefined)
    await preview('trip-1', command)

    expect(request).toHaveBeenCalledWith('/api/v1/trips/trip-1/revisions/preview', {
      method: 'POST',
      body: command,
    })
  })

  it('confirm 使用 POST /confirm 且 Idempotency-Key 与 confirmation_id 一致', async () => {
    vi.mocked(request).mockResolvedValue(undefined)
    await confirm('trip-1', { ...command, confirmed: true, confirmation_id: 'cid-1' }, 'cid-1')

    expect(request).toHaveBeenCalledWith('/api/v1/trips/trip-1/revisions/confirm', {
      method: 'POST',
      body: { ...command, confirmed: true, confirmation_id: 'cid-1' },
      idempotencyKey: 'cid-1',
    })
  })

  it('rollback 使用 POST /rollback/{versionId} 且携带 If-Match 头', async () => {
    vi.mocked(request).mockResolvedValue(undefined)
    await rollback('trip-1', 'target-1', 'current-etag')

    expect(request).toHaveBeenCalledWith('/api/v1/trips/trip-1/revisions/rollback/target-1', {
      method: 'POST',
      headers: { 'If-Match': 'current-etag' },
    })
  })
})
