import { create } from 'zustand';
import { api } from '@/lib/api';
import { BRAND_CONFIG } from '@/brand/brandConfig';

export interface AuthUser {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
  companyId?: string;
}

interface AuthState {
  user: AuthUser | null;
  token: string | null;
  busy: boolean;
  error: string | null;
  login: (email: string, password: string) => Promise<boolean>;
  register: (input: {
    email: string;
    password: string;
    firstName: string;
    lastName: string;
    companyName: string;
  }) => Promise<boolean>;
  demoLogin: () => Promise<boolean>;
  logout: () => void;
  clearError: () => void;
}

function readStoredUser(): AuthUser | null {
  try {
    const raw = localStorage.getItem('vg_user');
    return raw ? (JSON.parse(raw) as AuthUser) : null;
  } catch {
    return null;
  }
}

export const useAuthStore = create<AuthState>((set) => ({
  user: typeof window !== 'undefined' ? readStoredUser() : null,
  token: typeof window !== 'undefined' ? localStorage.getItem('vg_access_token') : null,
  busy: false,
  error: null,

  clearError: () => set({ error: null }),

  login: async (email, password) => {
    set({ busy: true, error: null });
    try {
      const { data } = await api.post('/auth/login', { email, password });
      localStorage.setItem('vg_access_token', data.accessToken);
      localStorage.setItem('vg_user', JSON.stringify(data.user));
      set({ user: data.user, token: data.accessToken, busy: false });
      return true;
    } catch (err: any) {
      const msg = err?.response?.data?.error || 'Authentication failed';
      set({ busy: false, error: msg });
      return false;
    }
  },

  register: async (input) => {
    set({ busy: true, error: null });
    try {
      const { data } = await api.post('/auth/register', input);
      localStorage.setItem('vg_access_token', data.accessToken);
      localStorage.setItem('vg_user', JSON.stringify(data.user));
      set({ user: data.user, token: data.accessToken, busy: false });
      return true;
    } catch (err: any) {
      const msg = err?.response?.data?.error || 'Registration failed';
      set({ busy: false, error: msg });
      return false;
    }
  },

  demoLogin: async () => {
    set({ busy: true, error: null });
    try {
      const { data } = await api.post('/auth/login', {
        email: BRAND_CONFIG.demoEmail,
        password: BRAND_CONFIG.demoSecret,
      });
      localStorage.setItem('vg_access_token', data.accessToken);
      localStorage.setItem('vg_user', JSON.stringify(data.user));
      set({ user: data.user, token: data.accessToken, busy: false });
      return true;
    } catch (err: any) {
      const msg = err?.response?.data?.error || 'Demo sign-in unavailable';
      set({ busy: false, error: msg });
      return false;
    }
  },

  logout: () => {
    localStorage.removeItem('vg_access_token');
    localStorage.removeItem('vg_user');
    set({ user: null, token: null, error: null });
  },
}));
