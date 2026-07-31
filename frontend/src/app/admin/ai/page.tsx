'use client'

import { useEffect, useRef, useState } from 'react'
import { apiFetch } from '@/lib/apiClient'
import styles from '../admin.module.css'

type Role = 'ai' | 'user' | 'error'

interface ChatMessage {
  id: number
  role: Role
  text: string
}

const SUGGESTED_QUESTIONS = [
  '대시보드 현황 알려줘',
  '정지된 회원 목록 보여줘',
  '접수 대기 신고 몇 건이야?',
  '숨김 처리된 상품 알려줘',
]

let nextMessageId = 1

function AiIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
      <path d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.937A2 2 0 0 0 9.937 8.5l1.581-6.135a.5.5 0 0 1 .964 0L13.5 8.5a2 2 0 0 0 1.437 1.437l6.135 1.581a.5.5 0 0 1 0 .964L14.937 13.5A2 2 0 0 0 13.5 14.937l-1.581 6.135a.5.5 0 0 1-.964 0z" />
    </svg>
  )
}

export default function AdminAiPage() {
  const [messages, setMessages] = useState<ChatMessage[]>([
    { id: nextMessageId++, role: 'ai', text: '안녕하세요. 관리 데이터를 조회해 드립니다. 예: "오늘 접수 대기 신고 몇 건이야?"' },
  ])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const logRef = useRef<HTMLDivElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)

  useEffect(() => {
    const el = logRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, sending])

  async function handleSend(e: React.FormEvent) {
    e.preventDefault()
    const text = input.trim()
    if (!text || sending) return

    setMessages(prev => [...prev, { id: nextMessageId++, role: 'user', text }])
    setInput('')

    setSending(true)
    try {
      // apiFetch: Access Token 자동 부착 + 401 시 Refresh 쿠키로 재발급 후 1회 재시도
      // (재발급도 실패하면 로그인 페이지로 이동). 다른 admin 페이지와 동일한 인증 경로.
      const res = await apiFetch('/api/admin/ai/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: text }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) {
        setMessages(prev => [...prev, { id: nextMessageId++, role: 'error', text: data?.message ?? '답변을 가져오지 못했어요.' }])
        return
      }
      setMessages(prev => [...prev, { id: nextMessageId++, role: 'ai', text: data?.data?.answer ?? '답변이 비어있어요.' }])
    } catch {
      setMessages(prev => [...prev, { id: nextMessageId++, role: 'error', text: '서버에 연결할 수 없습니다.' }])
    } finally {
      setSending(false)
    }
  }

  function fillChip(question: string) {
    setInput(question)
    inputRef.current?.focus()
  }

  return (
    <div className={styles.aiShell}>
      <div className={styles.aiHead}>
        <h1 className={styles.aiTitle}>
          <span className={styles.aiIcon}><AiIcon /></span>
          AI 어시스턴트
          <span className={`${styles.tag} ${styles.tagPink}`}>읽기 전용</span>
        </h1>
        <p className={styles.aiSub}>자연어로 관리 데이터를 조회합니다</p>
      </div>

      <div className={styles.chatCard}>
        <div className={styles.chatLog} ref={logRef}>
          {messages.map(m => (
            <div
              key={m.id}
              className={`${styles.chatMsg}${m.role === 'user' ? ' ' + styles.user : ''}${m.role === 'error' ? ' ' + styles.error : ''}`}
            >
              {m.role !== 'user' && (
                <div className={`${styles.chatAvatar}${m.role === 'error' ? ' ' + styles.chatAvatarError : ''}`}>
                  <AiIcon />
                </div>
              )}
              <div className={styles.chatBubble}>{m.text}</div>
            </div>
          ))}
          {sending && (
            <div className={styles.chatMsg}>
              <div className={styles.chatAvatar}><AiIcon /></div>
              <div className={`${styles.chatBubble} ${styles.typing}`} aria-label="답변 생성 중">
                <span /><span /><span />
              </div>
            </div>
          )}
        </div>

        <div className={styles.composer}>
          <div className={styles.chatChips}>
            {SUGGESTED_QUESTIONS.map(q => (
              <button key={q} type="button" className={styles.chatChip} onClick={() => fillChip(q)}>
                {q}
              </button>
            ))}
          </div>

          <form className={styles.chatInputRow} onSubmit={handleSend}>
            <input
              ref={inputRef}
              placeholder="질문을 입력하세요… (예: 최근 신고 목록 보여줘)"
              value={input}
              onChange={e => setInput(e.target.value)}
              disabled={sending}
            />
            <button type="submit" className={styles.chatSendBtn} disabled={sending || !input.trim()} aria-label="전송">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M7 11l5-5 5 5M12 6v13" />
              </svg>
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
