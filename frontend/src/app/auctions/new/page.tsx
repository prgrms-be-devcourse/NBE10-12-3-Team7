'use client'

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { apiFetch, bootstrapAutoLogin } from '@/lib/apiClient'
import { getAccessToken } from '@/lib/auth'
import styles from './page.module.css'

type LoadStatus = 'loading' | 'ready' | 'unauthenticated'

const ALLOWED_IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/gif', 'image/webp']
const MAX_FILE_BYTES = 5 * 1024 * 1024

export default function NewAuctionPage() {
  const [loadStatus, setLoadStatus] = useState<LoadStatus>('loading')

  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [startPrice, setStartPrice] = useState('')
  const [endAt, setEndAt] = useState('')
  const [imageUrl, setImageUrl] = useState('')
  const [uploading, setUploading] = useState(false)

  const [formMsg, setFormMsg] = useState<{ text: string; type: 'success' | 'error' } | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    let cancelled = false
    async function init() {
      if (!getAccessToken()) await bootstrapAutoLogin()
      if (cancelled) return
      setLoadStatus(getAccessToken() ? 'ready' : 'unauthenticated')
    }
    init()
    return () => { cancelled = true }
  }, [])

  async function handleImageSelect(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    if (!ALLOWED_IMAGE_TYPES.includes(file.type) || file.size > MAX_FILE_BYTES) {
      setFormMsg({ text: 'jpeg·png·gif·webp 형식, 5MB 이하만 업로드할 수 있어요.', type: 'error' })
      return
    }
    setUploading(true)
    try {
      const formData = new FormData()
      formData.append('files', file)
      const res = await apiFetch('/api/products/images', { method: 'POST', body: formData })
      const data = await res.json().catch(() => null)
      if (!res.ok) {
        setFormMsg({ text: data?.message ?? '이미지 업로드에 실패했습니다.', type: 'error' })
        return
      }
      setImageUrl(data?.data?.imageUrls?.[0] ?? '')
    } catch {
      setFormMsg({ text: '서버에 연결할 수 없습니다.', type: 'error' })
    } finally {
      setUploading(false)
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormMsg(null)

    if (!title.trim() || !startPrice.trim() || !endAt) {
      setFormMsg({ text: '제목·시작가·종료 시각은 필수예요.', type: 'error' })
      return
    }
    const endAtDate = new Date(endAt)
    if (endAtDate.getTime() <= Date.now()) {
      setFormMsg({ text: '종료 시각은 현재보다 미래여야 해요.', type: 'error' })
      return
    }

    setSubmitting(true)
    try {
      const res = await apiFetch('/api/auctions', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: title.trim(),
          imageUrl: imageUrl || null,
          description: description.trim() || null,
          startPrice: Number(startPrice),
          endAt: `${endAt}:00`,
        }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) {
        setFormMsg({ text: data?.message ?? '경매 등록 중 오류가 발생했습니다.', type: 'error' })
        setSubmitting(false)
        return
      }
      window.location.href = `/auctions/${data.data.auctionId}`
    } catch {
      setFormMsg({ text: '서버에 연결할 수 없습니다.', type: 'error' })
      setSubmitting(false)
    }
  }

  const msgCls = formMsg
    ? [styles.formMsg, styles.show, styles[formMsg.type]].join(' ')
    : styles.formMsg

  const introBlock = (
    <div className={styles.intro}>
      <span className={styles.badge}>⚡ 실시간 딜</span>
      <h1>경매 등록</h1>
      <p>시작가와 종료 시각을 정해서 실시간 경매를 열어보세요.</p>
    </div>
  )

  if (loadStatus === 'loading') {
    return <main className={styles.wrap}>{introBlock}<div className={styles.notice}><p>불러오는 중...</p></div></main>
  }
  if (loadStatus === 'unauthenticated') {
    return (
      <main className={styles.wrap}>
        {introBlock}
        <div className={styles.notice}>
          <p>로그인 후 이용할 수 있어요.</p>
          <Link href="/login" className="btn">로그인하기</Link>
        </div>
      </main>
    )
  }

  return (
    <main className={styles.wrap}>
      {introBlock}

      <div className={styles.card}>
        <div className={msgCls} role="alert">{formMsg?.text}</div>

        <form onSubmit={handleSubmit} noValidate>
          <div className={styles.field}>
            <label htmlFor="title">제목<span className={styles.req}>*</span></label>
            <input
              id="title" type="text"
              placeholder="경매에 올릴 상품명을 입력하세요" maxLength={100}
              value={title} onChange={e => setTitle(e.target.value)}
            />
          </div>

          <div className={styles.field}>
            <label>대표 이미지</label>
            {imageUrl && (
              <div className={styles.imagePreview}><img src={imageUrl} alt="" /></div>
            )}
            <label className={`${styles.uploadBtn}${uploading ? ' ' + styles.uploadBtnDisabled : ''}`}>
              <input
                type="file" accept={ALLOWED_IMAGE_TYPES.join(',')} hidden
                disabled={uploading} onChange={handleImageSelect}
              />
              {uploading ? '업로드 중...' : imageUrl ? '이미지 변경' : '+ 이미지 업로드'}
            </label>
          </div>

          <div className={styles.field}>
            <label htmlFor="description">설명</label>
            <textarea
              id="description" className={styles.textarea}
              placeholder="경매 상품에 대한 설명을 적어주세요."
              value={description} onChange={e => setDescription(e.target.value)}
            />
          </div>

          <div className={styles.row2}>
            <div className={styles.field}>
              <label htmlFor="startPrice">시작가<span className={styles.req}>*</span></label>
              <div className={styles.priceIn}>
                <input
                  id="startPrice" type="text" inputMode="numeric" placeholder="0"
                  value={startPrice ? Number(startPrice).toLocaleString('ko-KR') : ''}
                  onChange={e => setStartPrice(e.target.value.replace(/[^\d]/g, ''))}
                />
                <span className={styles.won}>원</span>
              </div>
            </div>
            <div className={styles.field}>
              <label htmlFor="endAt">종료 시각<span className={styles.req}>*</span></label>
              <input
                id="endAt" type="datetime-local"
                value={endAt} onChange={e => setEndAt(e.target.value)}
              />
            </div>
          </div>

          <div className={styles.actions}>
            <Link href="/auctions" className={styles.btnGhost}>취소</Link>
            <button type="submit" className={styles.btnSubmit} disabled={submitting || uploading}>
              {submitting ? '등록 중...' : '경매 등록하기'}
            </button>
          </div>
        </form>
      </div>
    </main>
  )
}
