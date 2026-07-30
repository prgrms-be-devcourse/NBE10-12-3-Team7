'use client'

import Link from 'next/link'
import { useState } from 'react'
import AgreementSection from './AgreementSection'
import { startOAuthLogin, type OAuthProviderKey } from '@/lib/oauth'
import styles from './page.module.css'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const PW_RE = /^(?=.*[A-Za-z])(?=.*\d)(?=.*[^A-Za-z0-9\s])\S{10,64}$/

type Hint = { text: string; kind?: string }
type MsgType = 'success' | 'error'

export default function SignupPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [nickname, setNickname] = useState('')

  const [emailHint, setEmailHint] = useState<Hint>({ text: '로그인에 사용할 이메일을 입력하세요.' })
  const [passwordHint, setPasswordHint] = useState<Hint>({ text: '영문·숫자·특수문자를 포함해 10~64자로 입력하세요.' })
  const [passwordConfirmHint, setPasswordConfirmHint] = useState<Hint>({ text: '비밀번호를 한 번 더 입력하세요.' })
  const [nicknameHint, setNicknameHint] = useState<Hint>({ text: '2~20자로 입력하세요. 다른 이웃에게 보여집니다.' })

  const [formMsg, setFormMsg] = useState<{ text: string; type: MsgType } | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [oauthLoading, setOauthLoading] = useState<OAuthProviderKey | null>(null)

  /* ── 약관 동의 ── */
  const [termsAgreed, setTermsAgreed] = useState(false)
  const [personalInfoCollectionAgreed, setPersonalInfoCollectionAgreed] = useState(false)
  // 제출을 한 번이라도 시도했는지만 기록한다. 에러 문구 자체는 저장하지 않고
  // termsAgreed/personalInfoCollectionAgreed 조합에서 매 렌더마다 파생해, 체크박스를 고치면
  // 별도 클리어 없이 문구가 항상 현재 상태와 맞게 갱신된다.
  const [agreementTouched, setAgreementTouched] = useState(false)
  // allAgreed도 별도 state가 아니라 termsAgreed && personalInfoCollectionAgreed의 파생값이다(AgreementSection 내부도 동일).
  const bothAgreed = termsAgreed && personalInfoCollectionAgreed
  const agreementErrorText = !agreementTouched || bothAgreed
    ? undefined
    : !termsAgreed && !personalInfoCollectionAgreed
      ? '이용약관과 개인정보 수집 및 이용 동의에 모두 동의해주세요.'
      : !termsAgreed
        ? '이용약관에 동의해주세요.'
        : '개인정보 수집 및 이용 동의가 필요합니다.'

  /* ── 이메일 인증 ── */
  const [codeSent, setCodeSent] = useState(false)
  const [code, setCode] = useState('')
  const [verifiedEmail, setVerifiedEmail] = useState('')
  const [sendingCode, setSendingCode] = useState(false)
  const [verifyingCode, setVerifyingCode] = useState(false)
  const [emailCodeMsg, setEmailCodeMsg] = useState<Hint>({ text: '' })

  const emailVerified = verifiedEmail !== '' && verifiedEmail === email.trim()

  function resetEmailVerification() {
    setCodeSent(false)
    setCode('')
    setVerifiedEmail('')
    setEmailCodeMsg({ text: '' })
  }

  async function sendVerificationCode() {
    if (!validateEmail()) return
    setSendingCode(true)
    setEmailCodeMsg({ text: '' })
    try {
      const res = await fetch('/api/auth/email-verifications', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim() }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) {
        setEmailCodeMsg({ text: data?.message ?? '인증 코드 발송에 실패했습니다.', kind: 'err' })
        return
      }
      setVerifiedEmail('')
      setCode('')
      setCodeSent(true)
      setEmailCodeMsg({ text: '인증 코드를 발송했어요. 이메일을 확인해주세요.', kind: 'ok' })
    } catch {
      setEmailCodeMsg({ text: '서버에 연결할 수 없습니다.', kind: 'err' })
    } finally {
      setSendingCode(false)
    }
  }

  async function verifyCode() {
    if (!code.trim()) { setEmailCodeMsg({ text: '인증 코드를 입력하세요.', kind: 'err' }); return }
    setVerifyingCode(true)
    try {
      const res = await fetch('/api/auth/email-verifications/confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: email.trim(), code: code.trim() }),
      })
      const data = await res.json().catch(() => null)
      if (!res.ok) {
        setEmailCodeMsg({ text: data?.message ?? '인증에 실패했습니다.', kind: 'err' })
        return
      }
      setVerifiedEmail(email.trim())
      setEmailCodeMsg({ text: '이메일 인증이 완료되었어요.', kind: 'ok' })
    } catch {
      setEmailCodeMsg({ text: '서버에 연결할 수 없습니다.', kind: 'err' })
    } finally {
      setVerifyingCode(false)
    }
  }

  function validateEmail() {
    const v = email.trim()
    if (!v) { setEmailHint({ text: '이메일을 입력하세요.', kind: 'err' }); return false }
    if (!EMAIL_RE.test(v)) { setEmailHint({ text: '올바른 이메일 형식이 아닙니다.', kind: 'err' }); return false }
    setEmailHint({ text: '' })
    return true
  }

  function validatePassword() {
    if (!PW_RE.test(password)) { setPasswordHint({ text: '영문·숫자·특수문자 포함 10~64자여야 합니다.', kind: 'err' }); return false }
    setPasswordHint({ text: '사용 가능한 비밀번호입니다.', kind: 'ok' })
    return true
  }

  function validatePasswordConfirm() {
    if (passwordConfirm !== password) { setPasswordConfirmHint({ text: '비밀번호가 일치하지 않습니다.', kind: 'err' }); return false }
    setPasswordConfirmHint({ text: '비밀번호가 일치합니다.', kind: 'ok' })
    return true
  }

  function validateNickname() {
    const v = nickname.trim()
    if (v.length < 2 || v.length > 20) { setNicknameHint({ text: '닉네임은 2~20자로 입력하세요.', kind: 'err' }); return false }
    setNicknameHint({ text: '', kind: 'ok' })
    return true
  }

  function validateAgreements() {
    setAgreementTouched(true)
    return bothAgreed
  }

  async function handleOAuthSignup(provider: OAuthProviderKey) {
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

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormMsg(null)
    const valid = [validateEmail(), validatePassword(), validatePasswordConfirm(), validateNickname(), validateAgreements()].every(Boolean)
    if (!valid) { setFormMsg({ text: '입력값을 다시 확인해주세요.', type: 'error' }); return }
    if (!emailVerified) { setFormMsg({ text: '이메일 인증을 먼저 완료해주세요.', type: 'error' }); return }

    setSubmitting(true)
    try {
      const res = await fetch('/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: email.trim(),
          password,
          nickname: nickname.trim(),
          termsAgreed,
          personalInfoCollectionAgreed,
        }),
      })

      const data = await res.json().catch(() => null)

      if (res.status === 409) {
        const code = data?.error
        const message = data?.message ?? '이미 사용 중인 값이 있습니다.'
        if (code === 'DUPLICATE_NICKNAME') setNicknameHint({ text: message, kind: 'err' })
        else setEmailHint({ text: message, kind: 'err' })
        setFormMsg({ text: message, type: 'error' })
        setSubmitting(false)
        return
      }

      if (data?.error === 'TERMS_NOT_AGREED' || data?.error === 'PERSONAL_INFO_COLLECTION_NOT_AGREED') {
        const message = data?.message ?? '필수 약관에 동의해주세요.'
        setAgreementTouched(true)
        setFormMsg({ text: message, type: 'error' })
        setSubmitting(false)
        return
      }

      if (!res.ok) {
        setFormMsg({ text: data?.message ?? '가입 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.', type: 'error' })
        setSubmitting(false)
        return
      }

      setFormMsg({ text: '회원가입이 완료되었습니다! 로그인 화면으로 이동합니다.', type: 'success' })
      setTimeout(() => { window.location.href = '/login' }, 1200)
    } catch {
      setFormMsg({ text: '서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.', type: 'error' })
      setSubmitting(false)
    }
  }

  const hintClass = (kind?: string) => [styles.hint, kind ? styles[kind] : ''].filter(Boolean).join(' ')
  const msgClass = formMsg ? [styles.formMsg, styles.show, styles[formMsg.type]].join(' ') : styles.formMsg

  return (
    <main className={styles.stage}>
      {/* 인트로 */}
      <div className={styles.intro}>
        <span className={styles.badge}>🌷 우리 동네 중고거래의 시작</span>
        <h1>Market<span style={{ color: 'var(--primary)' }}>ON</span> 회원가입</h1>
        <p>우리 동네 거래가 가장 활발한 곳, 지금 합류하세요.</p>
      </div>

      <div className={styles.card}>
        <div className={msgClass} role="alert">{formMsg?.text}</div>

        <div className={styles.oauthDivider}><span>소셜 계정으로 간편 회원가입</span></div>
        <div className={styles.oauthRow}>
          <button
            type="button"
            className={`${styles.oauthCircleBtn} ${styles.kakaoCircle}`}
            onClick={() => handleOAuthSignup('kakao')}
            disabled={oauthLoading !== null}
            aria-label="카카오로 회원가입"
          >
            <svg width="24" height="24" viewBox="0 0 24 24" fill="#191919"><path d="M12 3C6.48 3 2 6.48 2 10.78c0 2.72 1.82 5.11 4.56 6.5-.2.74-.73 2.7-.84 3.12-.13.51.19.5.4.37.16-.11 2.6-1.77 3.66-2.49.71.1 1.45.16 2.22.16 5.52 0 10-3.48 10-7.78S17.52 3 12 3z" /></svg>
          </button>
          <button
            type="button"
            className={`${styles.oauthCircleBtn} ${styles.googleCircle}`}
            onClick={() => handleOAuthSignup('google')}
            disabled={oauthLoading !== null}
            aria-label="구글로 회원가입"
          >
            <svg width="24" height="24" viewBox="0 0 24 24">
              <path fill="#4285F4" d="M23.52 12.27c0-.85-.08-1.67-.22-2.45H12v4.64h6.47c-.28 1.5-1.13 2.78-2.4 3.63v3.02h3.89c2.28-2.1 3.56-5.2 3.56-8.84z" />
              <path fill="#34A853" d="M12 24c3.24 0 5.96-1.07 7.95-2.9l-3.89-3.02c-1.08.72-2.46 1.15-4.06 1.15-3.12 0-5.77-2.11-6.71-4.94H1.28v3.11C3.26 21.3 7.31 24 12 24z" />
              <path fill="#FBBC05" d="M5.29 14.29A7.2 7.2 0 0 1 4.9 12c0-.8.14-1.57.39-2.29V6.6H1.28A11.98 11.98 0 0 0 0 12c0 1.93.46 3.76 1.28 5.4l4.01-3.11z" />
              <path fill="#EA4335" d="M12 4.77c1.76 0 3.34.6 4.58 1.79l3.44-3.44C17.95 1.19 15.24 0 12 0 7.31 0 3.26 2.7 1.28 6.6l4.01 3.11C6.23 6.88 8.88 4.77 12 4.77z" />
            </svg>
          </button>
        </div>

        <form onSubmit={handleSubmit} className={styles.formCol} noValidate>
          <div className={styles.field}>
            <label htmlFor="email">이메일<span className={styles.req}>*</span></label>
            <div className={styles.inlineRow}>
              <input
                type="email"
                id="email"
                placeholder="example@email.com"
                autoComplete="email"
                value={email}
                onChange={e => { setEmail(e.target.value); resetEmailVerification() }}
                onBlur={validateEmail}
                aria-invalid={emailHint.kind === 'err' ? 'true' : 'false'}
                disabled={emailVerified}
              />
              <button
                type="button"
                className="btn ghost"
                onClick={sendVerificationCode}
                disabled={sendingCode || emailVerified || !email.trim()}
              >
                {emailVerified ? '인증 완료' : sendingCode ? '발송 중...' : codeSent ? '재발송' : '인증번호 발송'}
              </button>
            </div>
            <div className={hintClass(emailHint.kind)}>{emailHint.text}</div>

            {codeSent && !emailVerified && (
              <div className={`${styles.inlineRow} ${styles.codeRow}`}>
                <input
                  type="text"
                  placeholder="인증번호를 입력하세요"
                  value={code}
                  onChange={e => setCode(e.target.value)}
                  maxLength={6}
                />
                <button
                  type="button"
                  className="btn ghost"
                  onClick={verifyCode}
                  disabled={verifyingCode || !code.trim()}
                >
                  {verifyingCode ? '확인 중...' : '인증 확인'}
                </button>
              </div>
            )}
            {emailCodeMsg.text && (
              <div className={hintClass(emailCodeMsg.kind)}>{emailCodeMsg.text}</div>
            )}
          </div>

          <div className={styles.field}>
            <label htmlFor="password">비밀번호<span className={styles.req}>*</span></label>
            <input
              type="password"
              id="password"
              placeholder="10~64자, 영문·숫자·특수문자 포함"
              autoComplete="new-password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              onBlur={validatePassword}
              aria-invalid={passwordHint.kind === 'err' ? 'true' : 'false'}
            />
            <div className={hintClass(passwordHint.kind)}>{passwordHint.text}</div>
          </div>

          <div className={styles.field}>
            <label htmlFor="passwordConfirm">비밀번호 확인<span className={styles.req}>*</span></label>
            <input
              type="password"
              id="passwordConfirm"
              placeholder="비밀번호를 한 번 더 입력하세요"
              autoComplete="new-password"
              value={passwordConfirm}
              onChange={e => setPasswordConfirm(e.target.value)}
              onBlur={validatePasswordConfirm}
              aria-invalid={passwordConfirmHint.kind === 'err' ? 'true' : 'false'}
            />
            <div className={hintClass(passwordConfirmHint.kind)}>{passwordConfirmHint.text}</div>
          </div>

          <div className={styles.field}>
            <label htmlFor="nickname">닉네임<span className={styles.req}>*</span></label>
            <input
              type="text"
              id="nickname"
              placeholder="닉네임을 설정하세요"
              maxLength={20}
              autoComplete="nickname"
              value={nickname}
              onChange={e => setNickname(e.target.value)}
              onBlur={validateNickname}
              aria-invalid={nicknameHint.kind === 'err' ? 'true' : 'false'}
            />
            <div className={hintClass(nicknameHint.kind)}>{nicknameHint.text}</div>
          </div>

          <div className={styles.field}>
            <AgreementSection
              termsAgreed={termsAgreed}
              personalInfoCollectionAgreed={personalInfoCollectionAgreed}
              onTermsChange={setTermsAgreed}
              onPersonalInfoCollectionChange={setPersonalInfoCollectionAgreed}
              errorText={agreementErrorText}
            />
          </div>

          <button type="submit" className="btn block" disabled={submitting || !emailVerified || !bothAgreed}>
            {submitting
              ? '가입 처리 중...'
              : !emailVerified
                ? '이메일 인증을 완료해주세요'
                : !bothAgreed
                  ? '필수 약관에 동의해주세요'
                  : '가입하기'}
          </button>
        </form>

        <div className={styles.foot}>
          <p>이미 계정이 있으신가요?</p>
          <Link href="/login" className="btn ghost block">로그인하기</Link>
        </div>
      </div>
    </main>
  )
}
