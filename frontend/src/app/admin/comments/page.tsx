'use client'

import { useEffect, useMemo, useState } from 'react'
import { getAccessToken } from '@/lib/auth'
import ConfirmModal from '@/components/ConfirmModal'
import styles from '../admin.module.css'

interface Comment {
  commentId: number
  memberId: number
  productId: number
  content: string
  deletedAt: string | null
  createdAt: string
}

interface Member { memberId: number; nickname: string }

type Status = 'loading' | 'ready' | 'error'

export default function AdminCommentsPage() {
  const [status, setStatus] = useState<Status>(() => (getAccessToken() ? 'loading' : 'error'))
  const [comments, setComments] = useState<Comment[]>([])
  const [nicknames, setNicknames] = useState<Record<number, string>>({})

  const [keyword, setKeyword] = useState('')
  const [query, setQuery] = useState('')
  const [productIdFilter, setProductIdFilter] = useState('')
  const [pendingDeleteId, setPendingDeleteId] = useState<number | null>(null)

  useEffect(() => {
    const token = getAccessToken()
    if (!token) return
    const headers = { Authorization: `Bearer ${token}` }

    let cancelled = false
    Promise.all([
      fetch('/api/admin/comments', { headers }).then(r => r.json()),
      fetch('/api/admin/members', { headers }).then(r => r.json()),
    ]).then(([commentsRes, membersRes]) => {
      if (cancelled) return
      setComments(commentsRes?.data ?? [])
      const members: Member[] = membersRes?.data ?? []
      const map: Record<number, string> = {}
      members.forEach(m => { map[m.memberId] = m.nickname })
      setNicknames(map)
      setStatus('ready')
    }).catch(() => { if (!cancelled) setStatus('error') })
    return () => { cancelled = true }
  }, [])

  async function deleteComment(commentId: number) {
    setPendingDeleteId(null)
    const token = getAccessToken()
    if (!token) return
    try {
      const res = await fetch(`/api/admin/comments/${commentId}`, {
        method: 'DELETE', headers: { Authorization: `Bearer ${token}` },
      })
      if (!res.ok) return
      setComments(prev => prev.map(c => c.commentId === commentId ? { ...c, deletedAt: new Date().toISOString() } : c))
    } catch {}
  }

  function handleSearch(e: React.FormEvent) {
    e.preventDefault()
    setQuery(keyword.trim().toLowerCase())
  }

  const filtered = useMemo(() => {
    let list = comments
    if (query) list = list.filter(c => c.content.toLowerCase().includes(query))
    if (productIdFilter.trim()) list = list.filter(c => String(c.productId) === productIdFilter.trim())
    return list
  }, [comments, query, productIdFilter])

  if (status === 'loading') return <div className={styles.empty}><p>불러오는 중...</p></div>
  if (status === 'error') return <div className={styles.empty}><p>댓글 목록을 불러오지 못했어요.</p></div>

  return (
    <>
      <div className={styles.ptitle}>댓글 목록 관리</div>
      <div className={styles.pdesc}>전체 댓글 검색 및 삭제 처리</div>
      <div className={styles.panel}>
        <form className={styles.filter} onSubmit={handleSearch}>
          <div className={styles.field}>
            <label>검색어 (댓글 내용)</label>
            <input value={keyword} onChange={e => setKeyword(e.target.value)} placeholder="댓글 내용 검색" />
          </div>
          <div className={styles.field}>
            <label>상품ID</label>
            <input value={productIdFilter} onChange={e => setProductIdFilter(e.target.value)} placeholder="상품 ID로 필터" inputMode="numeric" />
          </div>
          <button type="submit" className={`btn ${styles.filterBtn}`}>검색</button>
        </form>

        <div className={styles.tablewrap}>
          <table>
            <thead>
              <tr><th>댓글ID</th><th>상품ID</th><th>회원ID</th><th>닉네임</th><th>내용</th><th>작성일</th><th>관리</th></tr>
            </thead>
            <tbody>
              {filtered.map(c => (
                <tr key={c.commentId}>
                  <td>{c.commentId}</td>
                  <td>{c.productId}</td>
                  <td>{c.memberId}</td>
                  <td>{nicknames[c.memberId] ?? `회원 #${c.memberId}`}</td>
                  <td style={{ whiteSpace: 'normal', minWidth: 220 }}>
                    {c.deletedAt && <span className={`${styles.tag} ${styles.tagNeut}`} style={{ marginRight: 6 }}>삭제됨</span>}
                    {c.content}
                  </td>
                  <td>{c.createdAt.slice(0, 10)}</td>
                  <td><button type="button" className="btn danger" disabled={!!c.deletedAt} onClick={() => setPendingDeleteId(c.commentId)}>삭제</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {pendingDeleteId != null && (
        <ConfirmModal
          message="이 댓글을 삭제할까요?"
          confirmText="삭제"
          cancelText="취소"
          danger
          onConfirm={() => deleteComment(pendingDeleteId)}
          onCancel={() => setPendingDeleteId(null)}
        />
      )}
    </>
  )
}
