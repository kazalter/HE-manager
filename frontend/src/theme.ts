export type ThemeId = 'midnight' | 'sakura' | 'forest' | 'amber' | string

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

const hexToRgb = (hex: string): number[] => {
  let c = hex.substring(1)
  if (c.length === 3) c = c.split('').map(char => char + char).join('')
  const num = parseInt(c, 16)
  return [num >> 16, (num >> 8) & 255, num & 255]
}

const rgbToHsl = (r: number, g: number, b: number) => {
  r /= 255; g /= 255; b /= 255
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  let h = 0, s = 0, l = (max + min) / 2
  if (max !== min) {
    const d = max - min
    s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
    switch (max) {
      case r: h = (g - b) / d + (g < b ? 6 : 0); break
      case g: h = (b - r) / d + 2; break
      case b: h = (r - g) / d + 4; break
    }
    h /= 6
  }
  return [h, s, l]
}

const hslToRgb = (h: number, s: number, l: number) => {
  let r, g, b
  if (s === 0) {
    r = g = b = l
  } else {
    const hue2rgb = (p: number, q: number, t: number) => {
      if (t < 0) t += 1
      if (t > 1) t -= 1
      if (t < 1/6) return p + (q - p) * 6 * t
      if (t < 1/2) return q
      if (t < 2/3) return p + (q - p) * (2/3 - t) * 6
      return p
    }
    const q = l < 0.5 ? l * (1 + s) : l + s - l * s
    const p = 2 * l - q
    r = hue2rgb(p, q, h + 1/3)
    g = hue2rgb(p, q, h)
    b = hue2rgb(p, q, h - 1/3)
  }
  return [Math.round(r * 255), Math.round(g * 255), Math.round(b * 255)]
}

export const isThemeId = (value: string | null): boolean => {
  if (!value) return false
  if (value.startsWith('#') && /^#[0-9A-Fa-f]{6}$/i.test(value)) return true
  return themes.some(theme => theme.id === value)
}

export const getStoredTheme = (): string => {
  if (typeof window === 'undefined') return DEFAULT_THEME
  const stored = window.localStorage.getItem(STORAGE_KEY)
  return isThemeId(stored) ? stored! : DEFAULT_THEME
}

export const applyTheme = (themeId: string) => {
  window.localStorage.setItem(STORAGE_KEY, themeId)
  
  if (themeId.startsWith('#')) {
    document.documentElement.dataset.theme = 'custom'
    const [r, g, b] = hexToRgb(themeId)
    const [h, s, l] = rgbToHsl(r, g, b)
    
    const [gr, gg, gb] = hslToRgb(h, Math.min(1, s + 0.2), Math.min(0.9, l + 0.15))
    const [br, bg, bb] = hslToRgb(h, Math.min(0.2, s), 0.03)
    const [sr, sg, sb] = hslToRgb(h, Math.min(0.2, s), 0.06)
    
    document.documentElement.style.setProperty('--color-accent', `${r} ${g} ${b}`)
    document.documentElement.style.setProperty('--color-accent-glow', `${gr} ${gg} ${gb}`)
    document.documentElement.style.setProperty('--color-background', `${br} ${bg} ${bb}`)
    document.documentElement.style.setProperty('--color-sidebar', `${sr} ${sg} ${sb}`)
  } else {
    document.documentElement.dataset.theme = themeId
    document.documentElement.style.removeProperty('--color-accent')
    document.documentElement.style.removeProperty('--color-accent-glow')
    document.documentElement.style.removeProperty('--color-background')
    document.documentElement.style.removeProperty('--color-sidebar')
  }
}

export const initTheme = () => {
  applyTheme(getStoredTheme())
}
