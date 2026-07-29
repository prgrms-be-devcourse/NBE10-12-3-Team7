'use client'

import { useEffect, useRef, useState } from 'react'
import { usePathname } from 'next/navigation'
import styles from './LegalChatWidget.module.css'

interface Source {
  title: string
  snippet: string
}

interface ChatMessage {
  role: 'user' | 'assistant'
  text: string
  sources?: Source[]
  inScope?: boolean
}

type SendStatus = 'idle' | 'sending' | 'error'

export default function LegalChatWidget() {
  const pathname = usePathname()
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [status, setStatus] = useState<SendStatus>('idle')
  const [conversationId] = useState(() => (typeof crypto !== 'undefined' ? crypto.randomUUID() : ''))
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, status])

  /* 관리자 화면은 자체 콘솔 레이아웃이라 노출하지 않는다(Header와 동일 규칙).
     첫 진입 웰컴 화면(/)·로그인·회원가입 화면은 거래 관련 기능이 없어 법률 상담 버튼이 불필요하다. */
  if (pathname?.startsWith('/admin') || pathname === '/' || pathname === '/login' || pathname === '/signup') return null

  async function send() {
    const question = input.trim()
    if (!question || status === 'sending') return

    setMessages(prev => [...prev, { role: 'user', text: question }])
    setInput('')
    setStatus('sending')
    try {
      const res = await fetch('/agent/legal/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, conversationId }),
      })
      const body = await res.json().catch(() => null)
      if (!res.ok || !body?.success) {
        setMessages(prev => [...prev, {
          role: 'assistant',
          text: '지금은 답변을 가져올 수 없어요. 잠시 후 다시 시도해주세요.',
        }])
        setStatus('error')
        return
      }
      setMessages(prev => [...prev, {
        role: 'assistant',
        text: body.data.answer,
        sources: body.data.sources,
        inScope: body.data.inScope,
      }])
      setStatus('idle')
    } catch {
      setMessages(prev => [...prev, {
        role: 'assistant',
        text: '서버에 연결할 수 없습니다.',
      }])
      setStatus('error')
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  return (
    <div className={styles.widget}>
      {open && (
        <div className={styles.panel}>
          <div className={styles.panelHead}>
            <div>
              <div className={styles.panelTitle}>⚖️ 거래 법률 상담</div>
              <div className={styles.panelSub}>AI가 참고용으로 답변해요 · 법적 효력은 없어요</div>
            </div>
          </div>

          <div className={styles.thread} ref={listRef}>
            {messages.length === 0 && (
              <div className={styles.emptyState}>
                부동산 계약, 사기 피해, 반품·환불 등<br />중고거래 관련 법률 질문을 편하게 물어보세요.
              </div>
            )}
            {messages.map((m, i) => (
              <div key={i} className={`${styles.bubbleRow} ${m.role === 'user' ? styles.rowUser : styles.rowAssistant}`}>
                <div className={`${styles.bubble} ${m.role === 'user' ? styles.bubbleUser : styles.bubbleAssistant}`}>
                  {m.text}
                  {m.inScope === false && (
                    <div className={styles.scopeNote}>이 질문은 거래 법률 범위를 벗어났을 수 있어요.</div>
                  )}
                  {m.sources && m.sources.length > 0 && (
                    <div className={styles.sources}>
                      <div className={styles.sourcesLabel}>참고 자료</div>
                      {m.sources.map((s, si) => (
                        <div key={si} className={styles.sourceItem}>
                          <div className={styles.sourceTitle}>{s.title}</div>
                          <div className={styles.sourceSnippet}>{s.snippet}</div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
            {status === 'sending' && (
              <div className={`${styles.bubbleRow} ${styles.rowAssistant}`}>
                <div className={`${styles.bubble} ${styles.bubbleAssistant}`}>답변을 준비하고 있어요...</div>
              </div>
            )}
          </div>

          <div className={styles.inputRow}>
            <input
              type="text"
              placeholder="법률 질문을 입력하세요"
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={status === 'sending'}
            />
            <button type="button" onClick={send} disabled={status === 'sending' || !input.trim()}>
              전송
            </button>
          </div>
        </div>
      )}

      <div className={styles.fabRow}>
        {!open && <span className={styles.tooltip}>거래 법률 상담</span>}
        <button
          type="button"
          className={styles.fab}
          aria-label={open ? '법률 상담 닫기' : '거래 법률 상담 열기'}
          onClick={() => setOpen(v => !v)}
        >
          {open ? (
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.2">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          ) : (
            <svg width="26" height="26" viewBox="0 0 24 24" fill="none">
              <path d="M4 12a8 8 0 1 1 3.2 6.4L4 20l1.2-3.6A7.96 7.96 0 0 1 4 12Z" fill="#fff" />
              <path d="M8.5 12.2l2.2 2.2 4.8-4.8" stroke="var(--primary)" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          )}
        </button>
      </div>
    </div>
  )
}
