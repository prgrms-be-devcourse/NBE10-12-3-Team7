'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { Client } from '@stomp/stompjs'
import { useEffect, useRef, useState } from 'react'
import { apiFetch, bootstrapAutoLogin } from '@/lib/apiClient'
import { AUTH_CHANGED_EVENT, getAccessToken } from '@/lib/auth'
import styles from './page.module.css'

/* 로컬 dev는 Next 서버(:3000)가 WebSocket 업그레이드를 프록시하지 못해 백엔드에 직접 접속한다(chat/Header와 동일). */
const WS_ORIGIN = process.env.NEXT_PUBLIC_WS_ORIGIN ?? 'ws://localhost:8080'

type AuctionStatus = 'ONGOING' | 'ENDED'

interface Auction {
  auctionId: number
  sellerId: number
  title: string
  imageUrl: string | null
  description: string | null
  currentPrice: number
  highestBidderId: number | null
  status: AuctionStatus
  endAt: string
  createdAt: string
}

interface AuctionStateMessage {
  auctionId: number
  currentPrice: number
  highestBidderId: number | null
  status: AuctionStatus
  endAt: string
}

interface ErrorMessage {
  status: number
  error: string
  message: string
  timestamp: string
}

type PageStatus = 'loading' | 'ready' | 'error' | 'unauthenticated'

function priceText(price: number) {
  return price.toLocaleString('ko-KR') + '원'
}

function formatDate(iso: string) {
  return iso.slice(0, 16).replace('T', ' ')
}

export default function AuctionDetailPage() {
  const { auctionId } = useParams<{ auctionId: string }>()

  const [status, setStatus] = useState<PageStatus>('loading')
  const [errorMsg, setErrorMsg] = useState('')
  const [auction, setAuction] = useState<Auction | null>(null)
  const [myMemberId, setMyMemberId] = useState<number | null>(null)

  const [bidAmount, setBidAmount] = useState('')
  const [bidding, setBidding] = useState(false)
  const stompRef = useRef<Client | null>(null)

  /* 새로고침 직후에는 Access Token이 메모리에 없다가 bootstrapAutoLogin이 비동기로 복구한다.
     STOMP 연결은 이 복구가 끝난 뒤에도 다시 시도되어야 하므로 AUTH_CHANGED_EVENT를 구독한다(Header와 동일). */
  const [loggedIn, setLoggedIn] = useState(() => !!getAccessToken())
  useEffect(() => {
    const handler = () => setLoggedIn(!!getAccessToken())
    window.addEventListener(AUTH_CHANGED_EVENT, handler)
    return () => window.removeEventListener(AUTH_CHANGED_EVENT, handler)
  }, [])

  const [toastText, setToastText] = useState('')
  const [toastOn, setToastOn] = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  function showToast(msg: string) {
    setToastText(msg); setToastOn(true)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => setToastOn(false), 1900)
  }

  useEffect(() => {
    let cancelled = false
    async function load() {
      if (!getAccessToken()) await bootstrapAutoLogin()
      if (cancelled) return
      if (!getAccessToken()) { setStatus('unauthenticated'); return }

      const [auctionRes, meRes] = await Promise.all([
        apiFetch(`/api/auctions/${auctionId}`).then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) })),
        apiFetch('/api/members/me').then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) })),
      ])
      if (cancelled) return
      if (!auctionRes.ok) {
        setErrorMsg(auctionRes.data?.message ?? '경매를 찾을 수 없어요.')
        setStatus('error')
        return
      }
      setAuction(auctionRes.data.data)
      if (meRes.ok) setMyMemberId(meRes.data?.data?.memberId ?? null)
      setStatus('ready')
    }
    load().catch(() => {
      if (!cancelled) { setErrorMsg('경매 정보를 불러오지 못했습니다.'); setStatus('error') }
    })
    return () => { cancelled = true }
  }, [auctionId])

  useEffect(() => {
    if (!loggedIn) return

    const client = new Client({
      brokerURL: `${WS_ORIGIN}/ws`,
      reconnectDelay: 3000,
      beforeConnect: () => {
        client.connectHeaders = { Authorization: `Bearer ${getAccessToken() ?? ''}` }
      },
      onConnect: () => {
        client.subscribe(`/topic/auction/${auctionId}`, msg => {
          const state: AuctionStateMessage = JSON.parse(msg.body)
          setAuction(prev => prev ? { ...prev, ...state } : prev)
        })
        client.subscribe('/user/queue/errors', msg => {
          const err: ErrorMessage = JSON.parse(msg.body)
          showToast(err.message ?? '입찰 처리 중 오류가 발생했습니다.')
          setBidding(false)
        })
      },
    })
    stompRef.current = client
    client.activate()

    return () => {
      stompRef.current = null
      client.deactivate()
    }
  }, [auctionId, loggedIn])

  function handleBidSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!auction || bidding) return
    const amount = Number(bidAmount)
    if (!bidAmount || amount <= auction.currentPrice) {
      showToast('현재가보다 높은 금액을 입력해주세요.')
      return
    }
    const client = stompRef.current
    if (!client?.connected) {
      showToast('실시간 연결이 끊겼어요. 잠시 후 다시 시도해주세요.')
      return
    }
    setBidding(true)
    client.publish({
      destination: `/app/auction/${auctionId}/bid`,
      body: JSON.stringify({ amount }),
    })
    setBidAmount('')
    setBidding(false)
  }

  if (status === 'loading') {
    return <main className={styles.wrap}><div className={styles.empty}><p>불러오는 중...</p></div></main>
  }
  if (status === 'unauthenticated') {
    return (
      <main className={styles.wrap}>
        <div className={styles.empty}>
          <p>로그인 후 이용할 수 있어요.</p>
          <Link href="/login" className="btn">로그인하기</Link>
        </div>
      </main>
    )
  }
  if (status === 'error' || !auction) {
    return (
      <main className={styles.wrap}>
        <div className={styles.empty}>
          <p>{errorMsg}</p>
          <Link href="/auctions" className="btn ghost">경매 목록으로</Link>
        </div>
      </main>
    )
  }

  const isSeller = myMemberId !== null && myMemberId === auction.sellerId
  const isWinner = auction.status === 'ENDED' && myMemberId !== null && myMemberId === auction.highestBidderId

  return (
    <main className={styles.wrap}>
      <div className={styles.card}>
        <div className={styles.top}>
          <div className={styles.thumb}>
            {auction.imageUrl
              ? <img src={auction.imageUrl} alt={auction.title} />
              : <span className={styles.thumbPh}>NO IMG</span>}
            <span className={`${styles.statusTag} ${styles[`s_${auction.status}`]}`}>
              {auction.status === 'ONGOING' ? '진행중' : '종료'}
            </span>
          </div>
          <div className={styles.info}>
            <div className={styles.title}>{auction.title}</div>
            {auction.description && <p className={styles.desc}>{auction.description}</p>}
            <div className={styles.priceLabel}>{auction.status === 'ONGOING' ? '현재가' : '최종 낙찰가'}</div>
            <div className={styles.price}>{priceText(auction.currentPrice)}</div>
          </div>
        </div>

        <div className={styles.meta}>
          <span>경매번호 #{auction.auctionId}</span>
          <span>{auction.status === 'ONGOING' ? `마감 ${formatDate(auction.endAt)}` : `종료 ${formatDate(auction.endAt)}`}</span>
        </div>

        {auction.status === 'ENDED' && (
          <div className={styles.winnerBox}>
            {auction.highestBidderId
              ? (isWinner ? '축하해요! 이 경매의 낙찰자예요.' : `낙찰자 회원 #${auction.highestBidderId}`)
              : '입찰자가 없어 유찰되었어요.'}
          </div>
        )}

        {auction.status === 'ONGOING' && isSeller && (
          <p className={styles.hint}>내가 등록한 경매는 입찰할 수 없어요.</p>
        )}
        {auction.status === 'ONGOING' && !isSeller && (
          <form className={styles.bidRow} onSubmit={handleBidSubmit}>
            <div className={styles.bidIn}>
              <input
                type="text" inputMode="numeric"
                placeholder={`${(auction.currentPrice + 1).toLocaleString('ko-KR')} 이상`}
                value={bidAmount ? Number(bidAmount).toLocaleString('ko-KR') : ''}
                onChange={e => setBidAmount(e.target.value.replace(/[^\d]/g, ''))}
              />
              <span className={styles.won}>원</span>
            </div>
            <button type="submit" className={styles.btnBid} disabled={bidding}>
              {bidding ? '입찰 중...' : '입찰하기'}
            </button>
          </form>
        )}

        <Link href="/auctions" className={styles.backLink}>경매 목록으로 돌아가기</Link>
      </div>

      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>
    </main>
  )
}
