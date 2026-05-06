export type ThemeId = 'midnight' | 'sakura' | 'forest' | 'amber'

export interface ThemeOption {
  id: ThemeId
  name: string
  description: string
  swatches: string[]
}

const STORAGE_KEY = 'he-manager-theme'
const DEFAULT_THEME: ThemeId = 'midnight'

export const themes: ThemeOption[] = [
  {
    id: 'midnight',
    name: '午夜蓝',
    description: '冷静、克制，适合长时间整理媒体库。',
    swatches: ['#050507', '#111114', '#818cf8'],
  },
  {
    id: 'sakura',
    name: '樱花粉',
    description: '更柔和一点，适合图片和漫画浏览。',
    swatches: ['#110910', '#22121f', '#f472b6'],
  },
  {
    id: 'forest',
    name: '深林绿',
    description: '低刺激、高辨识，筛选和整理时很舒服。',
    swatches: ['#050e0c', '#0b1f1b', '#34d399'],
  },
  {
    id: 'amber',
    name: '琥珀色',
    description: '偏暖的夜间主题，视觉上更有收藏室感。',
    swatches: ['#0f0c08', '#201a12', '#f59e0b'],
  },
]

export const isThemeId = (value: string | null): value is ThemeId => {
  return themes.some(theme => theme.id === value)
}

export const getStoredTheme = (): ThemeId => {
  if (typeof window === 'undefined') return DEFAULT_THEME
  const stored = window.localStorage.getItem(STORAGE_KEY)
  return isThemeId(stored) ? stored : DEFAULT_THEME
}

export const applyTheme = (themeId: ThemeId) => {
  document.documentElement.dataset.theme = themeId
  window.localStorage.setItem(STORAGE_KEY, themeId)
}

export const initTheme = () => {
  applyTheme(getStoredTheme())
}
