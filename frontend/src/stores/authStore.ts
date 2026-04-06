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
  _setAuth: (userData: User) => void;
  login: (credentials: { email: string; password: string }) => Promise<User>;
  adminLogin: (credentials: { email: string; password: string }) => Promise<User>;
  register: (data: Record<string, unknown>) => Promise<User>;
  googleLogin: (credential: string) => Promise<User>;
  adminRegister: (data: Record<string, unknown>) => Promise<User>;
  setUser: (userData: User) => void;
  logout: () => void;
}

const useAuthStore = create<AuthState>((set, get) => {
  const storedUser: User | null = (() => {
    try {
      const stored = localStorage.getItem('user');
      return stored ? JSON.parse(stored) : null;
    } catch { return null; }
  })();

  return {
  user: storedUser,

  loading: false,

  isAuthenticated: !!storedUser,
  isAdmin: storedUser?.role === 'ADMIN' || storedUser?.role === 'SUPER_ADMIN' || false,
  isSuperAdmin: storedUser?.role === 'SUPER_ADMIN' || false,

  _setAuth: (userData) => {
    // Tokens are stored in httpOnly cookies by the backend — only user data goes to localStorage
    localStorage.setItem('user', JSON.stringify(userData));
    set({
      user: userData,
      isAuthenticated: true,
      isAdmin: userData.role === 'ADMIN' || userData.role === 'SUPER_ADMIN',
      isSuperAdmin: userData.role === 'SUPER_ADMIN',
    });
  },

  login: async (credentials) => {
    const res = await authService.login(credentials);
    const { user: userData } = res.data.data;
    get()._setAuth(userData);
    return userData;
  },

  adminLogin: async (credentials) => {
    const res = await authService.adminLogin(credentials);
    const { user: userData } = res.data.data;
    get()._setAuth(userData);
    return userData;
  },

  register: async (data) => {
    const res = await authService.register(data);
    const { user: userData } = res.data.data;
    get()._setAuth(userData);
    return userData;
  },

  googleLogin: async (credential) => {
    const res = await authService.googleLogin({ credential });
    const { user: userData } = res.data.data;
    get()._setAuth(userData);
    return userData;
  },

  adminRegister: async (data) => {
    const res = await authService.adminRegister(data);
    const { user: userData } = res.data.data;
    get()._setAuth(userData);
    return userData;
  },

  setUser: (userData) => {
    localStorage.setItem('user', JSON.stringify(userData));
    set({
      user: userData,
      isAuthenticated: !!userData,
      isAdmin: userData?.role === 'ADMIN' || userData?.role === 'SUPER_ADMIN' || false,
      isSuperAdmin: userData?.role === 'SUPER_ADMIN' || false,
    });
  },

  logout: () => {
    localStorage.removeItem('user');
    localStorage.removeItem('selectedBinge');
    // Clear httpOnly cookies via backend
    api.post('/auth/logout').catch(() => {});
    set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false });
  },
};});

export default useAuthStore;
