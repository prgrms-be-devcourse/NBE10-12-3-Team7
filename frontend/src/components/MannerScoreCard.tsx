'use client'

import { useEffect, useState } from 'react'
import { apiFetch } from '@/lib/apiClient'
import {
  MANNER_SCORE_BAND_COLOR,
  MANNER_SCORE_BAND_GRADIENT,
  MANNER_SCORE_BAND_LABEL,
  mannerScoreBand,
  mannerScoreStatusText,
  type MannerScoreBand,
} from '@/lib/mannerScoreBand'
import { MANNER_SCORE_REASON_LABEL, type MannerScoreReason } from '@/lib/mannerScoreReason'
import styles from './MannerScoreCard.module.css'

interface MannerScoreHistoryItem {
  historyId: number
  changeAmount: number
  reason: MannerScoreReason
  relatedReportId: number | null
  createdAt: string
}

type CardStatus = 'loading' | 'ready' | 'error'

const BANDS: MannerScoreBand[] = ['DANGER', 'WARNING', 'DEFAULT', 'GOOD']

function formatDateTime(iso: string) {
  const d = new Date(iso)
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, '0')}.${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

export default function MannerScoreCard() {
  const [status, setStatus] = useState<CardStatus>('loading')
  const [score, setScore] = useState<number | null>(null)
  const [history, setHistory] = useState<MannerScoreHistoryItem[]>([])

  useEffect(() => {
    let cancelled = false

    apiFetch('/api/members/me')
      .then(async res => {
        const meData = await res.json().catch(() => null)
        const myMemberId = meData?.data?.memberId
        if (!res.ok || myMemberId == null) { if (!cancelled) setStatus('error'); return }

        const [scoreRes, historyRes] = await Promise.all([
          fetch(`/api/members/${myMemberId}/manner-score`).then(r => r.ok ? r.json() : null),
          apiFetch('/api/members/me/manner-score/history').then(r => r.ok ? r.json() : null),
        ])
        if (cancelled) return
        if (scoreRes?.data?.score == null) { setStatus('error'); return }
        setScore(scoreRes.data.score)
        setHistory(historyRes?.data ?? [])
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) setStatus('error')
      })

    return () => { cancelled = true }
  }, [])

  if (status === 'loading') {
    return <div className={styles.card}><p className={styles.msg}>매너온도를 불러오는 중...</p></div>
  }
  if (status === 'error' || score == null) {
    return <div className={styles.card}><p className={styles.msg}>매너온도를 불러오지 못했습니다.</p></div>
  }

  const band = mannerScoreBand(score)
  const [tintFrom, tintTo] = MANNER_SCORE_BAND_GRADIENT[band]
  const fillPct = Math.min(100, Math.max(0, score))

  return (
    <div className={styles.card}>
      <div className={styles.top}>
        <div>
          <div className={styles.num}>
            {score.toFixed(1)}
            <small>°</small>
          </div>
          <div className={styles.status} style={{ color: MANNER_SCORE_BAND_COLOR[band] }}>
            {mannerScoreStatusText(score)}
          </div>
        </div>
        <div className={styles.legend}>
          기본 온도 36.5°<br />
          최근 30일 정상 거래 시 자동 회복
        </div>
      </div>

      <div className={styles.gaugeWrap}>
        <div className={styles.gaugeOuter}>
          <div
            className={styles.gaugeGradient}
            style={{ background: `linear-gradient(to right, ${tintFrom}, ${tintTo})` }}
          />
          <div className={styles.gaugeMask} style={{ width: `${100 - fillPct}%` }} />
          <div className={styles.gaugeTick} style={{ left: '36.5%' }} />
        </div>
        <div className={styles.gaugeTicks}>
          <span className={styles.gaugeTickLabel} style={{ left: '0%' }}>0</span>
          <span className={styles.gaugeTickLabel} style={{ left: '20%' }}>20</span>
          <span className={styles.gaugeTickLabel} style={{ left: '36.5%' }}>36.5(기본)</span>
          <span className={styles.gaugeTickLabel} style={{ left: '100%' }}>100</span>
        </div>
        <div className={styles.legendKey}>
          {BANDS.map(b => (
            <span key={b}>
              <span className={styles.dot} style={{ background: MANNER_SCORE_BAND_COLOR[b] }} />
              {MANNER_SCORE_BAND_LABEL[b]}
            </span>
          ))}
        </div>
      </div>

      <div className={styles.timeline}>
        <div className={styles.timelineTitle}>변화 이력</div>
        {history.length === 0 ? (
          <p className={styles.msg}>아직 변화 이력이 없어요.</p>
        ) : (
          <ul className={styles.timelineList}>
            {history.map(item => {
              const isUp = item.changeAmount > 0
              return (
                <li key={item.historyId} className={styles.item}>
                  <span className={`${styles.dot2} ${isUp ? styles.dotUp : styles.dotDown}`} />
                  <div className={styles.itemBody}>
                    <div className={styles.reason}>{MANNER_SCORE_REASON_LABEL[item.reason] ?? item.reason}</div>
                    <div className={styles.meta}>
                      <span>{formatDateTime(item.createdAt)}</span>
                      {item.relatedReportId != null && <span>· 관련 신고 #{item.relatedReportId}</span>}
                    </div>
                  </div>
                  <span className={`${styles.delta} ${isUp ? styles.deltaUp : styles.deltaDown}`}>
                    {isUp ? '+' : ''}{item.changeAmount.toFixed(1)}
                  </span>
                </li>
              )
            })}
          </ul>
        )}
      </div>
    </div>
  )
}
