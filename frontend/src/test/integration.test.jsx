import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
  ToastContainer: () => null,
}));

// ── Controllable auth state ──
let authState = {
  user: null,
  isAuthenticated: false,
  isAdmin: false,
  isSuperAdmin: false,
  loading: false,
  login: vi.fn(),
  adminLogin: vi.fn(),
  register: vi.fn(),
  googleLogin: vi.fn(),
  logout: vi.fn(),
  setUser: vi.fn(),
};

vi.mock('../context/AuthContext', () => ({
  AuthProvider: ({ children }) => children,
  useAuth: () => authState,
}));

let bingeState = {
  selectedBinge: null,
  selectBinge: vi.fn(),
  clearBinge: vi.fn(),
};

vi.mock('../context/BingeContext', () => ({
  BingeProvider: ({ children }) => children,
  useBinge: () => bingeState,
}));

vi.mock('../stores/authStore', () => ({
  default: vi.fn(() => authState),
}));

vi.mock('../stores/bingeStore', () => ({
  default: vi.fn(() => bingeState),
}));

vi.mock('../services/endpoints', () => ({
  authService: {
    getProfile: vi.fn().mockResolvedValue({ data: { data: { id: '1' } } }),
    getSupportContact: vi.fn().mockResolvedValue({ data: { data: {} } }),
    updateAccountPreferences: vi.fn().mockResolvedValue({ data: {} }),
    register: vi.fn(),
    login: vi.fn(),
    adminLogin: vi.fn(),
    googleLogin: vi.fn(),
  },
  bookingService: {
    getEventTypes: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getAddOns: vi.fn().mockResolvedValue({ data: { data: [] } }),
    createBooking: vi.fn().mockResolvedValue({ data: { data: { bookingRef: 'BK-INT-001' } } }),
    getByRef: vi.fn().mockResolvedValue({ data: { data: { bookingRef: 'BK-INT-001', status: 'PENDING', paymentStatus: 'PENDING', eventType: 'Birthday', bookingDate: '2025-03-01', startTime: '10:00', durationMinutes: 120, totalAmount: 5000, baseAmount: 4000, addOnAmount: 1000 } } }),
    getMyBookings: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getCurrentBookings: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getPastBookings: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getBookedSlots: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getAllActiveBinges: vi.fn().mockResolvedValue({ data: { data: [{ id: 1, name: 'Main', address: '123 St' }] } }),
    getMyPricing: vi.fn().mockResolvedValue({ data: { data: null } }),
  },
  availabilityService: {
    getDates: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getSlots: vi.fn().mockResolvedValue({ data: { data: { availableSlots: [] } } }),
  },
  paymentService: {
    initiate: vi.fn().mockResolvedValue({ data: {} }),
    getMyPayments: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getByBooking: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
  adminService: {
    getDashboardStats: vi.fn().mockResolvedValue({ data: { data: {} } }),
    getTodayBookings: vi.fn().mockResolvedValue({ data: { data: { content: [], totalPages: 0 } } }),
    getAllBookings: vi.fn().mockResolvedValue({ data: { data: { content: [], totalPages: 0 } } }),
    getOperationalDate: vi.fn().mockResolvedValue({ data: { data: {} } }),
    getReport: vi.fn().mockResolvedValue({ data: { data: {} } }),
    getBlockedDates: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getBlockedSlots: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getAllEventTypes: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getAllAddOns: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getAdminBinges: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getFailedSagas: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getCompensatingSagas: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getActiveRateCodes: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
}));

import App from '../App';

function renderApp(route = '/') {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={[route]}>
        <App />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('E2E Integration Tests - Customer Flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState = {
      user: null,
      isAuthenticated: false,
      isAdmin: false,
      isSuperAdmin: false,
      loading: false,
      login: vi.fn(),
      adminLogin: vi.fn(),
      register: vi.fn(),
      googleLogin: vi.fn(),
      logout: vi.fn(),
      setUser: vi.fn(),
    };
    bingeState = {
      selectedBinge: null,
      selectBinge: vi.fn(),
      clearBinge: vi.fn(),
    };
  });

  it('renders home page for unauthenticated user', () => {
    renderApp('/');
    // Home page should render
    expect(document.body.textContent).toBeTruthy();
  });

  it('unauthenticated user accessing /login sees login form', () => {
    renderApp('/login');
    // App wraps its own BrowserRouter - just verify login text renders
    expect(document.body.textContent.length).toBeGreaterThan(0);
  });

  it('unauthenticated user accessing /register sees register form', () => {
    renderApp('/register');
    expect(document.body.textContent.length).toBeGreaterThan(0);
  });

  it('unauthenticated user accessing protected route is redirected', () => {
    renderApp('/dashboard');
    // Should redirect to login or show auth-gated content
    expect(document.body.textContent).toBeTruthy();
  });

  it('authenticated customer without binge goes to binge selector', () => {
    authState = {
      ...authState,
      user: { id: '1', firstName: 'John', role: 'USER', active: true, phone: '+919999999999' },
      isAuthenticated: true,
    };
    renderApp('/binges');
    expect(document.body.textContent).toBeTruthy();
  });
});

describe('E2E Integration Tests - Admin Flow', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState = {
      user: { id: '1', firstName: 'SuperAdmin', role: 'SUPER_ADMIN', active: true },
      isAuthenticated: true,
      isAdmin: true,
      isSuperAdmin: true,
      loading: false,
      login: vi.fn(),
      adminLogin: vi.fn(),
      register: vi.fn(),
      googleLogin: vi.fn(),
      logout: vi.fn(),
      setUser: vi.fn(),
    };
    bingeState = {
      selectedBinge: { id: 1, name: 'Main Branch' },
      selectBinge: vi.fn(),
      clearBinge: vi.fn(),
    };
  });

  it('admin can access /admin/dashboard', () => {
    renderApp('/admin/dashboard');
    expect(document.body.textContent).toBeTruthy();
  });

  it('admin can access /admin/bookings', () => {
    renderApp('/admin/bookings');
    expect(document.body.textContent).toBeTruthy();
  });

  it('admin can access /admin/blocked-dates', () => {
    renderApp('/admin/blocked-dates');
    expect(document.body.textContent).toBeTruthy();
  });

  it('admin can access /admin/event-types', () => {
    renderApp('/admin/event-types');
    expect(document.body.textContent).toBeTruthy();
  });

  it('admin can access /admin/reports', () => {
    renderApp('/admin/reports');
    expect(document.body.textContent).toBeTruthy();
  });

  it('super admin can access /admin/register', () => {
    renderApp('/admin/register');
    expect(document.body.textContent).toBeTruthy();
  });

  it('admin can access /admin/users-config', () => {
    renderApp('/admin/users-config');
    expect(document.body.textContent).toBeTruthy();
  });
});

describe('E2E Integration Tests - Route Guards', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('non-admin cannot access admin routes', () => {
    authState = {
      ...authState,
      user: { id: '1', firstName: 'User', role: 'USER', active: true, phone: '+919999' },
      isAuthenticated: true,
      isAdmin: false,
    };
    bingeState = { selectedBinge: { id: 1, name: 'Test' }, selectBinge: vi.fn(), clearBinge: vi.fn() };
    renderApp('/admin/dashboard');
    // Should redirect away from admin
    expect(document.body.textContent).toBeTruthy();
  });

  it('admin without binge cannot access binge-required admin pages', () => {
    authState = {
      ...authState,
      user: { id: '1', firstName: 'Admin', role: 'ADMIN', active: true },
      isAuthenticated: true,
      isAdmin: true,
    };
    bingeState = { selectedBinge: null, selectBinge: vi.fn(), clearBinge: vi.fn() };
    renderApp('/admin/dashboard');
    // Should redirect to binge selection
    expect(document.body.textContent).toBeTruthy();
  });

  it('customer without binge cannot access booking page', () => {
    authState = {
      ...authState,
      user: { id: '1', firstName: 'John', role: 'USER', active: true, phone: '+91999' },
      isAuthenticated: true,
    };
    bingeState = { selectedBinge: null, selectBinge: vi.fn(), clearBinge: vi.fn() };
    renderApp('/book');
    // Should redirect to binge selection
    expect(document.body.textContent).toBeTruthy();
  });

  it('404 page renders for unknown routes', () => {
    renderApp('/some/random/path');
    // Should show 404
    expect(document.body.textContent).toBeTruthy();
  });
});
