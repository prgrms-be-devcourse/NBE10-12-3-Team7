'use client'

import { useEffect, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import styles from './page.module.css'

/** 텍스트를 줄 단위로 표현. 문자열 세그먼트에 색상 등 개별 스타일이 필요하면 { text, cls }로 표기한다. */
type Segment = string | { text: string; cls?: string }
type Line = Segment | Segment[]

interface Section {
  eyebrow: Line[]
  titleLines: Line[]
  descLines: Line[]
  cta?: boolean
  bg?: string
  bgOpacity?: number
}

const SECTIONS: Section[] = [
  {
    eyebrow: ['👋 반가워요'],
    titleLines: [
      [{ text: 'Market' }, { text: 'ON', cls: 'brand' }, { text: '에' }],
      '오신 걸 환영해요',
    ],
    descLines: ['스크롤을 내려서 조금 더 알아볼까요?'],
    bg: '/marketon-hero.png',
  },
  {
    eyebrow: ['🏘️ 우리 동네 중고거래'],
    titleLines: ['가까운 이웃과 함께하는', '따뜻한 거래 플랫폼이에요'],
    descLines: ['필요 없는 물건은 나누고,', '필요한 물건은 우리 동네에서 합리적으로 만나보세요.'],
    bg: '/marketon-section2.png',
    bgOpacity: 0.4,
  },
  {
    eyebrow: ['💬 채팅 · 관심 · 동네 인증'],
    titleLines: ['관심 상품은 저장하고,', '채팅으로 편하게 거래해요'],
    descLines: ['실시간 채팅과 관심 알림까지 — 거래에 필요한 기능이 다 있어요.'],
  },
  {
    eyebrow: ['🚀 시작해볼까요?'],
    titleLines: ['지금 가입하고', '첫 거래를 시작해보세요'],
    descLines: ['아래 버튼을 눌러 회원가입을 시작해보세요.'],
    cta: true,
  },
]

/**
 * 문장(줄) 단위로 아래에서 위로 순차적으로 떠오르는 stagger 애니메이션. 실제 트리거는 조상의 .visible 클래스(CSS)다.
 * startIndex를 주면 한 섹션 안의 여러 블록(인사말→제목→설명)이 하나의 연속된 순서로 이어서 올라온다.
 */
function Stagger({ lines, stagger, startIndex = 0 }: { lines: Line[]; stagger: number; startIndex?: number }) {
  return (
    <>
      {lines.map((line, li) => {
        const segments: { text: string; cls?: string }[] = (Array.isArray(line) ? line : [line])
          .map(seg => (typeof seg === 'string' ? { text: seg } : seg))
        const delay = (startIndex + li) * stagger
        return (
          <span key={li} className={styles.lineClip}>
            <span className={styles.lineWrap} style={{ transitionDelay: `${delay.toFixed(3)}s` }}>
              {segments.map((seg, si) => (
                <span key={si} className={seg.cls ? styles[seg.cls] : undefined}>{seg.text}</span>
              ))}
            </span>
          </span>
        )
      })}
    </>
  )
}

export default function Home() {
  const router = useRouter()
  const sectionRefs = useRef<(HTMLElement | null)[]>([])
  const [visible, setVisible] = useState<boolean[]>(() => SECTIONS.map(() => false))
  const navigatedRef = useRef(false)

  function goToSignup() {
    if (navigatedRef.current) return
    navigatedRef.current = true
    router.push('/signup')
  }

  useEffect(() => {
    const observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (!entry.isIntersecting) return
          const index = sectionRefs.current.indexOf(entry.target as HTMLElement)
          if (index === -1) return
          setVisible(prev => {
            if (prev[index]) return prev
            const next = [...prev]
            next[index] = true
            return next
          })
        })
      },
      { threshold: 0.4 }
    )

    sectionRefs.current.forEach(el => { if (el) observer.observe(el) })
    return () => observer.disconnect()
  }, [])

  return (
    <main className={styles.wrap}>
      {SECTIONS.map((section, i) => {
        const stagger = 0.28
        const eyebrowStart = 0
        const titleStart = eyebrowStart + section.eyebrow.length
        const descStart = titleStart + section.titleLines.length
        return (
        <section
          key={i}
          ref={el => { sectionRefs.current[i] = el }}
          className={`${styles.section}${visible[i] ? ' ' + styles.visible : ''}`}
        >
          {section.bg && (
            <div
              className={styles.heroBg}
              style={{
                backgroundImage: `url(${section.bg})`,
                ...(section.bgOpacity != null ? { '--hero-opacity': section.bgOpacity } as React.CSSProperties : {}),
              }}
              aria-hidden="true"
            />
          )}
          <span className={styles.eyebrow}>
            <Stagger lines={section.eyebrow} stagger={stagger} startIndex={eyebrowStart} />
          </span>
          <h1 className={styles.title}>
            <Stagger lines={section.titleLines} stagger={stagger} startIndex={titleStart} />
          </h1>
          <p className={styles.desc}>
            <Stagger lines={section.descLines} stagger={stagger} startIndex={descStart} />
          </p>

          {section.cta ? (
            <button type="button" className={styles.ctaBtn} onClick={goToSignup}>
              회원가입 하러 가기
            </button>
          ) : (
            <span className={styles.scrollHint}>
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <path d="M6 9l6 6 6-6" />
              </svg>
            </span>
          )}
        </section>
        )
      })}
    </main>
  )
}
