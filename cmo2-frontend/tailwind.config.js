/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // CMO² glassmorphism palette
        brand: {
          DEFAULT: '#6366f1',
          dark: '#4f46e5',
          glow: '#818cf8',
        },
        ink: '#0b1020',
      },
      backgroundImage: {
        'app-gradient':
          'radial-gradient(1200px 600px at 10% -10%, rgba(99,102,241,0.25), transparent), radial-gradient(1000px 500px at 110% 10%, rgba(16,185,129,0.18), transparent)',
      },
      boxShadow: {
        glass: '0 8px 32px 0 rgba(0, 0, 0, 0.37)',
      },
    },
  },
  plugins: [],
}
