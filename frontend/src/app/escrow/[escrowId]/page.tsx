'use client'

import Link from 'next/link'
import { useParams } from 'next/navigation'
import { useEffect, useRef, useState } from 'react'
import { apiFetch } from '@/lib/apiClient'
import ConfirmModal from '@/components/ConfirmModal'
import styles from './page.module.css'

type EscrowStatus = 'IN_ESCROW' | 'DONE' | 'CANCELED'

interface Escrow {
  escrowId: number
  productId: number
  buyerId: number
  sellerId: number
  amount: number
  status: EscrowStatus
  createdAt: string
  closedAt: string | null
}

interface Product {
  productId: number
  title: string
  thumbnailUrl: string | null
  sellerNickname: string
}

type PageStatus = 'loading' | 'ready' | 'error'

const STATUS_LABEL: Record<EscrowStatus, string> = {
  IN_ESCROW: '예치중',
  DONE: '구매확정 완료',
  CANCELED: '거래 취소됨',
}

function escrowStorageKey(productId: number) {
  return `mo_escrow_product_${productId}`
}

function priceText(amount: number) {
  return amount === 0 ? '나눔' : amount.toLocaleString('ko-KR') + '원'
}

function formatDate(iso: string) {
  return iso.slice(0, 16).replace('T', ' ')
}

export default function EscrowStatusPage() {
  const { escrowId } = useParams<{ escrowId: string }>()

  const [status, setStatus] = useState<PageStatus>('loading')
  const [errorMsg, setErrorMsg] = useState('')
  const [escrow, setEscrow] = useState<Escrow | null>(null)
  const [product, setProduct] = useState<Product | null>(null)
  const [myMemberId, setMyMemberId] = useState<number | null>(null)
  const [processing, setProcessing] = useState(false)
  const [showCancelConfirm, setShowCancelConfirm] = useState(false)
  const [showConfirmPurchase, setShowConfirmPurchase] = useState(false)

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
    Promise.all([
      apiFetch(`/api/escrows/${escrowId}`).then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) })),
      apiFetch('/api/members/me').then(async r => ({ ok: r.ok, data: await r.json().catch(() => null) })),
    ]).then(async ([escrowRes, meRes]) => {
      if (cancelled) return
      if (!escrowRes.ok) {
        setErrorMsg(escrowRes.data?.message ?? '거래 정보를 찾을 수 없어요.')
        setStatus('error')
        return
      }
      const escrowData: Escrow = escrowRes.data.data
      setEscrow(escrowData)
      if (meRes.ok) setMyMemberId(meRes.data?.data?.memberId ?? null)

      const productRes = await fetch(`/api/products/${escrowData.productId}`).then(r => r.ok ? r.json() : null).catch(() => null)
      if (cancelled) return
      setProduct(productRes?.data ?? null)
      setStatus('ready')
    }).catch(() => {
      if (!cancelled) { setErrorMsg('거래 정보를 불러오지 못했습니다.'); setStatus('error') }
    })
    return () => { cancelled = true }
  }, [escrowId])

  async function confirmPurchase() {
    if (!escrow) return
    setShowConfirmPurchase(false)
    setProcessing(true)
    try {
      const res = await apiFetch(`/api/escrows/${escrow.escrowId}/confirm`, { method: 'POST' })
      const data = await res.json().catch(() => null)
      if (!res.ok) { showToast(data?.message ?? '구매확정 중 오류가 발생했습니다.'); return }
      setEscrow(data.data)
      try { localStorage.removeItem(escrowStorageKey(escrow.productId)) } catch {}
      showToast('구매를 확정했어요')
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    } finally {
      setProcessing(false)
    }
  }

  async function cancelPurchase() {
    if (!escrow) return
    setShowCancelConfirm(false)
    setProcessing(true)
    try {
      const res = await apiFetch(`/api/escrows/${escrow.escrowId}/cancel`, { method: 'POST' })
      const data = await res.json().catch(() => null)
      if (!res.ok) { showToast(data?.message ?? '거래 취소 중 오류가 발생했습니다.'); return }
      setEscrow(data.data)
      try { localStorage.removeItem(escrowStorageKey(escrow.productId)) } catch {}
      showToast('거래를 취소했어요')
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    } finally {
      setProcessing(false)
    }
  }

  if (status === 'loading') {
    return <main className={styles.wrap}><div className={styles.empty}><p>불러오는 중...</p></div></main>
  }
  if (status === 'error' || !escrow) {
    return (
      <main className={styles.wrap}>
        <div className={styles.empty}>
          <p>{errorMsg}</p>
          <Link href="/products" className="btn ghost">상품목록으로</Link>
        </div>
      </main>
    )
  }

  const isBuyer = myMemberId !== null && myMemberId === escrow.buyerId

  return (
    <main className={styles.wrap}>
      <div className={styles.headRow}>
        <h1>안심결제 거래</h1>
        <p>대금을 예치했다가 구매를 확정하면 판매자에게 정산되는 안전거래예요.</p>
      </div>

      <div className={styles.card}>
        <div className={styles.top}>
          <div className={styles.thumb}>
            {product?.thumbnailUrl && <img src={product.thumbnailUrl} alt={product.title} />}
          </div>
          <div className={styles.info}>
            <div className={styles.title}>{product?.title ?? `상품 #${escrow.productId}`}</div>
            {product?.sellerNickname && <div className={styles.seller}>판매자 {product.sellerNickname}</div>}
            <div className={styles.amount}>{priceText(escrow.amount)}</div>
          </div>
          <span className={`${styles.statusTag} ${styles[`s_${escrow.status}`]}`}>
            {STATUS_LABEL[escrow.status]}
          </span>
        </div>

        <div className={styles.meta}>
          <span>거래번호 #{escrow.escrowId}</span>
          <span>예치일 {formatDate(escrow.createdAt)}</span>
          {escrow.closedAt && <span>종료일 {formatDate(escrow.closedAt)}</span>}
        </div>

        {escrow.status === 'IN_ESCROW' && isBuyer && (
          <div className={styles.actions}>
            <button type="button" className={styles.btnCancel} onClick={() => setShowCancelConfirm(true)} disabled={processing}>
              거래 취소
            </button>
            <button type="button" className={styles.btnConfirm} onClick={() => setShowConfirmPurchase(true)} disabled={processing}>
              {processing ? '처리 중...' : '구매확정'}
            </button>
          </div>
        )}
        {escrow.status === 'IN_ESCROW' && !isBuyer && (
          <p className={styles.hint}>이 거래의 구매자만 구매확정/취소를 할 수 있어요.</p>
        )}

        <Link href={`/products/${escrow.productId}`} className={styles.backLink}>상품 페이지로 돌아가기</Link>
      </div>

      {showCancelConfirm && (
        <ConfirmModal
          message="거래를 취소할까요? 대금이 환불(가정)돼요."
          confirmText="취소하기"
          cancelText="닫기"
          danger
          onConfirm={cancelPurchase}
          onCancel={() => setShowCancelConfirm(false)}
        />
      )}

      {showConfirmPurchase && (
        <ConfirmModal
          message={'물건을 확인하셨나요?\n구매확정 후에는 되돌릴 수 없어요.'}
          confirmText="확인"
          cancelText="취소"
          confirmColor="var(--blue)"
          onConfirm={confirmPurchase}
          onCancel={() => setShowConfirmPurchase(false)}
        />
      )}

      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>
    </main>
  )
}
