/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        background: 'rgb(var(--color-background) / <alpha-value>)',
        sidebar: 'rgb(var(--color-sidebar) / <alpha-value>)',
        accent: 'rgb(var(--color-accent) / <alpha-value>)',
        'accent-glow': 'rgb(var(--color-accent-glow) / <alpha-value>)',
      },
      backgroundImage: {
        'mesh-dark': 'radial-gradient(at 40% 20%, rgba(55, 48, 163, 0.15) 0px, transparent 50%), radial-gradient(at 80% 0%, rgba(88, 28, 135, 0.15) 0px, transparent 50%), radial-gradient(at 0% 50%, rgba(30, 58, 138, 0.15) 0px, transparent 50%)',
      },
      animation: {
        'blob': 'blob 7s infinite',
      },
      keyframes: {
        blob: {
          '0%': { transform: 'translate(0px, 0px) scale(1)' },
          '33%': { transform: 'translate(30px, -50px) scale(1.1)' },
          '66%': { transform: 'translate(-20px, 20px) scale(0.9)' },
          '100%': { transform: 'translate(0px, 0px) scale(1)' },
        }
      }
    },
  },
  plugins: [],
}
