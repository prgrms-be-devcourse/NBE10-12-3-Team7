'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { getAccessToken } from '@/lib/auth'
import { REPORT_REASON_LABEL, type ReportReason } from '@/lib/reportReasons'
import { REPORT_STATUS_LABEL, REPORT_TYPE_LABEL, type ReportStatus, type ReportType } from '@/lib/reportStatus'
import ConfirmModal from '@/components/ConfirmModal'
import styles from '../../admin.module.css'

interface Report {
  reportId: number
  reporterId: number
  targetMemberId: number | null
  targetProductId: number | null
  reportType: ReportType
  reason: ReportReason
  content: string
  status: ReportStatus
  createdAt: string
}

type Status = 'loading' | 'ready' | 'error'

function statusTagCls(s: ReportStatus) {
  if (s === 'RECEIVED') return styles.tagGreen
  if (s === 'REVIEWING') return styles.tagAmber
  if (s === 'REJECTED') return styles.tagRose
  return styles.tagNeut
}
function typeTagCls(t: ReportType) {
  return t === 'PRODUCT' ? styles.tagPink : styles.tagRose
}

export default function AdminReportDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [status, setStatus] = useState<Status>(() => (getAccessToken() ? 'loading' : 'error'))
  const [report, setReport] = useState<Report | null>(null)
  const [reporterName, setReporterName] = useState('')
  const [targetName, setTargetName] = useState('')
  const [targetSellerId, setTargetSellerId] = useState<number | null>(null)
  const [selectVal, setSelectVal] = useState<ReportStatus>('RECEIVED')
  const [saving, setSaving] = useState(false)
  const [showHideConfirm, setShowHideConfirm] = useState(false)
  const [showSuspendConfirm, setShowSuspendConfirm] = useState(false)

  const [toastText, setToastText] = useState('')
  const [toastOn, setToastOn] = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  function showToast(msg: string) {
    setToastText(msg); setToastOn(true)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => setToastOn(false), 1900)
  }

  useEffect(() => {
    const token = getAccessToken()
    if (!token) return
    const headers = { Authorization: `Bearer ${token}` }

    let cancelled = false
    fetch(`/api/admin/reports/${id}`, { headers })
      .then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) }))
      .then(reportRes => {
        if (cancelled) return
        if (!reportRes.ok || !reportRes.data?.data) { setStatus('error'); return }
        const r: Report = reportRes.data.data
        setReport(r)
        setSelectVal(r.status)

        const lookups: Promise<void>[] = [
          fetch(`/api/admin/members/${r.reporterId}`, { headers }).then(res => res.json()).then(d => {
            if (!cancelled) setReporterName(d?.data?.nickname ?? `회원 #${r.reporterId}`)
          }).catch(() => {}),
        ]
        if (r.reportType === 'PRODUCT' && r.targetProductId != null) {
          lookups.push(
            fetch(`/api/admin/products/${r.targetProductId}`, { headers }).then(res => res.json()).then(d => {
              if (cancelled) return
              setTargetName(d?.data?.title ?? `상품 #${r.targetProductId}`)
              setTargetSellerId(d?.data?.memberId ?? null)
            }).catch(() => {})
          )
        } else if (r.reportType === 'MEMBER' && r.targetMemberId != null) {
          lookups.push(
            fetch(`/api/admin/members/${r.targetMemberId}`, { headers }).then(res => res.json()).then(d => {
              if (!cancelled) setTargetName(d?.data?.nickname ?? `회원 #${r.targetMemberId}`)
            }).catch(() => {})
          )
        }
        Promise.all(lookups).then(() => { if (!cancelled) setStatus('ready') })
      })
      .catch(() => { if (!cancelled) setStatus('error') })
    return () => { cancelled = true }
  }, [id])

  async function applyStatus() {
    if (!report) return
    const token = getAccessToken()
    if (!token) return
    setSaving(true)
    try {
      const res = await fetch(`/api/admin/reports/${report.reportId}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ status: selectVal }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) { showToast(data?.message ?? '상태 변경 중 오류가 발생했습니다.'); return }
      setReport(data.data)
      showToast(`처리 상태를 "${REPORT_STATUS_LABEL[selectVal]}"(으)로 변경했어요`)
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    } finally {
      setSaving(false)
    }
  }

  async function hideTargetProduct() {
    if (!report?.targetProductId) return
    setShowHideConfirm(false)
    const token = getAccessToken()
    if (!token) return
    try {
      const res = await fetch(`/api/admin/products/${report.targetProductId}/hidden`, {
        method: 'PATCH', headers: { Authorization: `Bearer ${token}` },
      })
      const data = await res.json().catch(() => null)
      showToast(res.ok ? '대상 상품을 숨김 처리했어요' : (data?.message ?? '처리 중 오류가 발생했습니다.'))
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    }
  }

  async function suspendTargetMember() {
    const memberId = report?.reportType === 'MEMBER' ? report.targetMemberId : targetSellerId
    if (!memberId) return
    setShowSuspendConfirm(false)
    const token = getAccessToken()
    if (!token) return
    try {
      const res = await fetch(`/api/admin/members/${memberId}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ status: 'SUSPENDED' }),
      })
      const data = await res.json().catch(() => null)
      showToast(res.ok ? '대상 회원을 정지 처리했어요' : (data?.message ?? '처리 중 오류가 발생했습니다.'))
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    }
  }

  if (status === 'loading') return <div className={styles.empty}><p>불러오는 중...</p></div>
  if (status === 'error' || !report) {
    return (
      <div className={styles.empty}>
        <p>신고 정보를 불러오지 못했어요.</p>
        <Link href="/admin/reports" className="btn ghost">목록으로</Link>
      </div>
    )
  }

  const targetId = report.reportType === 'PRODUCT' ? report.targetProductId : report.targetMemberId

  return (
    <>
      <div className={styles.ptitle}>신고 상세 / 처리 상태 변경</div>
      <div className={styles.cols2}>
        <div className={styles.panel}>
          <h3>신고 상세 정보</h3>
          <dl className={styles.def}>
            <dt>신고ID</dt><dd>{report.reportId}</dd>
            <dt>신고유형</dt><dd><span className={`${styles.tag} ${typeTagCls(report.reportType)}`}>{REPORT_TYPE_LABEL[report.reportType]}</span></dd>
            <dt>사유</dt><dd>{REPORT_REASON_LABEL[report.reason] ?? report.reason}</dd>
            <dt>처리상태</dt><dd><span className={`${styles.tag} ${statusTagCls(report.status)}`}>{REPORT_STATUS_LABEL[report.status]}</span></dd>
            <dt>신고일</dt><dd>{report.createdAt.slice(0, 10)}</dd>
          </dl>

          <h3 style={{ marginTop: 18 }}>신고자 정보</h3>
          <dl className={styles.def}>
            <dt>신고자ID</dt><dd>{report.reporterId}</dd>
            <dt>닉네임</dt><dd>{reporterName || '-'}</dd>
          </dl>

          <h3 style={{ marginTop: 18 }}>신고 대상 정보</h3>
          <dl className={styles.def}>
            <dt>대상ID</dt><dd>{targetId} ({REPORT_TYPE_LABEL[report.reportType]})</dd>
            <dt>대상명</dt><dd>{targetName || '-'}</dd>
          </dl>

          <h3 style={{ marginTop: 18 }}>신고 내용</h3>
          <p style={{ color: 'var(--text-muted)', marginTop: 8, whiteSpace: 'pre-line' }}>{report.content}</p>
        </div>
        <div className={styles.panel}>
          <h3>처리</h3>
          <div className={styles.field}>
            <label>처리 상태</label>
            <div className={styles.statusToggle}>
              {(['RECEIVED', 'REVIEWING', 'COMPLETED', 'REJECTED'] as const).map(s => (
                <button
                  key={s}
                  type="button"
                  className={`${styles.statusToggleBtn}${selectVal === s ? ' ' + styles.statusToggleOn : ''}`}
                  onClick={() => setSelectVal(s)}
                >
                  {REPORT_STATUS_LABEL[s]}
                </button>
              ))}
            </div>
          </div>
          <div className={styles.btnRow} style={{ marginBottom: 18 }}>
            <button type="button" className="btn" onClick={applyStatus} disabled={saving}>
              {saving ? '변경 중...' : '상태 변경'}
            </button>
          </div>
          <h3>연계 조치</h3>
          <div className={styles.btnRow}>
            {report.reportType === 'PRODUCT' && (
              <button type="button" className={styles.btnWarn} onClick={() => setShowHideConfirm(true)}>대상 상품 숨김 처리</button>
            )}
            <button type="button" className="btn danger" onClick={() => setShowSuspendConfirm(true)}>대상 회원 정지 처리</button>
          </div>
        </div>
      </div>
      {showHideConfirm && (
        <ConfirmModal
          message="대상 상품을 숨김 처리할까요?"
          confirmText="숨김 처리"
          cancelText="취소"
          confirmColor="var(--amber)"
          onConfirm={hideTargetProduct}
          onCancel={() => setShowHideConfirm(false)}
        />
      )}

      {showSuspendConfirm && (
        <ConfirmModal
          message="대상 회원을 정지 처리할까요?"
          confirmText="정지 처리"
          cancelText="취소"
          danger
          onConfirm={suspendTargetMember}
          onCancel={() => setShowSuspendConfirm(false)}
        />
      )}

      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>
    </>
  )
}
