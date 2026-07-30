'use client'

import styles from './ConfirmModal.module.css'

interface Props {
  message: string
  confirmText?: string
  cancelText?: string
  danger?: boolean
  /** 특정 화면의 버튼 색과 맞춰야 할 때 CSS 색상 값(예: 'var(--blue)')을 지정한다. danger보다 우선한다. */
  confirmColor?: string
  onConfirm: () => void
  onCancel: () => void
}

export default function ConfirmModal({ message, confirmText = '확인', cancelText = '취소', danger, confirmColor, onConfirm, onCancel }: Props) {
  return (
    <div className={styles.overlay} onClick={e => e.target === e.currentTarget && onCancel()}>
      <div className={styles.modal} role="alertdialog" aria-modal="true">
        <p className={styles.message}>{message}</p>
        <div className={styles.actions}>
          <button type="button" className={styles.cancelBtn} onClick={onCancel}>{cancelText}</button>
          <button
            type="button"
            className={danger ? styles.confirmBtnDanger : styles.confirmBtn}
            style={confirmColor ? { background: confirmColor, borderColor: confirmColor } : undefined}
            onClick={onConfirm}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  )
}
