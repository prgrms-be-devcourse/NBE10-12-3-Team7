'use client'

import Link from 'next/link'
import { useEffect, useMemo, useState } from 'react'
import { getAccessToken } from '@/lib/auth'
import { REPORT_REASON_LABEL, type ReportReason } from '@/lib/reportReasons'
import { REPORT_STATUS_LABEL, REPORT_TYPE_LABEL, type ReportStatus, type ReportType } from '@/lib/reportStatus'
import styles from '../admin.module.css'

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

export default function AdminReportsPage() {
  const [status, setStatus] = useState<Status>(() => (getAccessToken() ? 'loading' : 'error'))
  const [reports, setReports] = useState<Report[]>([])
  const [statusFilter, setStatusFilter] = useState<'ALL' | ReportStatus>('ALL')
  const [typeFilter, setTypeFilter] = useState<'ALL' | ReportType>('ALL')

  useEffect(() => {
    const token = getAccessToken()
    if (!token) return
    let cancelled = false
    fetch('/api/admin/reports', { headers: { Authorization: `Bearer ${token}` } })
      .then(r => r.json())
      .then(data => { if (!cancelled) { setReports(data?.data ?? []); setStatus('ready') } })
      .catch(() => { if (!cancelled) setStatus('error') })
    return () => { cancelled = true }
  }, [])

  const filtered = useMemo(() => {
    let list = reports
    if (statusFilter !== 'ALL') list = list.filter(r => r.status === statusFilter)
    if (typeFilter !== 'ALL') list = list.filter(r => r.reportType === typeFilter)
    return list
  }, [reports, statusFilter, typeFilter])

  if (status === 'loading') return <div className={styles.empty}><p>불러오는 중...</p></div>
  if (status === 'error') return <div className={styles.empty}><p>신고 목록을 불러오지 못했어요.</p></div>

  return (
    <>
      <div className={styles.ptitle}>신고 목록 관리</div>
      <div className={styles.pdesc}>접수된 신고 조회 및 상태 관리</div>
      <div className={styles.panel}>
        <div className={styles.filter}>
          <div className={styles.field}>
            <label>처리 상태</label>
            <div className={styles.statusToggle}>
              {(['ALL', 'RECEIVED', 'REVIEWING', 'COMPLETED', 'REJECTED'] as const).map(s => (
                <button
                  key={s}
                  type="button"
                  className={`${styles.statusToggleBtn}${statusFilter === s ? ' ' + styles.statusToggleOn : ''}`}
                  onClick={() => setStatusFilter(s)}
                >
                  {s === 'ALL' ? '전체' : REPORT_STATUS_LABEL[s]}
                </button>
              ))}
            </div>
          </div>
          <div className={styles.field}>
            <label>신고 유형</label>
            <div className={styles.statusToggle}>
              {(['ALL', 'PRODUCT', 'MEMBER'] as const).map(t => (
                <button
                  key={t}
                  type="button"
                  className={`${styles.statusToggleBtn}${typeFilter === t ? ' ' + styles.statusToggleOn : ''}`}
                  onClick={() => setTypeFilter(t)}
                >
                  {t === 'ALL' ? '전체' : `${REPORT_TYPE_LABEL[t]} 신고`}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className={styles.tablewrap}>
          <table>
            <thead>
              <tr><th>신고ID</th><th>신고유형</th><th>사유</th><th>처리상태</th><th>신고자ID</th><th>대상ID</th><th>신고일</th><th>관리</th></tr>
            </thead>
            <tbody>
              {filtered.map(r => (
                <tr key={r.reportId}>
                  <td>{r.reportId}</td>
                  <td><span className={`${styles.tag} ${typeTagCls(r.reportType)}`}>{REPORT_TYPE_LABEL[r.reportType]}</span></td>
                  <td>{REPORT_REASON_LABEL[r.reason] ?? r.reason}</td>
                  <td><span className={`${styles.tag} ${statusTagCls(r.status)}`}>{REPORT_STATUS_LABEL[r.status]}</span></td>
                  <td>{r.reporterId}</td>
                  <td>{r.reportType === 'PRODUCT' ? r.targetProductId : r.targetMemberId}</td>
                  <td>{r.createdAt.slice(0, 10)}</td>
                  <td><Link href={`/admin/reports/${r.reportId}`} className="btn ghost">상세</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
