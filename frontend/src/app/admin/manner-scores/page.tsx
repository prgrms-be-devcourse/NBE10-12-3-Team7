'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { getAccessToken } from '@/lib/auth'
import { MANNER_SCORE_BAND_COLOR, MANNER_SCORE_BAND_LABEL, mannerScoreBand } from '@/lib/mannerScoreBand'
import styles from '../admin.module.css'

interface LowTrustMember {
  memberId: number
  nickname: string
  email: string
  score: number
}

type Status = 'loading' | 'ready' | 'error'

const DEFAULT_THRESHOLD = '20.0'

export default function AdminMannerScoresPage() {
  const [status, setStatus] = useState<Status>(() => (getAccessToken() ? 'loading' : 'error'))
  const [members, setMembers] = useState<LowTrustMember[]>([])
  const [thresholdInput, setThresholdInput] = useState(DEFAULT_THRESHOLD)
  const [appliedThreshold, setAppliedThreshold] = useState(DEFAULT_THRESHOLD)

  useEffect(() => {
    const token = getAccessToken()
    if (!token) return
    let cancelled = false
    fetch(`/api/admin/manner-scores?threshold=${encodeURIComponent(appliedThreshold)}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then(r => r.json())
      .then(data => { if (!cancelled) { setMembers(data?.data ?? []); setStatus('ready') } })
      .catch(() => { if (!cancelled) setStatus('error') })
    return () => { cancelled = true }
  }, [appliedThreshold])

  function applyThreshold() {
    const trimmed = thresholdInput.trim()
    setStatus('loading')
    setAppliedThreshold(trimmed || DEFAULT_THRESHOLD)
  }

  if (status === 'loading') return <div className={styles.empty}><p>불러오는 중...</p></div>
  if (status === 'error') return <div className={styles.empty}><p>저신뢰 회원 목록을 불러오지 못했어요.</p></div>

  return (
    <>
      <div className={styles.ptitle}>저신뢰 회원 모니터링</div>
      <div className={styles.pdesc}>매너온도가 기준치 이하인 회원을 낮은 순으로 조회합니다.</div>
      <div className={styles.panel}>
        <div className={styles.filter}>
          <div className={styles.field}>
            <label>매너온도 기준치 이하</label>
            <input
              type="number"
              step="0.1"
              value={thresholdInput}
              onChange={e => setThresholdInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && applyThreshold()}
            />
          </div>
          <button type="button" className={`btn ${styles.filterBtn}`} onClick={applyThreshold}>조회</button>
        </div>
        <div className={styles.hint} style={{ marginTop: -10, marginBottom: 18 }}>기본값 20.0 — 값을 바꾸고 조회를 누르면 다시 검색해요.</div>

        {members.length === 0 ? (
          <div className={styles.empty}><p>기준치 이하인 회원이 없어요.</p></div>
        ) : (
          <div className={styles.tablewrap}>
            <table>
              <thead>
                <tr><th>회원ID</th><th>닉네임</th><th>이메일</th><th>매너온도</th><th>관리</th></tr>
              </thead>
              <tbody>
                {members.map(m => {
                  const band = mannerScoreBand(m.score)
                  return (
                    <tr key={m.memberId}>
                      <td>{m.memberId}</td>
                      <td>{m.nickname}</td>
                      <td>{m.email}</td>
                      <td>
                        <span
                          className={styles.tag}
                          style={{ background: `${MANNER_SCORE_BAND_COLOR[band]}22`, color: MANNER_SCORE_BAND_COLOR[band] }}
                        >
                          {m.score.toFixed(1)}° · {MANNER_SCORE_BAND_LABEL[band]}
                        </span>
                      </td>
                      <td><Link href={`/admin/members/${m.memberId}`} className="btn ghost">회원 상세</Link></td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}
