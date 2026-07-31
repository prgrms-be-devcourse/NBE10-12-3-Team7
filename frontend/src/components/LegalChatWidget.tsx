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
  grounded?: boolean
  emergency?: boolean
  isError?: boolean
}

type SendStatus = 'idle' | 'sending' | 'error'

/** 답변 본문 끝에 코드로 붙는 면책 조항(agent/src/agent/nodes.py의 _DISCLAIMER, '※'로 시작)을 분리해 각주로 렌더링한다. */
function splitFootnote(text: string): { body: string; footnote: string | null } {
  const idx = text.lastIndexOf('※')
  if (idx === -1) return { body: text, footnote: null }
  return { body: text.slice(0, idx).trimEnd(), footnote: text.slice(idx).trim() }
}

/** 긴급 안내의 112/119 버튼 아이콘. 이모지(📞) 대신 색을 직접 제어할 수 있는 SVG로 통일한다. */
function PhoneIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z" />
    </svg>
  )
}

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
          isError: true,
        }])
        setInput(question)
        setStatus('error')
        return
      }
      setMessages(prev => [...prev, {
        role: 'assistant',
        text: body.data.answer,
        sources: body.data.sources,
        inScope: body.data.inScope,
        grounded: body.data.grounded,
        emergency: body.data.emergency,
      }])
      setStatus('idle')
    } catch {
      setMessages(prev => [...prev, {
        role: 'assistant',
        text: '서버에 연결할 수 없습니다.',
        isError: true,
      }])
      setInput(question)
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
            {messages.map((m, i) => {
              const { body, footnote } = m.role === 'assistant' ? splitFootnote(m.text) : { body: m.text, footnote: null }
              const emergency = m.role === 'assistant' && m.emergency === true
              const outOfScope = m.role === 'assistant' && !emergency && m.inScope === false
              const noGround = m.role === 'assistant' && !emergency && m.inScope !== false && m.grounded === false
              return (
                <div key={i} className={`${styles.bubbleRow} ${m.role === 'user' ? styles.rowUser : styles.rowAssistant}`}>
                  <div
                    className={`${styles.bubble} ${m.role === 'user' ? styles.bubbleUser : styles.bubbleAssistant}${emergency ? ' ' + styles.bubbleEmergency : ''}${outOfScope ? ' ' + styles.bubbleOutOfScope : ''}${noGround ? ' ' + styles.bubbleNoGround : ''}${m.isError ? ' ' + styles.bubbleError : ''}`}
                  >
                    {emergency && <div className={styles.emergencyHead}>🚨 긴급 상황일 수 있어요</div>}
                    {body}
                    {emergency && (
                      <div className={styles.emergencyActions}>
                        <a href="tel:112" className={styles.emergencyBtn}><PhoneIcon /> 112 경찰</a>
                        <a href="tel:119" className={styles.emergencyBtn}><PhoneIcon /> 119 구급</a>
                      </div>
                    )}
                    {noGround && (
                      <div className={styles.groundNote}>근거 부족 — 출처 없음</div>
                    )}
                    {outOfScope && (
                      <div className={styles.scopeNote}>이 질문은 거래 법률 범위를 벗어났을 수 있어요.</div>
                    )}
                    {m.isError && (
                      <div className={styles.errorNote}>전송 실패 — 다시 시도</div>
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
                    {footnote && <div className={styles.footnote}>{footnote}</div>}
                  </div>
                </div>
              )
            })}
            {status === 'sending' && (
              <div className={`${styles.bubbleRow} ${styles.rowAssistant}`}>
                <div className={`${styles.bubble} ${styles.bubbleAssistant} ${styles.typingBubble}`} aria-label="답변을 준비하고 있어요">
                  <span className={styles.typingDot} />
                  <span className={styles.typingDot} />
                  <span className={styles.typingDot} />
                </div>
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
