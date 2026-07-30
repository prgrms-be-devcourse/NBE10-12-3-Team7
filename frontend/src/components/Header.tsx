'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { Client } from '@stomp/stompjs'
import { useEffect, useRef, useState } from 'react'
import { apiFetch, logout } from '@/lib/apiClient'
import { AUTH_CHANGED_EVENT, getAccessToken } from '@/lib/auth'

/* 로컬 dev는 Next 서버(:3000)가 WebSocket 업그레이드를 프록시하지 못해 백엔드에 직접 접속한다(chat 페이지와 동일). */
const WS_ORIGIN = process.env.NEXT_PUBLIC_WS_ORIGIN ?? 'ws://localhost:8080'
/* 개인 큐(/user/queue/notifications) 신호로 즉시 갱신되므로, 폴링은 재연결 공백을 메우는 백업 용도로 주기를 늘린다. */
const UNREAD_POLL_MS = 60000

interface NotificationItem {
  type: 'COMMENT' | 'CHAT' | 'PRICE_CHANGE'
  message: string
  productId: number
  roomId: number | null
  isRead: boolean
  occurredAt: string
}

function notificationHref(n: NotificationItem) {
  return n.type === 'CHAT' ? `/chat/${n.roomId}` : `/products/${n.productId}`
}

function formatNotifTime(iso: string) {
  const d = new Date(iso)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
  }
  return iso.slice(5, 10).replace('-', '.')
}

const NAV_LINKS = [
  { href: '/products',     label: '상품목록' },
  /* 실시간 딜: 백엔드 미구현. 버튼만 먼저 노출하고 클릭해도 아무 화면도 뜨지 않는다(기능 구현 후 연결 예정). */
  { href: '#',             label: '실시간 딜', disabled: true },
  { href: '/products/new', label: '상품등록' },
  { href: '/my-products',  label: '나의 마켓온', match: ['/my-products', '/favorites'] },
  { href: '/my-reports',   label: '신고내역' },
  { href: '/my-profile',   label: '내정보' },
]

export default function Header() {
  const pathname = usePathname()
  const [dark, setDark] = useState(false)
  const [loggedIn, setLoggedIn] = useState(() => !!getAccessToken())
  const [hasUnreadNotification, setHasUnreadNotification] = useState(false)
  const [notifOpen, setNotifOpen] = useState(false)
  const [notifications, setNotifications] = useState<NotificationItem[]>([])
  const [notifLoading, setNotifLoading] = useState(false)
  const notifWrapRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const handler = () => setLoggedIn(!!getAccessToken())
    window.addEventListener(AUTH_CHANGED_EVENT, handler)
    return () => window.removeEventListener(AUTH_CHANGED_EVENT, handler)
  }, [])

  function fetchUnreadCount() {
    apiFetch('/api/notifications/unread-count')
      .then(r => r.ok ? r.json() : null)
      .then(data => {
        if (data == null) return
        setHasUnreadNotification((data?.data?.unreadCount ?? 0) > 0)
      })
      .catch(() => {})
  }

  /* 백업 폴링 — 개인 큐 신호를 놓쳤을 때(재연결 공백 등) 갱신을 메운다. */
  useEffect(() => {
    if (!loggedIn) return
    fetchUnreadCount()
    const timer = setInterval(fetchUnreadCount, UNREAD_POLL_MS)
    return () => clearInterval(timer)
  }, [loggedIn])

  /* 실시간 배지 갱신 — 개인 큐(/user/queue/notifications)로 알림 신호가 오면 정확한 카운트를 재조회한다. */
  useEffect(() => {
    if (!loggedIn) return

    const client = new Client({
      brokerURL: `${WS_ORIGIN}/ws`,
      reconnectDelay: 3000,
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${getAccessToken() ?? ''}` }
      },
      onConnect: () => {
        client.subscribe('/user/queue/notifications', () => {
          fetchUnreadCount()
        })
      },
    })
    client.activate()

    return () => { client.deactivate() }
  }, [loggedIn])

  useEffect(() => {
    if (!notifOpen) return
    function handleClickOutside(e: MouseEvent) {
      if (notifWrapRef.current && !notifWrapRef.current.contains(e.target as Node)) {
        setNotifOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [notifOpen])

  async function toggleNotifPanel() {
    const next = !notifOpen
    setNotifOpen(next)
    if (!next) return

    setNotifLoading(true)
    try {
      const res = await apiFetch('/api/notifications')
      const data = await res.json().catch(() => null)
      setNotifications(res.ok ? (data?.data ?? []) : [])
    } finally {
      setNotifLoading(false)
    }

    setHasUnreadNotification(false)
    apiFetch('/api/notifications/read', { method: 'POST' }).catch(() => {})
  }

  async function handleLogout() {
    try {
      await logout()
    } finally {
      window.location.href = '/login'
    }
  }

  useEffect(() => {
    const saved = (() => {
      try { return localStorage.getItem('marketon-theme') } catch { return null }
    })()
    const system = window.matchMedia?.('(prefers-color-scheme: dark)').matches
    const isDark = saved ? saved === 'dark' : system
    // eslint-disable-next-line react-hooks/set-state-in-effect -- localStorage/matchMedia는 클라이언트에서만 읽을 수 있어 SSR 하이드레이션 이후에만 계산 가능
    setDark(isDark)
    applyTheme(isDark)

    const mq = window.matchMedia?.('(prefers-color-scheme: dark)')
    if (!mq) return
    const handler = (e: MediaQueryListEvent) => {
      if (!localStorage.getItem('marketon-theme')) {
        setDark(e.matches)
        applyTheme(e.matches)
      }
    }
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [])

  function applyTheme(isDark: boolean) {
    if (isDark) document.documentElement.setAttribute('data-theme', 'dark')
    else document.documentElement.removeAttribute('data-theme')
  }

  function toggleTheme() {
    const next = !dark
    setDark(next)
    applyTheme(next)
    try { localStorage.setItem('marketon-theme', next ? 'dark' : 'light') } catch {}
  }

  /* 관리자 화면은 자체 사이드바 레이아웃을 쓰므로 고객용 헤더를 숨긴다.
     (테마 초기화 useEffect는 계속 실행되도록 훅 아래에서 분기한다) */
  if (pathname?.startsWith('/admin')) return null

  return (
    <header>
      <div className="head-in">
        <Link className="logo" href="/">
          Market<span>ON</span>
        </Link>
        <nav className="main-nav">
          {NAV_LINKS.map(({ href, label, match, disabled }) => {
            const cls = (match ?? [href]).includes(pathname ?? '') ? 'on' : ''
            if (disabled) {
              return (
                <a key={label} href="#" className={cls} onClick={e => e.preventDefault()}>
                  {label}
                </a>
              )
            }
            return (
              <Link key={label} href={href} className={cls}>
                {label}
              </Link>
            )
          })}
        </nav>
        <div className="sp" />
        <button className="icon-btn" onClick={toggleTheme} aria-label="테마 전환" type="button">
          {dark ? (
            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
              <path d="M20 14.5A8 8 0 0 1 9.5 4a7 7 0 1 0 10.5 10.5z" />
            </svg>
          ) : (
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <circle cx="12" cy="12" r="4.2" />
              <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
            </svg>
          )}
        </button>
        <div className="notif-wrap" ref={notifWrapRef}>
          <button className="icon-btn notif-btn" onClick={toggleNotifPanel} aria-label="알림" type="button">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9z" />
              <path d="M13.7 21a2 2 0 0 1-3.4 0" />
            </svg>
            {loggedIn && hasUnreadNotification && <span className="notif-dot" />}
          </button>
          {notifOpen && (
            <div className="notif-panel">
              <div className="notif-panel-head">알림</div>
              {notifLoading ? (
                <div className="notif-empty">불러오는 중...</div>
              ) : notifications.length === 0 ? (
                <div className="notif-empty">새로운 알림이 없어요.</div>
              ) : (
                <ul className="notif-list">
                  {notifications.map((n, i) => (
                    <li key={i}>
                      <Link
                        href={notificationHref(n)}
                        className={`notif-item${n.isRead ? '' : ' unread'}`}
                        onClick={() => setNotifOpen(false)}
                      >
                        <span className="notif-item-msg">{n.message}</span>
                        <span className="notif-item-time">{formatNotifTime(n.occurredAt)}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
        <Link className="icon-btn" href="/chat" aria-label="채팅">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M21 11.5a8.5 8.5 0 0 1-12.3 7.6L3 21l1.9-5.7A8.5 8.5 0 1 1 21 11.5z" />
          </svg>
        </Link>
        {loggedIn ? (
          <button className="ghost-link" onClick={handleLogout} type="button">로그아웃</button>
        ) : (
          <Link className="ghost-link" href="/login">로그인</Link>
        )}
      </div>
    </header>
  )
}
