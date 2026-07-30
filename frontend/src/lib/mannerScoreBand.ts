export type MannerScoreBand = 'DANGER' | 'WARNING' | 'DEFAULT' | 'GOOD'

export const MANNER_SCORE_DEFAULT = 36.5

export function mannerScoreBand(score: number): MannerScoreBand {
  if (score < 20) return 'DANGER'
  if (score < MANNER_SCORE_DEFAULT) return 'WARNING'
  if (score === MANNER_SCORE_DEFAULT) return 'DEFAULT'
  return 'GOOD'
}

export const MANNER_SCORE_BAND_LABEL: Record<MannerScoreBand, string> = {
  DANGER: '위험',
  WARNING: '주의',
  DEFAULT: '기본',
  GOOD: '우수',
}

/** 신고 통계 "처리중" 색과 항상 동일해야 하는 GOOD(우수) 등급은 라이트 모드 값으로 테마 무관 고정한다. */
export const MANNER_SCORE_BAND_COLOR: Record<MannerScoreBand, string> = {
  DANGER: '#3c3f42',
  WARNING: '#b8860b',
  DEFAULT: '#e85d9e',
  GOOD: '#2f77e0',
}

/** 게이지 바에 쓰는 등급별 (연한 톤 → 진한 톤) 그라데이션.
 * DANGER·WARNING은 라이트 모드 배경(--neutral-soft 등 옅은 톤)과 대비가 약해 보여서 진하게 조정했다.
 * DANGER는 차콜 톤, WARNING은 채도를 높인 골드 톤이다. */
export const MANNER_SCORE_BAND_GRADIENT: Record<MannerScoreBand, [string, string]> = {
  DANGER: ['#9a9da0', '#3c3f42'],
  WARNING: ['#f0c94a', '#b8860b'],
  DEFAULT: ['#f5b3d1', '#e85d9e'],
  GOOD: ['#bcd6fb', '#2f77e0'],
}

export function mannerScoreStatusText(score: number): string {
  const band = mannerScoreBand(score)
  const label = {
    DANGER: '위험해요',
    WARNING: '주의가 필요해요',
    DEFAULT: '기본 온도예요',
    GOOD: '우수해요',
  }[band]
  const diff = Math.abs(score - MANNER_SCORE_DEFAULT)
  if (diff < 0.05) return label
  return `${label} · 기본 온도보다 ${diff.toFixed(1)}° ${score > MANNER_SCORE_DEFAULT ? '높아요' : '낮아요'}`
}
