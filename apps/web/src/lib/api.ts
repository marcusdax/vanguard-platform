import axios from 'axios';

export const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  timeout: 20000,
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('vg_access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401 && typeof window !== 'undefined') {
      const hasToken = localStorage.getItem('vg_access_token');
      if (hasToken) {
        localStorage.removeItem('vg_access_token');
        localStorage.removeItem('vg_user');
      }
    }
    return Promise.reject(err);
  },
);

export function formatUsd(cents: number): string {
  const neg = cents < 0;
  const abs = Math.abs(Math.round(cents));
  const whole = Math.floor(abs / 100);
  const rem = String(abs % 100).padStart(2, '0');
  return `${neg ? '-' : ''}$${whole.toLocaleString('en-US')}.${rem}`;
}
