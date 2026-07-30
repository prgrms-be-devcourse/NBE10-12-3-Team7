'use client'

import Link from 'next/link'
import { useState } from 'react'
import { setAccessToken } from '@/lib/auth'
import { startOAuthLogin, type OAuthProviderKey } from '@/lib/oauth'
import styles from './page.module.css'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type Hint = { text: string; kind?: string }
type MsgType = 'success' | 'error'

export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [autoLogin, setAutoLogin] = useState(false)

  const [emailHint, setEmailHint] = useState<Hint>({ text: '' })
  const [passwordHint, setPasswordHint] = useState<Hint>({ text: '' })

  const [formMsg, setFormMsg] = useState<{ text: string; type: MsgType } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [oauthLoading, setOauthLoading] = useState<OAuthProviderKey | null>(null)

  function validateEmail(v: string) {
    if (!v) { setEmailHint({ text: '이메일을 입력하세요.', kind: 'err' }); return false }
    if (!EMAIL_RE.test(v)) { setEmailHint({ text: '올바른 이메일 형식이 아닙니다.', kind: 'err' }); return false }
    setEmailHint({ text: '' })
    return true
  }

  function validatePassword(v: string) {
    if (!v) { setPasswordHint({ text: '비밀번호를 입력하세요.', kind: 'err' }); return false }
    setPasswordHint({ text: '' })
    return true
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormMsg(null)
    const valid = [validateEmail(email.trim()), validatePassword(password)].every(Boolean)
    if (!valid) { setFormMsg({ text: '이메일과 비밀번호를 확인해주세요.', type: 'error' }); return }

    setSubmitting(true)
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim(), password, autoLogin }),
      })
      const data = await res.json().catch(() => null)

      if (!res.ok) {
        setFormMsg({ text: data?.message ?? '로그인 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.', type: 'error' })
        setSubmitting(false)
        return
      }

      const accessToken = data?.data?.accessToken
      if (accessToken) {
        setAccessToken(accessToken)
      }
      setFormMsg({ text: '로그인되었습니다. 이동합니다.', type: 'success' })
      setTimeout(() => { window.location.href = '/products' }, 900)
    } catch {
      setFormMsg({ text: '서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.', type: 'error' })
      setSubmitting(false)
    }
  }

  async function handleOAuthLogin(provider: OAuthProviderKey) {
    if (oauthLoading) return
    setFormMsg(null)
    setOauthLoading(provider)
    const errorMessage = await startOAuthLogin(provider)
    // 성공하면 브라우저가 이미 이동하므로, 여기 도달하는 건 실패했을 때뿐이다.
    if (errorMessage) {
      setFormMsg({ text: errorMessage, type: 'error' })
      setOauthLoading(null)
    }
  }

  const hintClass = (kind?: string) => [styles.hint, kind ? styles[kind] : ''].filter(Boolean).join(' ')
  const msgClass = formMsg ? [styles.formMsg, styles.show, styles[formMsg.type]].join(' ') : styles.formMsg

  return (
    <main className={styles.stage}>
      {/* 인트로 */}
      <div className={styles.intro}>
        <span className={styles.badge}>🌷 다시 오신 걸 환영해요</span>
        <h1>Market<span style={{ color: 'var(--primary)' }}>ON</span> 로그인</h1>
        <p>우리 동네 거래가 가장 활발한 곳, 다시 시작해요.</p>
      </div>

      <div className={styles.card}>
        <div className={msgClass} role="alert">{formMsg?.text}</div>

        <form onSubmit={handleSubmit} className={styles.formCol} noValidate>
          <div className={styles.field}>
            <label htmlFor="email">이메일<span className={styles.req}>*</span></label>
            <input
              type="email"
              id="email"
              placeholder="example@email.com"
              autoComplete="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              onBlur={e => e.target.value && validateEmail(e.target.value.trim())}
              aria-invalid={emailHint.kind === 'err' ? 'true' : 'false'}
            />
            <div className={hintClass(emailHint.kind)}>{emailHint.text}</div>
          </div>

          <div className={styles.field}>
            <label htmlFor="password">비밀번호<span className={styles.req}>*</span></label>
            <input
              type="password"
              id="password"
              placeholder="비밀번호를 입력하세요"
              autoComplete="current-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              aria-invalid={passwordHint.kind === 'err' ? 'true' : 'false'}
            />
            <div className={hintClass(passwordHint.kind)}>{passwordHint.text}</div>
          </div>

          <div className={styles.autoLoginRow}>
            <div className={styles.autoLoginCheck}>
              <input
                type="checkbox"
                id="autoLogin"
                checked={autoLogin}
                onChange={e => setAutoLogin(e.target.checked)}
              />
              <label htmlFor="autoLogin">자동 로그인</label>
            </div>
            <Link href="/find-password" className={styles.forgotLink}>비밀번호 찾기</Link>
          </div>

          <button type="submit" className="btn block" disabled={submitting}>
            {submitting ? '로그인 중...' : '로그인'}
          </button>
        </form>

        <div className={styles.divider}><span>또는</span></div>

        <div className={styles.oauthCol}>
          <button
            type="button"
            className={`${styles.oauthBtn} ${styles.kakaoBtn}`}
            onClick={() => handleOAuthLogin('kakao')}
            disabled={oauthLoading !== null}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="#191919"><path d="M12 3C6.48 3 2 6.48 2 10.78c0 2.72 1.82 5.11 4.56 6.5-.2.74-.73 2.7-.84 3.12-.13.51.19.5.4.37.16-.11 2.6-1.77 3.66-2.49.71.1 1.45.16 2.22.16 5.52 0 10-3.48 10-7.78S17.52 3 12 3z" /></svg>
            {oauthLoading === 'kakao' ? '이동 중...' : '카카오로 로그인'}
          </button>
          <button
            type="button"
            className={`${styles.oauthBtn} ${styles.googleBtn}`}
            onClick={() => handleOAuthLogin('google')}
            disabled={oauthLoading !== null}
          >
            <svg width="18" height="18" viewBox="0 0 24 24">
              <path fill="#4285F4" d="M23.52 12.27c0-.85-.08-1.67-.22-2.45H12v4.64h6.47c-.28 1.5-1.13 2.78-2.4 3.63v3.02h3.89c2.28-2.1 3.56-5.2 3.56-8.84z" />
              <path fill="#34A853" d="M12 24c3.24 0 5.96-1.07 7.95-2.9l-3.89-3.02c-1.08.72-2.46 1.15-4.06 1.15-3.12 0-5.77-2.11-6.71-4.94H1.28v3.11C3.26 21.3 7.31 24 12 24z" />
              <path fill="#FBBC05" d="M5.29 14.29A7.2 7.2 0 0 1 4.9 12c0-.8.14-1.57.39-2.29V6.6H1.28A11.98 11.98 0 0 0 0 12c0 1.93.46 3.76 1.28 5.4l4.01-3.11z" />
              <path fill="#EA4335" d="M12 4.77c1.76 0 3.34.6 4.58 1.79l3.44-3.44C17.95 1.19 15.24 0 12 0 7.31 0 3.26 2.7 1.28 6.6l4.01 3.11C6.23 6.88 8.88 4.77 12 4.77z" />
            </svg>
            {oauthLoading === 'google' ? '이동 중...' : '구글로 로그인'}
          </button>
        </div>

        <div className={styles.foot}>아직 계정이 없으신가요? <Link href="/signup">회원가입</Link></div>
      </div>
    </main>
  )
}
