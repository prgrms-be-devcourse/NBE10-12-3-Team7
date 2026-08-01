'use client'

import Link from 'next/link'
import { useEffect, useMemo, useState } from 'react'
import { getAccessToken } from '@/lib/auth'
import { MEMBER_STATUS_LABEL, type MemberStatus } from '@/lib/memberStatus'
import styles from '../admin.module.css'

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

export default function AdminMembersPage() {
  const [status, setStatus] = useState<Status>(() => (getAccessToken() ? 'loading' : 'error'))
  const [members, setMembers] = useState<Member[]>([])
  const [reportCounts, setReportCounts] = useState<Record<number, number>>({})

  const [keyword, setKeyword] = useState('')
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<'ALL' | MemberStatus>('ALL')

  useEffect(() => {
    const token = getAccessToken()
    if (!token) return
    const headers = { Authorization: `Bearer ${token}` }

    let cancelled = false
    Promise.all([
      fetch('/api/admin/members', { headers }).then(r => r.json()),
      fetch('/api/admin/reports', { headers }).then(r => r.json()),
    ]).then(([membersRes, reportsRes]) => {
      if (cancelled) return
      setMembers(membersRes?.data ?? [])
      const reports: Report[] = reportsRes?.data ?? []
      const counts: Record<number, number> = {}
      reports.forEach(r => {
        if (r.reportType === 'MEMBER' && r.targetMemberId != null) {
          counts[r.targetMemberId] = (counts[r.targetMemberId] ?? 0) + 1
        }
      })
      setReportCounts(counts)
      setStatus('ready')
    }).catch(() => { if (!cancelled) setStatus('error') })
    return () => { cancelled = true }
  }, [])

  function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    setQuery(keyword.trim().toLowerCase())
  }

  const filtered = useMemo(() => {
    let list = members
    if (statusFilter !== 'ALL') list = list.filter(m => m.status === statusFilter)
    if (query) list = list.filter(m => m.email.toLowerCase().includes(query) || m.nickname.toLowerCase().includes(query))
    return list
  }, [members, statusFilter, query])

  if (status === 'loading') return <div className={styles.empty}><p>불러오는 중...</p></div>
  if (status === 'error') return <div className={styles.empty}><p>회원 목록을 불러오지 못했어요.</p></div>

  return (
    <>
      <div className={styles.ptitle}>회원 목록 관리</div>
      <div className={styles.pdesc}>전체 회원 검색 및 상태 관리</div>
      <div className={styles.panel}>
        <form className={styles.filter} onSubmit={handleSearch}>
          <div className={styles.field}>
            <label>검색어 (이메일 / 닉네임)</label>
            <input value={keyword} onChange={e => setKeyword(e.target.value)} placeholder="검색어 입력" />
          </div>
          <div className={styles.field}>
            <label>회원 상태</label>
            <div className={styles.statusToggle}>
              {(['ALL', 'ACTIVE', 'SUSPENDED', 'DELETED'] as const).map(s => (
                <button
                  key={s}
                  type="button"
                  className={`${styles.statusToggleBtn}${statusFilter === s ? ' ' + styles.statusToggleOn : ''}`}
                  onClick={() => setStatusFilter(s)}
                >
                  {s === 'ALL' ? '전체' : MEMBER_STATUS_LABEL[s]}
                </button>
              ))}
            </div>
          </div>
          <button type="submit" className={`btn ${styles.filterBtn}`}>검색</button>
        </form>

        <div className={styles.tablewrap}>
          <table>
            <thead>
              <tr><th>회원ID</th><th>이메일</th><th>닉네임</th><th>권한</th><th>상태</th><th>신고수</th><th>가입일</th><th>관리</th></tr>
            </thead>
            <tbody>
              {filtered.map(m => (
                <tr key={m.memberId}>
                  <td>{m.memberId}</td>
                  <td>{m.email}</td>
                  <td>{m.nickname}</td>
                  <td>{m.role === 'ROLE_ADMIN' ? '관리자' : '일반회원'}</td>
                  <td><span className={`${styles.tag} ${statusTagCls(m.status)}`}>{MEMBER_STATUS_LABEL[m.status]}</span></td>
                  <td>{reportCounts[m.memberId] ?? 0}</td>
                  <td>{m.createdAt.slice(0, 10)}</td>
                  <td><Link href={`/admin/members/${m.memberId}`} className="btn ghost">상세</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}
