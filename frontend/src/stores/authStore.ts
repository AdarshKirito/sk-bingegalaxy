import { create } from 'zustand';
import { authService, authorityService } from '../services/endpoints';
import api from '../services/api';
import type { User } from '../types';

/**
 * Snapshot of the user's effective authority right now (native role + Authority
 * Handover delegation). Mirrors the auth-service `EffectiveAuthorityDto`.
 */
export interface EffectiveAuthority {
  role: string;
  superAdmin: boolean;
  delegated: boolean;
  scopes: string[];
  nextExpiryAt?: string | null;
}

interface AuthState {
  user: User | null;
  loading: boolean;
  isAuthenticated: boolean;
  isAdmin: boolean;
  isSuperAdmin: boolean;
  effectiveAuthority: EffectiveAuthority | null;
  _setAuth: (userData: User, token?: string) => void;
  refreshAuthority: () => Promise<void>;
  login: (credentials: { email: string; password: string; mfaCode?: string }) => Promise<{ user: User; mfaRequired?: boolean; mustChangePassword?: boolean }>;
  adminLogin: (credentials: { email: string; password: string; mfaCode?: string }) => Promise<{ user: User; mfaRequired?: boolean }>;
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

  // Validate stored user against the server on load.
  // Only 401/403 mean the token is genuinely invalid (clear session).
  // 5xx means the server is temporarily unavailable — keep the stored user
  // so the UI doesn't lose all local state on a backend blip.
  if (storedUser) {
    api.get('/auth/profile').then(res => {
      const userData = res.data?.data;
      if (userData && userData.mustChangePassword) {
        // Lingering temporary-password session — don't finalize; force re-auth so
        // the user completes the password change (gateway blocks everything else).
        localStorage.removeItem('user');
        set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false, effectiveAuthority: null, loading: false });
        if (typeof window !== 'undefined' && !window.location.pathname.endsWith('/login')) {
          window.location.href = '/login';
        }
      } else if (userData) {
        get()._setAuth(userData);
        set({ loading: false });
        get().refreshAuthority();
      } else {
        localStorage.removeItem('user');
        set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false, effectiveAuthority: null, loading: false });
      }
    }).catch((err) => {
      const status = err?.response?.status;
      if (status === 401 || status === 403) {
        // Token genuinely expired or revoked — clear session
        localStorage.removeItem('user');
        set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false, effectiveAuthority: null, loading: false });
      } else {
        // Network error or server-side 5xx — keep local session, mark loading done
        set({ loading: false });
      }
    });
  }

  return {
  user: storedUser,

  loading: !!storedUser,

  isAuthenticated: !!storedUser,
  isAdmin: storedUser?.role === 'ADMIN' || storedUser?.role === 'SUPER_ADMIN' || false,
  isSuperAdmin: storedUser?.role === 'SUPER_ADMIN' || false,
  effectiveAuthority: null,

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
    // Kick off authority lookup so the delegation banner / route guards render
    // with up-to-date scopes immediately after login. Non-blocking.
    void get().refreshAuthority();
  },

  /**
   * Refresh the user's Authority Handover snapshot from the server. Safe to call
   * any time; for unauthenticated users it clears the snapshot. Errors are
   * swallowed because delegation is additive — failing here should never break
   * the existing native auth flow.
   */
  refreshAuthority: async () => {
    if (!get().isAuthenticated) {
      set({ effectiveAuthority: null });
      return;
    }
    try {
      const res = await authorityService.getMyAuthority();
      const data = res.data?.data;
      if (data) {
        set({
          effectiveAuthority: {
            role: data.role,
            superAdmin: !!data.superAdmin,
            delegated: !!data.delegated,
            scopes: Array.isArray(data.scopes) ? data.scopes : [],
            nextExpiryAt: data.nextExpiryAt ?? null,
          },
        });
      }
    } catch {
      // Endpoint not yet deployed or transient error — leave snapshot alone.
    }
  },

  login: async (credentials) => {
    const res = await authService.login(credentials);
    const data = res.data.data;
    if (data?.mfaRequired) {
      return { user: data.user, mfaRequired: true };
    }
    if (data?.mustChangePassword) {
      // Signed in with an admin-issued temporary password. The backend has a
      // session cookie set, but we intentionally do NOT finalize local auth —
      // the caller must force a password change first, then re-login.
      return { user: data.user, mustChangePassword: true };
    }
    const { user: userData, token } = data;
    get()._setAuth(userData, token);
    return { user: userData };
  },

  adminLogin: async (credentials) => {
    const res = await authService.adminLogin(credentials);
    const data = res.data.data;
    if (data?.mfaRequired) {
      return { user: data.user, mfaRequired: true };
    }
    const { user: userData, token } = data;
    get()._setAuth(userData, token);
    return { user: userData };
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
    set({ user: null, isAuthenticated: false, isAdmin: false, isSuperAdmin: false, effectiveAuthority: null });
    // Clear httpOnly cookies via backend
    try { await api.post('/auth/logout'); } catch (_) { /* best-effort */ }
  },
};});

export default useAuthStore;
