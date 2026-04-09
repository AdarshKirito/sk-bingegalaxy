import { create } from 'zustand';
import { authService } from '../services/endpoints';
import api from '../services/api';
import type { User } from '../types';

interface AuthState {
  user: User | null;
  loading: boolean;
  isAuthenticated: boolean;
  isAdmin: boolean;
  isSuperAdmin: boolean;
  _setAuth: (userData: User, token?: string) => void;
  login: (credentials: { email: string; password: string }) => Promise<User>;
  adminLogin: (credentials: { email: string; password: string }) => Promise<User>;
  register: (data: Record<string, unknown>) => Promise<User>;
  googleLogin: (credential: string) => Promise<User>;
  adminRegister: (data: Record<string, unknown>) => Promise<User>;
  setUser: (userData: User) => void;
  logout: () => Promise<void>;
}

const useAuthStore = create<AuthState>((set, get) => {
  const storedUser: User | null = (() => {
    try {
      const stored = localStorage.getItem('user');
      return stored ? JSON.parse(stored) : null;
    } catch { return null; }
  })();

  // Validate stored user against the server on load
  if (storedUser) {
    api.get('/auth/profile').then(res => {
      const userData = res.data?.data;
      if (userData) {
        get()._setAuth(userData);
        set({ loading: false });
      } else {
        // Server rejected — clear stale local state
        localStorage.removeItem('user');
        set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false, loading: false });
      }
    }).catch(() => {
      // Token expired / invalid — clear stale local state
      localStorage.removeItem('user');
      set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false, loading: false });
    });
  }

  return {
  user: storedUser,

  loading: !!storedUser,

  isAuthenticated: !!storedUser,
  isAdmin: storedUser?.role === 'ADMIN' || storedUser?.role === 'SUPER_ADMIN' || false,
  isSuperAdmin: storedUser?.role === 'SUPER_ADMIN' || false,

  _setAuth: (userData: User, token?: string) => {
    // Tokens are stored in httpOnly cookies by the backend — only minimal user data goes to localStorage
    const minimalUser = { id: userData.id, firstName: userData.firstName, role: userData.role, active: userData.active, phone: userData.phone };
    localStorage.setItem('user', JSON.stringify(minimalUser));
    // Store token expiry (seconds epoch) so the request interceptor can proactively refresh
    if (token) {
      try {
        const [, payload] = token.split('.');
        const { exp } = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
        localStorage.setItem('token_exp', String(exp));
      } catch { /* ignore */ }
    }
    set({
      user: userData,
      isAuthenticated: true,
      isAdmin: userData.role === 'ADMIN' || userData.role === 'SUPER_ADMIN',
      isSuperAdmin: userData.role === 'SUPER_ADMIN',
    });
  },

  login: async (credentials) => {
    const res = await authService.login(credentials);
    const { user: userData, token } = res.data.data;
    get()._setAuth(userData, token);
    return userData;
  },

  adminLogin: async (credentials) => {
    const res = await authService.adminLogin(credentials);
    const { user: userData, token } = res.data.data;
    get()._setAuth(userData, token);
    return userData;
  },

  register: async (data) => {
    const res = await authService.register(data);
    const { user: userData, token } = res.data.data;
    get()._setAuth(userData, token);
    return userData;
  },

  googleLogin: async (credential) => {
    const res = await authService.googleLogin({ credential });
    const { user: userData, token } = res.data.data;
    get()._setAuth(userData, token);
    return userData;
  },

  adminRegister: async (data) => {
    const res = await authService.adminRegister(data);
    const { user: userData, token } = res.data.data;
    get()._setAuth(userData, token);
    return userData;
  },

  setUser: (userData) => {
    const minimalUser = { id: userData.id, firstName: userData.firstName, role: userData.role, active: userData.active, phone: userData.phone };
    localStorage.setItem('user', JSON.stringify(minimalUser));
    set({
      user: userData,
      isAuthenticated: !!userData,
      isAdmin: userData?.role === 'ADMIN' || userData?.role === 'SUPER_ADMIN' || false,
      isSuperAdmin: userData?.role === 'SUPER_ADMIN' || false,
    });
  },

  logout: async () => {
    // Clear local state first to prevent stale UI
    localStorage.removeItem('user');
    localStorage.removeItem('selectedBinge');
    localStorage.removeItem('token_exp');
    set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false });
    // Clear httpOnly cookies via backend
    try { await api.post('/auth/logout'); } catch (_) { /* best-effort */ }
  },
};});

export default useAuthStore;
