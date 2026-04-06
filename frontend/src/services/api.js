import axios from 'axios';
import { toast } from 'react-toastify';

const api = axios.create({
  baseURL: '/api/v1',
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,  // Send httpOnly cookies automatically
});

api.interceptors.request.use((config) => {
  // Auto-attach selected binge for multi-tenancy
  const binge = localStorage.getItem('selectedBinge');
  if (binge) {
    try {
      const { id } = JSON.parse(binge);
      if (id) config.headers['X-Binge-Id'] = id;
    } catch (_) { /* ignore parse errors */ }
  }
  return config;
});

/**
 * Extract a user-friendly error message from backend responses.
 * Handles Spring Boot's default error body, our ApiResponse wrapper, and plain strings.
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
  const validationDetails = data.data && typeof data.data === 'object'
    ? Object.values(data.data).find((value) => typeof value === 'string' && value.trim())
    : null;
  if (validationDetails) return validationDetails;
  return data.message || data.error || 'Something went wrong. Please try again.';
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
      // Don't try to refresh if we're already on a login/refresh call
      if (originalRequest.url?.includes('/auth/login') ||
          originalRequest.url?.includes('/auth/admin/login') ||
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
          localStorage.setItem('user', JSON.stringify(data.data.user));
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
  // Call backend logout to clear httpOnly cookies
  axios.post('/api/v1/auth/logout', {}, { withCredentials: true }).catch(() => {});
  if (!window.location.pathname.includes('/login')) {
    toast.error('Session expired. Please log in again.');
    window.location.href = '/login';
  }
}

export default api;
