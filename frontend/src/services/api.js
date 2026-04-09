import axios from 'axios';
import { toast } from 'react-toastify';

const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,  // Send httpOnly cookies automatically
  timeout: 15000,
});

api.interceptors.request.use(async (config) => {
  // Auto-attach selected binge for multi-tenancy
  const binge = localStorage.getItem('selectedBinge');
  if (binge) {
    try {
      const { id } = JSON.parse(binge);
      if (id) config.headers['X-Binge-Id'] = id;
    } catch (_) { /* ignore parse errors */ }
  }

  // Proactive token refresh: if the JWT will expire within 60 seconds, silently refresh
  // before sending the request. Prevents mid-workflow 401s when an admin fills out a long form.
  const tokenExp = localStorage.getItem('token_exp');
  if (tokenExp && !config.url?.includes('/auth/refresh') && !isRefreshing) {
    const secondsLeft = Number(tokenExp) - Math.floor(Date.now() / 1000);
    if (secondsLeft > 0 && secondsLeft < 60) {
      isRefreshing = true;
      try {
        const { data } = await axios.post('/api/v1/auth/refresh', {}, {
          headers: { 'Content-Type': 'application/json' },
          withCredentials: true,
        });
        if (data.data?.user) {
          const minUser = { id: data.data.user.id, firstName: data.data.user.firstName, role: data.data.user.role, active: data.data.user.active, phone: data.data.user.phone };
          localStorage.setItem('user', JSON.stringify(minUser));
        }
        if (data.data?.token) {
          try {
            const [, payload] = data.data.token.split('.');
            const { exp } = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
            localStorage.setItem('token_exp', String(exp));
          } catch { /* ignore */ }
        }
        processQueue(null);
      } catch (_) { /* fall through; reactive 401 handler will catch real expiry */ }
      isRefreshing = false;
    }
  }

  return config;
});

/**
 * Extract a user-friendly error message from backend responses.
 * Sanitizes messages to avoid leaking internal details to the UI.
 */
function extractErrorMessage(err) {
  const data = err.response?.data;
  if (!data) {
    if (err.code === 'ERR_NETWORK') return 'Unable to connect to the server. Please check your internet connection.';
    if (err.code === 'ECONNABORTED') return 'Request timed out. Please try again.';
    return 'Something went wrong. Please try again.';
  }
  // Our ApiResponse wrapper: { message: '...' } or Spring's { error: '...', message: '...' }
  if (typeof data === 'string') return data;
  // Return generic validation message without exposing field-level details
  const validationDetails = data.data && typeof data.data === 'object'
    ? Object.values(data.data).find((value) => typeof value === 'string' && value.trim())
    : null;
  if (validationDetails) {
    // Strip any internal details — only show validation-safe messages
    const msg = validationDetails;
    if (/email.*unique|already.*registered|already.*exists/i.test(msg)) {
      return 'Please check your information and try again.';
    }
    return msg;
  }
  const message = data.message || data.error || 'Something went wrong. Please try again.';
  // Suppress messages that could leak user-enumeration info
  if (/email.*unique|already.*registered|already.*exists/i.test(message)) {
    return 'Please check your information and try again.';
  }
  return message;
}

// ── Silent refresh token logic ──────────────────────────────
let isRefreshing = false;
let failedQueue = [];

function processQueue(error, token = null) {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) reject(error);
    else resolve(token);
  });
  failedQueue = [];
}

api.interceptors.response.use(
  (res) => res,
  async (err) => {
    const originalRequest = err.config;

    // If 401 and we haven't already retried this request
    if (err.response?.status === 401 && !originalRequest._retry) {
      // Don't try to refresh if we're already on an auth call
      if (originalRequest.url?.includes('/auth/login') ||
          originalRequest.url?.includes('/auth/admin/login') ||
          originalRequest.url?.includes('/auth/google') ||
          originalRequest.url?.includes('/auth/register') ||
          originalRequest.url?.includes('/auth/refresh')) {
        return Promise.reject(err);
      }

      if (isRefreshing) {
        // Queue this request until the refresh completes
        return new Promise((resolve, reject) => {
          failedQueue.push({ resolve, reject });
        }).then(() => {
          return api(originalRequest);
        });
      }

      originalRequest._retry = true;
      isRefreshing = true;

      try {
        // Refresh token is sent automatically via httpOnly cookie
        const { data } = await axios.post('/api/v1/auth/refresh', {}, {
          headers: { 'Content-Type': 'application/json' },
          withCredentials: true,
        });

        if (data.data?.user) {
          const minUser = { id: data.data.user.id, firstName: data.data.user.firstName, role: data.data.user.role, active: data.data.user.active, phone: data.data.user.phone };
          localStorage.setItem('user', JSON.stringify(minUser));
        }
        if (data.data?.token) {
          try {
            const [, payload] = data.data.token.split('.');
            const { exp } = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')));
            localStorage.setItem('token_exp', String(exp));
          } catch { /* ignore */ }
        }

        isRefreshing = false;
        processQueue(null);

        return api(originalRequest);
      } catch (refreshErr) {
        isRefreshing = false;
        processQueue(refreshErr, null);
        forceLogout();
        return Promise.reject(refreshErr);
      }
    } else if (err.response?.status === 403) {
      toast.error('You do not have permission to perform this action.');
    } else if (err.response?.status === 429) {
      toast.error('Too many attempts. Please wait a moment and try again.');
    } else if (err.response?.status >= 500) {
      toast.error('Server error. Please try again later.');
    } else if (!err.response) {
      // Network error / timeout
      toast.error(extractErrorMessage(err));
    }

    // Attach the extracted message for callers to use
    err.userMessage = extractErrorMessage(err);
    return Promise.reject(err);
  }
);

function forceLogout() {
  localStorage.removeItem('user');
  localStorage.removeItem('selectedBinge');
  localStorage.removeItem('token_exp');
  // Call backend logout to clear httpOnly cookies
  axios.post('/api/v1/auth/logout', {}, { withCredentials: true }).catch(() => {});
  const isAdminPath = window.location.pathname.startsWith('/admin');
  const loginPath = isAdminPath ? '/admin/login' : '/login';
  if (!window.location.pathname.endsWith(loginPath)) {
    toast.error('Session expired. Please log in again.');
    window.location.href = loginPath;
  }
}

export default api;
