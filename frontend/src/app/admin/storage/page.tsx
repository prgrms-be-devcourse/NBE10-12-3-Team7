'use client'

import { useEffect, useRef, useState } from 'react'
import { getAccessToken } from '@/lib/auth'
import styles from '../admin.module.css'

interface OrphanFile {
  directory: string
  filename: string
  sizeBytes: number
  lastModified: string
  ageHours: number
}

interface OrphanScanResponse {
  orphans: OrphanFile[]
  totalCount: number
  totalBytes: number
  graceHours: number
}

type Status = 'loading' | 'ready' | 'error'

const DEFAULT_GRACE_HOURS = '24'

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function fileKey(directory: string, filename: string) {
  return `${directory}#${filename}`
}

export default function AdminStoragePage() {
  const [status, setStatus] = useState<Status>(() => (getAccessToken() ? 'loading' : 'error'))
  const [scan, setScan] = useState<OrphanScanResponse | null>(null)
  const [graceHoursInput, setGraceHoursInput] = useState(DEFAULT_GRACE_HOURS)
  const [appliedGraceHours, setAppliedGraceHours] = useState(DEFAULT_GRACE_HOURS)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)

  const [toastText, setToastText] = useState('')
  const [toastOn, setToastOn] = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  function showToast(msg: string) {
    setToastText(msg); setToastOn(true)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => setToastOn(false), 2200)
  }

  function loadOrphans() {
    const token = getAccessToken()
    if (!token) return
    fetch(`/api/admin/storage/orphans?graceHours=${encodeURIComponent(appliedGraceHours)}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then(r => r.json())
      .then(data => {
        setScan(data?.data ?? null)
        setSelected(new Set())
        setStatus('ready')
      })
      .catch(() => setStatus('error'))
  }

  useEffect(() => {
    loadOrphans()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [appliedGraceHours])

  function applyGraceHours() {
    const trimmed = graceHoursInput.trim()
    setStatus('loading')
    setAppliedGraceHours(trimmed || DEFAULT_GRACE_HOURS)
  }

  function toggleSelected(directory: string, filename: string) {
    const key = fileKey(directory, filename)
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  function toggleSelectAll() {
    if (!scan) return
    if (selected.size === scan.orphans.length) {
      setSelected(new Set())
    } else {
      setSelected(new Set(scan.orphans.map(o => fileKey(o.directory, o.filename))))
    }
  }

  async function deleteSelected() {
    if (!scan || selected.size === 0) return
    if (!window.confirm(`선택한 ${selected.size}개 파일을 삭제할까요? 되돌릴 수 없어요.`)) return

    const token = getAccessToken()
    if (!token) return
    const targets = scan.orphans
      .filter(o => selected.has(fileKey(o.directory, o.filename)))
      .map(o => ({ directory: o.directory, filename: o.filename }))

    setDeleting(true)
    try {
      const res = await fetch(`/api/admin/storage/orphans?graceHours=${encodeURIComponent(appliedGraceHours)}`, {
        method: 'DELETE',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ targets }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) {
        showToast(data?.message ?? '삭제 중 오류가 발생했어요.')
        return
      }
      const result = data.data
      showToast(`${result.deleted}개 삭제, ${result.skipped}개 건너뜀`)
      loadOrphans()
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    } finally {
      setDeleting(false)
    }
  }

  if (status === 'loading') return <div className={styles.empty}><p>불러오는 중...</p></div>
  if (status === 'error' || !scan) return <div className={styles.empty}><p>고아파일 목록을 불러오지 못했어요.</p></div>

  const allSelected = scan.orphans.length > 0 && selected.size === scan.orphans.length

  return (
    <>
      <div className={styles.ptitle}>저장소 고아파일 정리</div>
      <div className={styles.pdesc}>DB가 더 이상 참조하지 않는 업로드 파일을 찾아 정리합니다.</div>

      <div className={`${styles.statGrid} ${styles.statGridTwo}`}>
        <div className={styles.stat}>
          <div className={styles.statL}>고아파일 개수</div>
          <div className={styles.statN}>{scan.totalCount}개</div>
        </div>
        <div className={styles.stat}>
          <div className={styles.statL}>총 용량</div>
          <div className={styles.statN}>{formatBytes(scan.totalBytes)}</div>
        </div>
      </div>

      <div className={styles.panel}>
        <div className={styles.filter}>
          <div className={styles.field}>
            <label>유예 시간(시간)</label>
            <input
              type="number"
              step="1"
              value={graceHoursInput}
              onChange={e => setGraceHoursInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && applyGraceHours()}
            />
          </div>
          <button type="button" className={`btn ${styles.filterBtn}`} onClick={applyGraceHours}>조회</button>
          <button
            type="button"
            className={`btn danger ${styles.filterBtn}`}
            onClick={deleteSelected}
            disabled={selected.size === 0 || deleting}
          >
            {deleting ? '삭제 중...' : `선택 삭제 (${selected.size})`}
          </button>
        </div>
        <div className={styles.hint} style={{ marginTop: -12, marginBottom: 18 }}>이 시간 이내에 수정된 파일은 업로드 진행 중일 수 있어 제외해요.</div>

        {scan.orphans.length === 0 ? (
          <div className={styles.empty}><p>고아파일이 없어요.</p></div>
        ) : (
          <div className={styles.tablewrap}>
            <table>
              <thead>
                <tr>
                  <th><input type="checkbox" checked={allSelected} onChange={toggleSelectAll} /></th>
                  <th>디렉터리</th>
                  <th>파일명</th>
                  <th>용량</th>
                  <th>최종 수정</th>
                  <th>경과 시간</th>
                </tr>
              </thead>
              <tbody>
                {scan.orphans.map(o => {
                  const key = fileKey(o.directory, o.filename)
                  return (
                    <tr key={key}>
                      <td>
                        <input
                          type="checkbox"
                          checked={selected.has(key)}
                          onChange={() => toggleSelected(o.directory, o.filename)}
                        />
                      </td>
                      <td>{o.directory}</td>
                      <td>{o.filename}</td>
                      <td>{formatBytes(o.sizeBytes)}</td>
                      <td>{o.lastModified.slice(0, 10)}</td>
                      <td>{o.ageHours}시간 전</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>
    </>
  )
}
