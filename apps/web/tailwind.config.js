/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        display: ['Syne', 'Plus Jakarta Sans', 'system-ui', 'sans-serif'],
        sans: ['Plus Jakarta Sans', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['JetBrains Mono', 'ui-monospace', 'SFMono-Regular', 'monospace'],
      },
      colors: {
        obsidian: {
          ground: '#0E1116',
          surface: '#161B22',
          elevated: '#1E2530',
          border: '#2B3442',
          ink: '#E6ECF4',
          muted: '#94A3B8',
          amber: '#E09132',
          proof: '#2FA37A',
          alert: '#E05252',
        },
        linen: {
          ground: '#F7F4ED',
          surface: '#FFFFFF',
          border: '#DDD6C7',
          ink: '#11161C',
          muted: '#4B5563',
          emerald: '#1F6F54',
          amber: '#B87224',
        },
      },
    },
  },
  plugins: [],
};
