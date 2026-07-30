'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { getAccessToken } from '@/lib/auth'
import { MEMBER_STATUS_LABEL, type MemberStatus } from '@/lib/memberStatus'
import styles from '../../admin.module.css'

interface Member {
  memberId: number
  email: string
  nickname: string
  role: string
  status: MemberStatus
  createdAt: string
  deletedAt: string | null
}

interface Report { reportType: 'PRODUCT' | 'MEMBER'; targetMemberId: number | null }

type Status = 'loading' | 'ready' | 'error'

function statusTagCls(s: MemberStatus) {
  if (s === 'ACTIVE') return styles.tagGreen
  if (s === 'SUSPENDED') return styles.tagAmber
  return styles.tagNeut
}

export default function AdminMemberDetailPage() {
  const { id } = useParams<{ id: string }>()
  const [status, setStatus] = useState<Status>(() => (getAccessToken() ? 'loading' : 'error'))
  const [member, setMember] = useState<Member | null>(null)
  const [reportCount, setReportCount] = useState(0)
  const [selectVal, setSelectVal] = useState<MemberStatus>('ACTIVE')
  const [saving, setSaving] = useState(false)

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
    Promise.all([
      fetch(`/api/admin/members/${id}`, { headers }).then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) })),
      fetch('/api/admin/reports', { headers }).then(r => r.json()),
    ]).then(([memberRes, reportsRes]) => {
      if (cancelled) return
      if (!memberRes.ok || !memberRes.data?.data) { setStatus('error'); return }
      setMember(memberRes.data.data)
      setSelectVal(memberRes.data.data.status)
      const reports: Report[] = reportsRes?.data ?? []
      setReportCount(reports.filter(r => r.reportType === 'MEMBER' && r.targetMemberId === Number(id)).length)
      setStatus('ready')
    }).catch(() => { if (!cancelled) setStatus('error') })
    return () => { cancelled = true }
  }, [id])

  async function applyStatus() {
    const token = getAccessToken()
    if (!token) return
    setSaving(true)
    try {
      const res = await fetch(`/api/admin/members/${id}/status`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ status: selectVal }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) { showToast(data?.message ?? '상태 변경 중 오류가 발생했습니다.'); setSaving(false); return }
      setMember(data.data)
      showToast(`회원 상태를 "${MEMBER_STATUS_LABEL[selectVal]}"(으)로 변경했어요`)
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    } finally {
      setSaving(false)
    }
  }

  if (status === 'loading') return <div className={styles.empty}><p>불러오는 중...</p></div>
  if (status === 'error' || !member) {
    return (
      <div className={styles.empty}>
        <p>회원 정보를 불러오지 못했어요.</p>
        <Link href="/admin/members" className="btn ghost">목록으로</Link>
      </div>
    )
  }

  return (
    <>
      <div className={styles.ptitle} style={{ marginBottom: 20 }}>회원 상세 / 상태 변경</div>
      <div className={styles.cols2}>
        <div className={styles.panel}>
          <h3>회원 기본 정보</h3>
          <dl className={styles.def}>
            <dt>회원ID</dt><dd>{member.memberId}</dd>
            <dt>이메일</dt><dd>{member.email}</dd>
            <dt>닉네임</dt><dd>{member.nickname}</dd>
            <dt>권한</dt><dd>{member.role === 'ROLE_ADMIN' ? '관리자' : '일반회원'}</dd>
            <dt>상태</dt><dd><span className={`${styles.tag} ${statusTagCls(member.status)}`}>{MEMBER_STATUS_LABEL[member.status]}</span></dd>
            <dt>신고 누적 수</dt><dd>{reportCount > 0 ? <span className={`${styles.tag} ${styles.tagRose}`}>{reportCount}건</span> : '0건'}</dd>
            <dt>가입일</dt><dd>{member.createdAt.slice(0, 10)}</dd>
          </dl>
        </div>
        <div className={styles.panel}>
          <h3>상태 변경</h3>
          <div className={styles.field}>
            <label>회원 상태</label>
            <select value={selectVal} onChange={e => setSelectVal(e.target.value as MemberStatus)}>
              <option value="ACTIVE">정상</option>
              <option value="SUSPENDED">정지</option>
              <option value="DELETED">탈퇴</option>
            </select>
          </div>
          <div className={styles.btnRow}>
            <button type="button" className="btn" onClick={applyStatus} disabled={saving}>
              {saving ? '변경 중...' : '상태 변경'}
            </button>
          </div>
        </div>
      </div>
      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>
    </>
  )
}
