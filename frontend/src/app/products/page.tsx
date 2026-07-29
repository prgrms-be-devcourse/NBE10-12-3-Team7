'use client'

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { apiFetch, bootstrapAutoLogin } from '@/lib/apiClient'
import { getAccessToken } from '@/lib/auth'
import type { TradeStatus } from '@/lib/tradeStatus'
import RegionCascadeSelect from '@/components/RegionCascadeSelect'
import styles from './page.module.css'

interface Category {
  id: number
  name: string
}

interface MemberLocation {
  regionCode: string
  regionFullName: string
  sortOrder: number
  active: boolean
}

interface Product {
  productId: number
  memberId: number
  categoryId: number
  title: string
  price: number
  tradeStatus: TradeStatus
  regionFullName: string
  viewCount: number
  favoriteCount: number
  thumbnailUrl: string | null
  hidden: boolean
}

interface ProductPage {
  items: Product[]
  nextCursor: number | null
  hasNext: boolean
}

interface MyFavorite {
  product: { productId: number }
}

type PageStatus = 'loading' | 'ready' | 'error'

const SORT_OPTIONS = [
  { value: 'latest',     label: '최신순' },
  { value: 'likes',      label: '관심 많은순' },
  { value: 'price_asc',  label: '낮은 가격순' },
  { value: 'price_desc', label: '높은 가격순' },
]

/* 상단 활동 배너용 목표 수치 — 실제 집계 API가 없어 디자인 시안의 예시 값을 그대로 사용 */
const STAT_TARGETS = [1204, 1892, 5640]
const PRODUCT_PAGE_SIZE = 30
const PRODUCT_POLL_MS = 5000

function priceText(price: number) {
  return price === 0 ? '나눔' : price.toLocaleString('ko-KR') + '원'
}

function badgeInfo(product: Product): { label: string; cls: string } | null {
  if (product.tradeStatus === 'RESERVED')  return { label: '예약중',  cls: styles.badgeReserved }
  if (product.tradeStatus === 'COMPLETED') return { label: '거래완료', cls: styles.badgeDone }
  if (product.price === 0)                 return { label: '나눔',   cls: styles.badgeFree }
  return { label: '판매중', cls: styles.badgeSale }
}

export default function ProductsPage() {
  const [status, setStatus] = useState<PageStatus>('loading')
  const [products, setProducts] = useState<Product[]>([])
  const [nextCursor, setNextCursor] = useState<number | null>(null)
  const [hasNext, setHasNext] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)
  const [categories, setCategories] = useState<Category[]>([])

  const [search,     setSearch]     = useState('')
  const [categoryId, setCategoryId] = useState<number | 'all'>('all')
  const [sort,       setSort]       = useState('latest')
  const [sortOpen,   setSortOpen]   = useState(false)
  const sortWrapRef = useRef<HTMLDivElement>(null)

  /* ── 내 동네 설정 ── */
  const [myLocations,     setMyLocations]     = useState<MemberLocation[]>([])
  const [activeRegionCode, setActiveRegionCode] = useState('')
  const [regionOpen,    setRegionOpen]    = useState(false)
  const [addMode,       setAddMode]       = useState(false)

  /* ── 관심 상품 ── */
  const [favoriteIds, setFavoriteIds] = useState<Set<number>>(new Set())

  /* ── 활동 배너 카운트업 ── */
  const [counts, setCounts] = useState([0, 0, 0])

  const [toastText, setToastText] = useState('')
  const [toastOn,   setToastOn]   = useState(false)
  const toastTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  /* ── 목록 폴링 ── */
  const isPollingRef = useRef(false)
  const hasExtraPagesRef = useRef(false)

  function showToast(msg: string) {
    setToastText(msg); setToastOn(true)
    if (toastTimer.current) clearTimeout(toastTimer.current)
    toastTimer.current = setTimeout(() => setToastOn(false), 1900)
  }

  /* 활동 배너 숫자 카운트업 애니메이션 */
  useEffect(() => {
    const dur = 1400
    const t0 = Date.now()
    const ease = (x: number) => 1 - Math.pow(1 - x, 3)
    const timer = setInterval(() => {
      const p = Math.min(1, (Date.now() - t0) / dur)
      const e = ease(p)
      setCounts(STAT_TARGETS.map(v => Math.round(v * e)))
      if (p >= 1) clearInterval(timer)
    }, 33)
    return () => clearInterval(timer)
  }, [])

  /* 카테고리·지역 목록, 내 동네, 관심 상품 목록
     새로고침 직후에는 액세스 토큰이 메모리에서 초기화되고 AuthBootstrap의 조용한 재로그인이
     아직 끝나지 않은 상태라, getAccessToken() 스냅샷만으로 로그인 여부를 판단하면 실제로는
     로그인된 사용자인데도 내 동네/관심상품 조회가 스킵된다. bootstrapAutoLogin()을 먼저
     기다려 재로그인 결과를 반영한 뒤 로그인 여부를 판단한다(리다이렉트 없이 안전하게 시도만 함). */
  useEffect(() => {
    let cancelled = false
    bootstrapAutoLogin().then(loggedIn => {
      if (cancelled) return null
      return Promise.all([
        fetch('/api/categories').then(r => r.json()).catch(() => null),
        loggedIn ? apiFetch('/api/members/me/locations').then(r => r.ok ? r.json() : null).catch(() => null) : Promise.resolve(null),
        loggedIn ? apiFetch('/api/members/me/favorites').then(r => r.ok ? r.json() : null).catch(() => null) : Promise.resolve(null),
      ])
    }).then(results => {
      if (cancelled || !results) return
      const [catRes, locRes, favRes] = results
      setCategories(catRes?.data ?? [])

      const locs: MemberLocation[] = locRes?.data ?? []
      const ordered = locs.slice().sort((a, b) => a.sortOrder - b.sortOrder)
      setMyLocations(ordered)
      const active = ordered.find(l => l.active)
      if (active) setActiveRegionCode(active.regionCode)

      const favs: MyFavorite[] = favRes?.data ?? []
      setFavoriteIds(new Set(favs.map(f => f.product.productId)))
    })
    return () => { cancelled = true }
  }, [])

  const fetchProductPage = useCallback(async (cursor?: number | null): Promise<ProductPage> => {
    const params = new URLSearchParams()
    params.set('size', String(PRODUCT_PAGE_SIZE))
    if (activeRegionCode) params.append('regionCodes', activeRegionCode)
    if (cursor != null) params.set('cursor', String(cursor))

    const query = params.toString()
    const res = await fetch(query ? `/api/products?${query}` : '/api/products')
    const data = await res.json().catch(() => null)
    if (!res.ok) throw new Error(data?.message ?? '상품 목록 조회 실패')

    return data?.data ?? { items: [], nextCursor: null, hasNext: false }
  }, [activeRegionCode])

  /* 상품 목록 — 활성 동네가 있으면 해당 지역으로 필터링해 조회 */
  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- activeRegionCode 변경 시 첫 페이지를 다시 조회하며 로딩 상태를 표시한다.
    setStatus('loading')
    hasExtraPagesRef.current = false

    fetchProductPage()
      .then(page => {
        if (cancelled) return
        setProducts(page.items)
        setNextCursor(page.nextCursor)
        setHasNext(page.hasNext)
        setStatus('ready')
      })
      .catch(() => {
        if (!cancelled) {
          setProducts([])
          setNextCursor(null)
          setHasNext(false)
          setStatus('error')
        }
      })
    return () => { cancelled = true }
  }, [fetchProductPage])

  /* 게시글 목록 폴링 — 로딩 스피너 없이 조용히 첫 페이지 기준으로 동기화 */
  useEffect(() => {
    if (status !== 'ready') return

    const timer = setInterval(async () => {
      if (isPollingRef.current) return
      isPollingRef.current = true
      try {
        const page = await fetchProductPage()
        if (hasExtraPagesRef.current) {
          setProducts(prev => [...page.items, ...prev.slice(PRODUCT_PAGE_SIZE)])
        } else {
          setProducts(page.items)
          setNextCursor(page.nextCursor)
          setHasNext(page.hasNext)
        }
      } catch {
        // 폴링 실패는 조용히 무시하고 다음 주기에 재시도한다.
      } finally {
        isPollingRef.current = false
      }
    }, PRODUCT_POLL_MS)

    return () => clearInterval(timer)
  }, [status, fetchProductPage])

  async function loadMoreProducts() {
    if (loadingMore || !hasNext || nextCursor == null) return

    setLoadingMore(true)
    try {
      const page = await fetchProductPage(nextCursor)
      setProducts(prev => [...prev, ...page.items])
      setNextCursor(page.nextCursor)
      setHasNext(page.hasNext)
      hasExtraPagesRef.current = true
    } catch {
      showToast('상품을 더 불러오지 못했습니다.')
    } finally {
      setLoadingMore(false)
    }
  }

  /* ── 동네 설정 모달 ── */
  function openRegion() { setRegionOpen(true); setAddMode(false) }
  function closeRegion() { setRegionOpen(false); setAddMode(false) }

  async function persistRegions(next: MemberLocation[]) {
    try {
      const res = await apiFetch('/api/members/me/locations', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ regionCodes: next.map(l => l.regionCode) }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) {
        showToast(data?.message ?? '동네 설정 중 오류가 발생했습니다.')
        return
      }
      const locs: MemberLocation[] = data?.data ?? []
      setMyLocations(locs.slice().sort((a, b) => a.sortOrder - b.sortOrder))
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    }
  }

  function addRegion(regionCode: string, regionFullName: string) {
    if (!getAccessToken()) { showToast('로그인 후 이용할 수 있어요'); return }
    setAddMode(false)
    if (myLocations.length >= 2 || myLocations.some(l => l.regionCode === regionCode)) return
    const next = [...myLocations, { regionCode, regionFullName, sortOrder: myLocations.length, active: false }]
    persistRegions(next)
    if (!activeRegionCode) setActiveRegionCode(regionCode)
  }

  function removeRegion(regionCode: string) {
    if (!getAccessToken()) { showToast('로그인 후 이용할 수 있어요'); return }
    const next = myLocations.filter(l => l.regionCode !== regionCode)
    persistRegions(next)
    if (activeRegionCode === regionCode) setActiveRegionCode(next[0]?.regionCode ?? '')
  }

  function selectActive(regionCode: string) {
    setActiveRegionCode(regionCode)
    setRegionOpen(false)
  }

  const regionLabel = myLocations.length === 0
    ? '내 동네 설정'
    : (myLocations.find(l => l.regionCode === activeRegionCode)?.regionFullName ?? myLocations[0].regionFullName)

  /* ── 정렬 드롭다운 바깥 클릭 시 닫기 ── */
  useEffect(() => {
    if (!sortOpen) return
    function handleClickOutside(e: MouseEvent) {
      if (sortWrapRef.current && !sortWrapRef.current.contains(e.target as Node)) {
        setSortOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [sortOpen])

  /* ── 관심 토글 ── */
  async function toggleFavorite(e: React.MouseEvent, product: Product) {
    e.preventDefault()
    e.stopPropagation()
    if (!getAccessToken()) { showToast('로그인 후 이용할 수 있어요'); return }

    const liked = favoriteIds.has(product.productId)
    try {
      const res = await apiFetch(`/api/products/${product.productId}/favorites`, {
        method: liked ? 'DELETE' : 'POST',
      })
      if (!res.ok) {
        const data = await res.json().catch(() => null)
        showToast(data?.message ?? '처리 중 오류가 발생했습니다.')
        return
      }
      setFavoriteIds(prev => {
        const next = new Set(prev)
        if (liked) next.delete(product.productId)
        else next.add(product.productId)
        return next
      })
      setProducts(prev => prev.map(p => p.productId === product.productId
        ? { ...p, favoriteCount: p.favoriteCount + (liked ? -1 : 1) }
        : p))
    } catch {
      showToast('서버에 연결할 수 없습니다.')
    }
  }

  function handleSearch(e: React.FormEvent) {
    e.preventDefault()
  }

  const filtered = useMemo(() => {
    let list = products
    if (categoryId !== 'all') list = list.filter(p => p.categoryId === categoryId)
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(p => p.title.toLowerCase().includes(q) || p.regionFullName.includes(search.trim()))
    }
    switch (sort) {
      case 'likes':      list = [...list].sort((a, b) => b.favoriteCount - a.favoriteCount); break
      case 'price_asc':  list = [...list].sort((a, b) => a.price - b.price); break
      case 'price_desc': list = [...list].sort((a, b) => b.price - a.price); break
    }
    return list
  }, [products, categoryId, search, sort])

  return (
    <main className={styles.wrap}>
      {/* 활동 배너 */}
      <section className={styles.band}>
        <div className={styles.bandLead}>
          <div className={styles.bandTag}>
            <span className={styles.pulseDot}><span /><span /></span>
            지금 우리 동네는 거래 중
          </div>
          <div className={styles.bandHead}>오늘도 이웃들이<br />활발하게 나누는 중이에요</div>
        </div>
        <div className={styles.bandStats}>
          <div>
            <div className={styles.bandStatV}>{counts[0].toLocaleString('ko-KR')}<span>명</span></div>
            <div className={styles.bandStatL}>지금 거래 중인 이웃</div>
          </div>
          <div>
            <div className={styles.bandStatV}>{counts[1].toLocaleString('ko-KR')}<span>개</span></div>
            <div className={styles.bandStatL}>오늘 올라온 새 매물</div>
          </div>
          <div>
            <div className={styles.bandStatV}>{counts[2].toLocaleString('ko-KR')}<span>건</span></div>
            <div className={styles.bandStatL}>이번 주 거래 완료</div>
          </div>
        </div>
      </section>

      {/* 동네 + 검색 */}
      <div className={styles.locRow}>
        <button type="button" className={styles.locBtn} onClick={openRegion}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" strokeWidth="2.2"><path d="M12 21s7-5.6 7-11a7 7 0 1 0-14 0c0 5.4 7 11 7 11z" /><circle cx="12" cy="10" r="2.4" fill="var(--primary)" stroke="none" /></svg>
          <span>{regionLabel}</span>
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"><path d="M6 9l6 6 6-6" /></svg>
        </button>

        <form className={styles.searchBar} onSubmit={handleSearch}>
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="var(--text-muted)" strokeWidth="2.2"><circle cx="11" cy="11" r="7" /><path d="M21 21l-4-4" /></svg>
          <input
            placeholder="우리 동네 중고거래, 무엇을 찾으세요?"
            value={search}
            onChange={e => setSearch(e.target.value)}
          />
        </form>
      </div>

      {/* 내 동네 설정 모달 */}
      {regionOpen && (
        <div className={styles.modalOverlay} onClick={closeRegion}>
          <div className={styles.modal} onClick={e => e.stopPropagation()}>
            <div className={styles.modalHead}>
              <div>
                <div className={styles.modalTitle}>내 동네 설정</div>
                <div className={styles.modalDesc}>지역 기반 거래를 위해 최대 2개의 동네를 설정할 수 있어요.</div>
              </div>
              <button type="button" className={styles.modalClose} aria-label="닫기" onClick={closeRegion}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><path d="M18 6L6 18M6 6l12 12" /></svg>
              </button>
            </div>

            <div className={styles.modalList}>
              {myLocations.map(loc => (
                <div key={loc.regionCode} className={styles.regRow}>
                  <button type="button" className={styles.regSelect} onClick={() => selectActive(loc.regionCode)}>
                    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="var(--primary)" strokeWidth="2.2"><path d="M12 21s7-5.6 7-11a7 7 0 1 0-14 0c0 5.4 7 11 7 11z" /><circle cx="12" cy="10" r="2.4" fill="var(--primary)" stroke="none" /></svg>
                    {loc.regionFullName}
                    {loc.regionCode === activeRegionCode && <span className={styles.regActiveTag}>보는 중</span>}
                  </button>
                  <button type="button" className={styles.regRemove} aria-label="동네 삭제" onClick={() => removeRegion(loc.regionCode)}>
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><path d="M18 6L6 18M6 6l12 12" /></svg>
                  </button>
                </div>
              ))}

              {myLocations.length === 0 && (
                <div className={styles.noRegions}>
                  아직 설정된 동네가 없어요.<br />아래 <b>동네 추가</b>로 내 동네를 등록해보세요.
                </div>
              )}

              {myLocations.length < 2 && !addMode && (
                <button type="button" className={styles.addRegionBtn} onClick={() => setAddMode(true)}>
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6"><path d="M12 5v14M5 12h14" /></svg>
                  동네 추가
                </button>
              )}
            </div>

            {addMode && (
              <div className={styles.searchWrap}>
                <RegionCascadeSelect value="" onChange={(code, fullName) => { if (code) addRegion(code, fullName) }} />
              </div>
            )}
          </div>
        </div>
      )}

      {/* 카테고리 칩 */}
      <nav className={styles.tabs}>
        <button
          type="button"
          className={`${styles.tab}${categoryId === 'all' ? ' ' + styles.on : ''}`}
          onClick={() => setCategoryId('all')}
        >
          전체
        </button>
        {categories.map(cat => (
          <button
            key={cat.id}
            type="button"
            className={`${styles.tab}${categoryId === cat.id ? ' ' + styles.on : ''}`}
            onClick={() => setCategoryId(cat.id)}
          >
            {cat.name}
          </button>
        ))}
      </nav>

      {/* 피드 헤더 */}
      <div className={styles.feedHead}>
        <h2>우리 동네 따끈한 매물</h2>
        <div className={styles.sortWrap} ref={sortWrapRef}>
          <button
            type="button"
            className={styles.sortBtn}
            onClick={() => setSortOpen(o => !o)}
            aria-haspopup="listbox"
            aria-expanded={sortOpen}
          >
            {SORT_OPTIONS.find(o => o.value === sort)?.label}
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4"><path d="M6 9l6 6 6-6" /></svg>
          </button>
          <ul className={`${styles.sortList}${sortOpen ? ' ' + styles.sortListOpen : ''}`} role="listbox">
            {SORT_OPTIONS.map(o => (
              <li key={o.value}>
                <button
                  type="button"
                  className={`${styles.sortOption}${o.value === sort ? ' ' + styles.sortOptionOn : ''}`}
                  role="option"
                  aria-selected={o.value === sort}
                  onClick={() => { setSort(o.value); setSortOpen(false) }}
                >
                  {o.label}
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>

      {status === 'loading' && (
        <div className={styles.empty}><p>불러오는 중...</p></div>
      )}

      {status === 'error' && (
        <div className={styles.empty}>
          <div className={styles.emptyIcon}>⚠️</div>
          <h2>상품 목록을 불러오지 못했어요</h2>
          <p>잠시 후 다시 시도해주세요.</p>
        </div>
      )}

      {status === 'ready' && (
        filtered.length > 0 ? (
          <>
            <div className={styles.grid}>
              {filtered.map((product, i) => {
                const badge = badgeInfo(product)
                const liked = favoriteIds.has(product.productId)
                return (
                  <Link
                    key={product.productId}
                    href={`/products/${product.productId}`}
                    className={styles.pcard}
                    style={{ animationDelay: `${(0.05 + i * 0.045).toFixed(3)}s` }}
                  >
                    <div className={styles.thumb}>
                      {badge && <span className={`${styles.badgeTag} ${badge.cls}`}>{badge.label}</span>}
                      {product.thumbnailUrl ? (
                        <img src={product.thumbnailUrl} alt={product.title} className={styles.thumbImg} />
                      ) : (
                        <span className={styles.thumbPh}>{categories.find(c => c.id === product.categoryId)?.name ?? '상품 이미지'}</span>
                      )}
                      <button
                        type="button"
                        className={`${styles.heartBtn}${liked ? ' ' + styles.on : ''}`}
                        aria-label={liked ? '찜 취소' : '찜'}
                        onClick={e => toggleFavorite(e, product)}
                      >
                        <svg width="17" height="17" viewBox="0 0 24 24" strokeWidth="2"><path d="M12 20.5l-1.4-1.3C5.4 14.5 2 11.4 2 7.6 2 4.9 4.1 3 6.7 3c1.5 0 3 .7 3.9 1.9L12 6.3l1.4-1.4C14.3 3.7 15.8 3 17.3 3 19.9 3 22 4.9 22 7.6c0 3.8-3.4 6.9-8.6 11.6L12 20.5z" /></svg>
                      </button>
                    </div>

                    <div className={styles.body}>
                      <div className={styles.title}>{product.title}</div>
                      <div className={`${styles.price}${product.price === 0 ? ' ' + styles.priceFree : ''}`}>
                        {priceText(product.price)}
                      </div>
                      <div className={styles.meta}>
                        <span>{product.regionFullName}</span>
                      </div>
                      <div className={styles.foot}>
                        <span>♡ {product.favoriteCount}</span>
                      </div>
                    </div>
                  </Link>
                )
              })}
            </div>

            {hasNext && (
              <div className={styles.loadMoreWrap}>
                <button
                  type="button"
                  className={styles.loadMoreBtn}
                  onClick={loadMoreProducts}
                  disabled={loadingMore}
                >
                  {loadingMore ? '불러오는 중...' : '더 보기'}
                </button>
              </div>
            )}
          </>
        ) : (
          <div className={styles.empty}>
            <div className={styles.emptyIcon}>🔍</div>
            <h2>검색 결과가 없어요</h2>
            <p>다른 검색어나 카테고리를 시도해보세요.</p>
          </div>
        )
      )}

      <div className={`toast${toastOn ? ' show' : ''}`}>{toastText}</div>
    </main>
  )
}
