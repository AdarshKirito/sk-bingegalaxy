import axios from 'axios';
import { toast } from 'react-toastify';

const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
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
  return data.message || data.error || 'Something went wrong. Please try again.';
}

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      if (!window.location.pathname.includes('/login')) {
        toast.error('Session expired. Please log in again.');
        window.location.href = '/login';
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

export default api;
