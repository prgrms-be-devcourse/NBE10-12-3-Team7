'use client'

import { useEffect, useRef, useState } from 'react'
import { apiFetch } from '@/lib/apiClient'
import { REPORT_REASON_LABEL, type ReportReason } from '@/lib/reportReasons'
import MannerScoreCard from '@/components/MannerScoreCard'
import ReportDetailModal from '@/components/ReportDetailModal'
import ConfirmModal from '@/components/ConfirmModal'
import styles from './page.module.css'

type ReportType = 'PRODUCT' | 'MEMBER'
type ReportStatus = 'RECEIVED' | 'REVIEWING' | 'COMPLETED' | 'REJECTED'
type FilterTab = '전체' | ReportStatus
type PageStatus = 'loading' | 'ready' | 'error'

interface MyReport {
  reportId: number
  reportType: ReportType
  targetId: number
  reason: ReportReason
  status: ReportStatus
  evidenceImageUrl: string | null
  createdAt: string
}

const TABS: { key: FilterTab; label: string }[] = [
  { key: '전체', label: '전체' },
  { key: 'RECEIVED', label: '접수' },
  { key: 'REVIEWING', label: '처리중' },
  { key: 'COMPLETED', label: '처리완료' },
  { key: 'REJECTED', label: '반려' },
]

function typeTag(type: ReportType) {
  return type === 'MEMBER'
    ? { cls: styles.tagMember, label: '사용자 신고', icon: '👤' }
    : { cls: styles.tagProduct, label: '상품 신고', icon: '📦' }
}

function statusTag(status: ReportStatus) {
  if (status === 'RECEIVED') return { cls: styles.tagReceived, label: '접수' }
  if (status === 'REVIEWING') return { cls: styles.tagProcessing, label: '처리중' }
  if (status === 'REJECTED') return { cls: styles.tagRejected, label: '반려' }
  return { cls: styles.tagDone, label: '처리완료' }
}

function formatDate(iso: string) {
  return iso.slice(0, 10)
}

function targetLabelOf(report: MyReport, productTitles: Record<number, string>) {
  return report.reportType === 'MEMBER'
    ? `회원 #${report.targetId}`
    : (productTitles[report.targetId] ?? `상품 #${report.targetId}`)
}

function formatRelative(iso: string) {
  const diffMs = Date.now() - new Date(iso).getTime()
  const diffMin = Math.floor(diffMs / 60000)
  if (diffMin < 1) return '방금 전'
  if (diffMin < 60) return `${diffMin}분 전`
  const diffHour = Math.floor(diffMin / 60)
  if (diffHour < 24) return `${diffHour}시간 전`
  const diffDay = Math.floor(diffHour / 24)
  if (diffDay < 7) return `${diffDay}일 전`
  return `${formatDate(iso)} 접수`
}

const STATUS_ORDER: ReportStatus[] = ['RECEIVED', 'REVIEWING', 'COMPLETED', 'REJECTED']
const STATUS_LABEL: Record<ReportStatus, string> = {
  RECEIVED: '접수', REVIEWING: '처리중', COMPLETED: '처리완료', REJECTED: '반려',
}
/* 접수/처리중/반려는 상품목록 배지(나눔/예약중/판매중)와 동일 색상으로 맞춘다. */
const STATUS_COLOR_VAR: Record<ReportStatus, string> = {
  RECEIVED: '#1f9d57', REVIEWING: '#2f77e0', COMPLETED: '#3a3a3a', REJECTED: '#ec1451',
}
const STEPS: ReportStatus[] = ['RECEIVED', 'REVIEWING', 'COMPLETED']

function buildDonutGradient(counts: Record<ReportStatus, number>, total: number) {
  if (total === 0) return 'var(--surface-2)'
  let acc = 0
  const stops: string[] = []
  for (const key of STATUS_ORDER) {
    const c = counts[key]
    if (c === 0) continue
    const start = (acc / total) * 100
    acc += c
    const end = (acc / total) * 100
    stops.push(`${STATUS_COLOR_VAR[key]} ${start}% ${end}%`)
  }
  return `conic-gradient(${stops.join(', ')})`
}

function AnimatedNumber({ value }: { value: number }) {
  const [display, setDisplay] = useState(0)
  useEffect(() => {
    const dur = 900
    const t0 = Date.now()
    const ease = (x: number) => 1 - Math.pow(1 - x, 3)
    const timer = setInterval(() => {
      const p = Math.min(1, (Date.now() - t0) / dur)
      setDisplay(Math.round(value * ease(p)))
      if (p >= 1) clearInterval(timer)
    }, 33)
    return () => clearInterval(timer)
  }, [value])
  return <>{display.toLocaleString('ko-KR')}</>
}

function StatusStepper({ status }: { status: ReportStatus }) {
  if (status === 'REJECTED') {
    return <span className={styles.stepperRejected}>반려됨</span>
  }
  const idx = STEPS.indexOf(status)
  return (
    <div className={styles.stepper} aria-hidden="true">
      {STEPS.map((s, i) => (
        <span key={s} className={styles.stepperSeg}>
          <span className={`${styles.stepperDot}${i <= idx ? ' ' + styles.stepperDotOn : ''}`} />
          {i < STEPS.length - 1 && (
            <span className={`${styles.stepperLine}${i < idx ? ' ' + styles.stepperLineOn : ''}`} />
          )}
        </span>
      ))}
    </div>
  )
}

export default function MyReportsPage() {
  const [reports, setReports] = useState<MyReport[]>([])
  const [filter, setFilter] = useState<FilterTab>('전체')
  const [status, setStatus] = useState<PageStatus>('loading')
  const [errorMsg, setErrorMsg] = useState('')
  const [cancellingId, setCancellingId] = useState<number | null>(null)
  const [cancelTargetId, setCancelTargetId] = useState<number | null>(null)
  const [productTitles, setProductTitles] = useState<Record<number, string>>({})
  const [detailReportId, setDetailReportId] = useState<number | null>(null)

  const [toastText, setToastText] = useState('')
  const [toastOn, setToastOn] = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  function showToast(msg: string) {
    setToastText(msg); setToastOn(true)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => setToastOn(false), 1900)
  }

  async function cancelReport(reportId: number) {
    setCancelTargetId(null)
    setCancellingId(reportId)
    try {
      const res = await apiFetch(`/api/members/me/reports/${reportId}`, { method: 'DELETE' })
      if (!res.ok) {
        const data = await res.json().catch(() => null)
        showToast(data?.message ?? '취소 중 오류가 발생했습니다.')
        return
      }
      setReports(prev => prev.filter(r => r.reportId !== reportId))
      showToast('신고를 취소했어요')
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    } finally {
      setCancellingId(null)
    }
  }

  useEffect(() => {
    let cancelled = false

    apiFetch('/api/members/me/reports')
      .then(async res => {
        const data = await res.json().catch(() => null)
        if (!res.ok) throw new Error(data?.message ?? '신고 내역을 불러오지 못했습니다.')
        if (!cancelled) {
          setReports(data?.data ?? [])
          setStatus('ready')
        }
      })
      .catch(err => {
        if (cancelled) return
        setErrorMsg(err instanceof Error ? err.message : '신고 내역을 불러오지 못했습니다.')
        setStatus('error')
      })

    return () => { cancelled = true }
  }, [])

  /* 상품 신고의 대상 이름(상품명)을 조회한다. 회원 신고는 닉네임을 조회할 공개 API가 없어 ID만 표시한다. */
  useEffect(() => {
    const productIds = Array.from(new Set(
      reports.filter(r => r.reportType === 'PRODUCT').map(r => r.targetId)
    ))
    if (productIds.length === 0) return
    let cancelled = false

    Promise.all(productIds.map(id =>
      fetch(`/api/products/${id}`)
        .then(res => res.ok ? res.json() : null)
        .catch(() => null)
        .then(data => [id, data?.data?.title as string | undefined] as const)
    )).then(entries => {
      if (cancelled) return
      setProductTitles(prev => {
        const next = { ...prev }
        for (const [id, title] of entries) {
          if (title) next[id] = title
        }
        return next
      })
    })

    return () => { cancelled = true }
  }, [reports])

  const filtered = filter === '전체' ? reports : reports.filter(r => r.status === filter)

  const counts: Record<ReportStatus, number> = {
    RECEIVED: reports.filter(r => r.status === 'RECEIVED').length,
    REVIEWING: reports.filter(r => r.status === 'REVIEWING').length,
    COMPLETED: reports.filter(r => r.status === 'COMPLETED').length,
    REJECTED: reports.filter(r => r.status === 'REJECTED').length,
  }
  const total = reports.length
  const donutBg = buildDonutGradient(counts, total)

  const reasonCounts: Partial<Record<ReportReason, number>> = {}
  for (const r of reports) reasonCounts[r.reason] = (reasonCounts[r.reason] ?? 0) + 1
  const topReasonEntry = (Object.entries(reasonCounts) as [ReportReason, number][])
    .sort((a, b) => b[1] - a[1])[0]

  const productCount = reports.filter(r => r.reportType === 'PRODUCT').length
  const productPct = total > 0 ? Math.round((productCount / total) * 100) : 0
  const memberPct = total > 0 ? 100 - productPct : 0

  return (
    <main className={styles.wrap}>
      {/* 헤더 */}
      <div className={styles.headRow}>
        <h1>신고내역</h1>
        <p>내가 접수한 신고의 처리 상태를 확인할 수 있어요.</p>
      </div>

      {/* 매너온도 */}
      <div className={styles.sectionHead}>
        <h2>매너 온도</h2>
        <p>다른 사용자와 거래할 때의 매너를 온도로 나타낸 신뢰도 지표예요.</p>
      </div>
      <MannerScoreCard />

      {status === 'loading' && (
        <div className={styles.empty}><p>불러오는 중...</p></div>
      )}

      {status === 'error' && (
        <div className={styles.empty}>
          <div className={styles.emptyIcon}>⚠️</div>
          <p>{errorMsg}</p>
        </div>
      )}

      {status === 'ready' && (
        <>
          {/* 처리 상태 섹션 타이틀 */}
          <div className={styles.sectionHead}>
            <h2>처리 상태</h2>
            <p>내가 접수한 신고가 지금 어떤 단계에 있는지 통계와 목록으로 확인할 수 있어요.</p>
          </div>

          {/* 통계 카드 */}
          {total > 0 && (
            <div className={styles.statsCard}>
              <div className={styles.donutWrap}>
                <div className={styles.donut} style={{ background: donutBg }}>
                  <div className={styles.donutHole}>
                    <span className={styles.donutTotal}><AnimatedNumber value={total} /></span>
                    <span className={styles.donutTotalLabel}>총 신고</span>
                  </div>
                </div>
              </div>
              <div className={styles.legend}>
                {STATUS_ORDER.map(key => (
                  <div key={key} className={styles.legendRow}>
                    <span className={styles.legendDot} style={{ background: STATUS_COLOR_VAR[key] }} />
                    <span className={styles.legendLabel}>{STATUS_LABEL[key]}</span>
                    <span className={styles.legendCount}><AnimatedNumber value={counts[key]} /></span>
                  </div>
                ))}
              </div>
              <div className={styles.insights}>
                {topReasonEntry && (
                  <div className={styles.reasonHighlight}>
                    <span className={styles.reasonHighlightIcon}>💡</span>
                    <div>
                      <div className={styles.reasonHighlightLabel}>가장 많이 접수한 사유</div>
                      <div className={styles.reasonHighlightValue}>
                        {REPORT_REASON_LABEL[topReasonEntry[0]] ?? topReasonEntry[0]}
                        <span className={styles.reasonHighlightCount}>{topReasonEntry[1]}건</span>
                      </div>
                    </div>
                  </div>
                )}
                <div className={styles.reasonHighlight}>
                  <span className={styles.reasonHighlightIcon}>📊</span>
                  <div style={{ flex: 1 }}>
                    <div className={styles.reasonHighlightLabel}>신고 유형 비율</div>
                    <div className={styles.reasonHighlightValue}>
                      상품 {productPct}%
                      <span className={styles.reasonHighlightCount}>· 사용자 {memberPct}%</span>
                    </div>
                    <div className={styles.typeRatioBar}>
                      <div className={styles.typeRatioProduct} style={{ width: `${productPct}%` }} />
                      <div className={styles.typeRatioMember} style={{ width: `${memberPct}%` }} />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          )}

          {/* 탭 필터 */}
          <div className={styles.tabs}>
            {TABS.map(tab => (
              <button
                key={tab.key}
                type="button"
                className={`${styles.tab}${filter === tab.key ? ' ' + styles.on : ''}`}
                onClick={() => setFilter(tab.key)}
              >
                {tab.label}
              </button>
            ))}
          </div>

          {/* 신고 목록 */}
          {filtered.length > 0 ? (
            <div className={styles.list}>
              {filtered.map(report => {
                const tt = typeTag(report.reportType)
                const st = statusTag(report.status)
                const targetLabel = targetLabelOf(report, productTitles)
                return (
                  <div key={report.reportId} className={styles.rcard}>
                    <div className={styles.top}>
                      <span className={tt.cls}>{tt.icon} {tt.label}</span>
                      <span className={st.cls}>{st.label}</span>
                      <span className={styles.rid}>#{report.reportId}</span>
                    </div>
                    <div className={styles.target}>{targetLabel}</div>
                    <div className={styles.reason}>
                      <b>사유</b>&nbsp; {REPORT_REASON_LABEL[report.reason] ?? report.reason}
                    </div>
                    {report.evidenceImageUrl && (
                      <a
                        href={report.evidenceImageUrl}
                        target="_blank"
                        rel="noreferrer"
                        className={styles.evidenceThumb}
                      >
                        <img src={report.evidenceImageUrl} alt="증빙 이미지" />
                      </a>
                    )}
                    <StatusStepper status={report.status} />
                    <div className={styles.foot}>
                      <button
                        type="button"
                        className={styles.detailBtn}
                        onClick={() => setDetailReportId(report.reportId)}
                      >
                        상세보기
                      </button>
                      <span className={styles.date}>{formatRelative(report.createdAt)}</span>
                      {report.status === 'RECEIVED' && (
                        <button
                          type="button"
                          className={styles.cancelBtn}
                          onClick={() => setCancelTargetId(report.reportId)}
                          disabled={cancellingId === report.reportId}
                        >
                          {cancellingId === report.reportId ? '취소 중...' : '신고 취소'}
                        </button>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          ) : (
            <div className={styles.empty}>
              <div className={styles.emptyIcon}>🗂️</div>
              <p>해당 상태의 신고 내역이 없어요.</p>
            </div>
          )}
        </>
      )}

      {detailReportId != null && (() => {
        const detailReport = reports.find(r => r.reportId === detailReportId)
        return (
          <ReportDetailModal
            reportId={detailReportId}
            targetLabel={detailReport ? targetLabelOf(detailReport, productTitles) : ''}
            onClose={() => setDetailReportId(null)}
          />
        )
      })()}

      {cancelTargetId != null && (
        <ConfirmModal
          message="이 신고를 취소할까요? 취소 후에는 되돌릴 수 없어요."
          confirmText="취소하기"
          cancelText="닫기"
          danger
          onConfirm={() => cancelReport(cancelTargetId)}
          onCancel={() => setCancelTargetId(null)}
        />
      )}

      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>
    </main>
  )
}
