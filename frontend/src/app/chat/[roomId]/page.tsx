'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { Client } from '@stomp/stompjs'
import { Fragment, useEffect, useRef, useState } from 'react'
import { apiFetch } from '@/lib/apiClient'
import { getAccessToken, getCurrentMemberId } from '@/lib/auth'
import type { TradeStatus } from '@/lib/tradeStatus'
import MannerScoreBadge from '@/components/MannerScoreBadge'
import MannerRatingModal from '@/components/MannerRatingModal'
import styles from './page.module.css'

/** 로컬 dev는 Next 서버(:3000)가 WebSocket 업그레이드를 프록시하지 못해 백엔드에 직접 접속한다. */
const WS_ORIGIN = process.env.NEXT_PUBLIC_WS_ORIGIN ?? 'ws://localhost:8080'

interface ChatProductSummary {
  productId: number
  title: string
  price: number
  tradeStatus: TradeStatus
  thumbnailUrl: string | null
}

interface ChatMemberSummary {
  memberId: number
  nickname: string
  withdrawn: boolean
}

interface ChatMessage {
  messageId: number
  senderId: number
  content: string
  createdAt: string
}

interface ChatRoom {
  roomId: number
  product: ChatProductSummary
  opponent: ChatMemberSummary
  viewerRole: 'BUYER' | 'SELLER'
  createdAt: string
  lastMessage: ChatMessage | null
}

type PageStatus = 'loading' | 'ready' | 'error'

function priceText(price: number) {
  return price === 0 ? '나눔' : price.toLocaleString('ko-KR') + '원'
}

function formatTime(iso: string) {
  return new Date(iso).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
}

function isSameDay(a: string, b: string) {
  const da = new Date(a), db = new Date(b)
  return da.getFullYear() === db.getFullYear() && da.getMonth() === db.getMonth() && da.getDate() === db.getDate()
}

function formatDateSeparator(iso: string) {
  const d = new Date(iso)
  if (isSameDay(iso, new Date().toISOString())) return '오늘'
  const yesterday = new Date()
  yesterday.setDate(yesterday.getDate() - 1)
  if (isSameDay(iso, yesterday.toISOString())) return '어제'
  return d.toLocaleDateString('ko-KR', { year: 'numeric', month: 'long', day: 'numeric' })
}

export default function ChatRoomPage() {
  const { roomId } = useParams<{ roomId: string }>()
  const myMemberId = getCurrentMemberId()

  const [room, setRoom] = useState<ChatRoom | null>(null)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [status, setStatus] = useState<PageStatus>('loading')
  const [errorMsg, setErrorMsg] = useState('')
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)

  const [toastText, setToastText] = useState('')
  const [toastOn, setToastOn] = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [ratingModalOpen, setRatingModalOpen] = useState(false)

  const listRef = useRef<HTMLDivElement | null>(null)
  const seenIds = useRef<Set<number>>(new Set())

  function showToast(msg: string) {
    setToastText(msg); setToastOn(true)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => setToastOn(false), 1900)
  }

  function scrollToBottom() {
    requestAnimationFrame(() => {
      const el = listRef.current
      if (el) el.scrollTop = el.scrollHeight
    })
  }

  function markRead() {
    apiFetch(`/api/chat-rooms/${roomId}/read`, { method: 'POST' }).catch(() => {})
  }

  function mergeMessages(incoming: ChatMessage[]) {
    incoming.forEach(m => seenIds.current.add(m.messageId))
    setMessages(prev => {
      const map = new Map(prev.map(m => [m.messageId, m]))
      for (const m of incoming) map.set(m.messageId, m)
      return Array.from(map.values()).sort((a, b) => a.messageId - b.messageId)
    })
  }

  useEffect(() => {
    let cancelled = false

    Promise.all([
      apiFetch('/api/chat-rooms').then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) })),
      apiFetch(`/api/chat-rooms/${roomId}/messages`).then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) })),
    ]).then(([roomsRes, messagesRes]) => {
      if (cancelled) return
      if (!roomsRes.ok) { setErrorMsg(roomsRes.data?.message ?? '채팅방을 불러오지 못했습니다.'); setStatus('error'); return }

      const found: ChatRoom | undefined = (roomsRes.data?.data ?? [])
        .find((r: ChatRoom) => String(r.roomId) === String(roomId))
      if (!found) { setErrorMsg('채팅방을 찾을 수 없어요.'); setStatus('error'); return }
      setRoom(found)

      if (!messagesRes.ok) { setErrorMsg(messagesRes.data?.message ?? '메시지를 불러오지 못했습니다.'); setStatus('error'); return }
      const list: ChatMessage[] = (messagesRes.data?.data?.messages ?? []).slice().reverse()
      mergeMessages(list)
      setStatus('ready')
      scrollToBottom()
      markRead()
    }).catch(() => {
      if (!cancelled) { setErrorMsg('서버에 연결할 수 없습니다.'); setStatus('error') }
    })

    return () => { cancelled = true }
  }, [roomId])

  /* 실시간 수신 — 방 토픽을 구독해 상대 메시지를 폴링 없이 즉시 반영한다.
     전송은 기존 REST(POST .../messages)를 그대로 쓰고, 서버가 전송 성공 후 이 토픽으로 브로드캐스트한다. */
  useEffect(() => {
    if (status !== 'ready') return

    const client = new Client({
      brokerURL: `${WS_ORIGIN}/ws`,
      reconnectDelay: 3000,
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${getAccessToken() ?? ''}` }
      },
      onConnect: () => {
        client.subscribe(`/topic/chat-rooms/${roomId}`, frame => {
          const msg: ChatMessage = JSON.parse(frame.body)
          mergeMessages([msg])
          scrollToBottom()
          if (msg.senderId !== myMemberId) markRead()
        })
      },
    })
    client.activate()

    return () => { client.deactivate() }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- myMemberId는 세션 동안 불변, roomId/status 변경 시에만 재연결하면 된다.
  }, [status, roomId])

  async function handleSend(e: React.FormEvent) {
    e.preventDefault()
    const v = input.trim()
    if (!v || sending) return
    setSending(true)
    try {
      const res = await apiFetch(`/api/chat-rooms/${roomId}/messages`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content: v }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) { showToast(data?.message ?? '전송 중 오류가 발생했습니다.'); return }
      mergeMessages([data.data])
      setInput('')
      scrollToBottom()
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    } finally {
      setSending(false)
    }
  }

  if (status === 'loading') {
    return <main className={styles.wrap}><div className={styles.empty}><p>불러오는 중...</p></div></main>
  }
  if (status === 'error') {
    return (
      <main className={styles.wrap}>
        <div className={styles.empty}>
          <div className={styles.emptyIcon}>⚠️</div>
          <p>{errorMsg}</p>
          <Link href="/chat" className={`btn ghost ${styles.backBtn}`}>채팅 목록으로</Link>
        </div>
      </main>
    )
  }

  return (
    <main className={styles.wrap}>
      <div className={styles.head}>
        <Link href="/chat" className={styles.back} aria-label="채팅 목록으로">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
            <path d="M15 18l-6-6 6-6" />
          </svg>
        </Link>
        <div className={styles.avatar}>{room?.opponent.nickname.charAt(0) ?? '?'}</div>
        <div className={styles.headInfo}>
          <div className={styles.nick}>
            {room?.opponent.nickname}
            {room && <MannerScoreBadge memberId={room.opponent.memberId} />}
          </div>
          <div className={styles.pname}>{room?.product.title} · {room ? priceText(room.product.price) : ''}</div>
        </div>
        {room && (
          <Link href={`/products/${room.product.productId}`} className={styles.viewProduct}>
            <div className={styles.viewProductThumb}>
              {room.product.thumbnailUrl
                ? <img src={room.product.thumbnailUrl} alt="" />
                : <span className={styles.thumbPh}>NO IMG</span>}
            </div>
            상품보기
          </Link>
        )}
      </div>

      {room && room.product.tradeStatus === 'COMPLETED' && room.viewerRole === 'BUYER' && (
        <div className={styles.completeBanner}>
          <span>거래가 완료됐어요</span>
          <button type="button" onClick={() => setRatingModalOpen(true)}>후기 남기기</button>
        </div>
      )}

      <div className={styles.messages} ref={listRef}>
        {messages.length === 0 && (
          <div className={styles.emptyMsg}>
            <span className={styles.emptyMsgIcon}>👋</span>
            대화를 시작해보세요
          </div>
        )}
        {messages.map((m, i) => {
          const prev = messages[i - 1]
          const next = messages[i + 1]
          const showDateSep = !prev || !isSameDay(prev.createdAt, m.createdAt)
          const isMine = m.senderId === myMemberId
          const grouped = !!prev && !showDateSep && prev.senderId === m.senderId
          const groupContinues = !!next && isSameDay(m.createdAt, next.createdAt) && next.senderId === m.senderId
          return (
            <Fragment key={m.messageId}>
              {showDateSep && (
                <div className={styles.dateSep}><span>{formatDateSeparator(m.createdAt)}</span></div>
              )}
              <div
                className={`${styles.bubbleRow}${isMine ? ' ' + styles.mine : ''}${grouped ? ' ' + styles.grouped : ''}`}
              >
                <div className={`${styles.bubble}${groupContinues ? ' ' + styles.bubbleGrouped : ''}`}>
                  {m.content}
                </div>
                <div className={styles.mtime}>{formatTime(m.createdAt)}</div>
              </div>
            </Fragment>
          )
        })}
      </div>

      {room && room.opponent.withdrawn && (
        <div className={styles.withdrawnBanner}>
          탈퇴한 사용자와는 더 이상 대화할 수 없습니다.
        </div>
      )}

      <form className={styles.inputRow} onSubmit={handleSend}>
        <input
          className={styles.input}
          placeholder="메시지를 입력하세요"
          value={input}
          onChange={e => setInput(e.target.value)}
          maxLength={1000}
          disabled={!!room?.opponent.withdrawn}
        />
        <button
          type="submit"
          className={styles.sendBtn}
          disabled={sending || !input.trim() || !!room?.opponent.withdrawn}
          aria-label="전송"
        >
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M22 2 11 13" />
            <path d="M22 2 15 22l-4-9-9-4 20-7z" />
          </svg>
        </button>
      </form>

      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>

      {ratingModalOpen && room && (
        <MannerRatingModal
          productId={room.product.productId}
          productTitle={room.product.title}
          rateeNickname={room.opponent.nickname}
          onClose={() => setRatingModalOpen(false)}
          onSubmitted={() => showToast('후기를 등록했어요')}
        />
      )}
    </main>
  )
}
