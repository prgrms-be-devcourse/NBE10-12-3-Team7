'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { apiFetch, bootstrapAutoLogin } from '@/lib/apiClient'
import { getAccessToken } from '@/lib/auth'
import styles from './page.module.css'

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

type PageStatus = 'loading' | 'ready' | 'error' | 'unauthenticated'

function priceText(price: number) {
  return price.toLocaleString('ko-KR') + '원'
}

function formatEndAt(iso: string) {
  return iso.slice(0, 16).replace('T', ' ')
}

export default function AuctionsPage() {
  const [auctions, setAuctions] = useState<Auction[]>([])
  const [status, setStatus] = useState<PageStatus>('loading')

  useEffect(() => {
    let cancelled = false
    async function load() {
      if (!getAccessToken()) await bootstrapAutoLogin()
      if (cancelled) return
      if (!getAccessToken()) { setStatus('unauthenticated'); return }

      const res = await apiFetch('/api/auctions')
      const data = await res.json().catch(() => null)
      if (cancelled) return
      if (!res.ok) { setStatus('error'); return }
      setAuctions(data?.data ?? [])
      setStatus('ready')
    }
    load().catch(() => { if (!cancelled) setStatus('error') })
    return () => { cancelled = true }
  }, [])

  return (
    <main className={styles.wrap}>
      <div className={styles.headRow}>
        <div>
          <span className={styles.badge}>⚡ 실시간 딜</span>
          <h1>지금 진행 중인 경매</h1>
          <p>실시간으로 입찰하고 최저가로 득템해보세요.</p>
        </div>
        <Link href="/auctions/new" className="btn">경매 등록</Link>
      </div>

      {status === 'loading' && (
        <div className={styles.empty}><p>불러오는 중...</p></div>
      )}

      {status === 'unauthenticated' && (
        <div className={styles.empty}>
          <p>로그인 후 이용할 수 있어요.</p>
          <Link href="/login" className="btn">로그인하기</Link>
        </div>
      )}

      {status === 'error' && (
        <div className={styles.empty}>
          <div className={styles.emptyIcon}>⚠️</div>
          <p>경매 목록을 불러오지 못했어요.</p>
        </div>
      )}

      {status === 'ready' && (
        auctions.length > 0 ? (
          <div className={styles.grid}>
            {auctions.map(a => (
              <Link key={a.auctionId} href={`/auctions/${a.auctionId}`} className={styles.card}>
                <div className={styles.thumb}>
                  {a.imageUrl
                    ? <img src={a.imageUrl} alt={a.title} className={styles.thumbImg} />
                    : <span className={styles.thumbPh}>NO IMG</span>}
                  <span className={`${styles.statusTag} ${styles[`s_${a.status}`]}`}>
                    {a.status === 'ONGOING' ? '진행중' : '종료'}
                  </span>
                </div>
                <div className={styles.body}>
                  <div className={styles.title}>{a.title}</div>
                  <div className={styles.price}>{priceText(a.currentPrice)}</div>
                  <div className={styles.endAt}>
                    {a.status === 'ONGOING' ? `마감 ${formatEndAt(a.endAt)}` : '경매 종료'}
                  </div>
                </div>
              </Link>
            ))}
          </div>
        ) : (
          <div className={styles.empty}>
            <div className={styles.emptyIcon}>⚡</div>
            <p>등록된 경매가 없어요.</p>
            <Link href="/auctions/new" className="btn">첫 경매 등록하기</Link>
          </div>
        )
      )}
    </main>
  )
}
