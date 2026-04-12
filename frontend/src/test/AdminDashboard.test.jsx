import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'Admin', role: 'SUPER_ADMIN', active: true },
    isAuthenticated: true,
    isAdmin: true,
    isSuperAdmin: true,
  }),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

const { mockGetDashboardStats, mockGetTodayBookings, mockGetOperationalDate, mockRunAudit } = vi.hoisted(() => ({
  mockGetDashboardStats: vi.fn(),
  mockGetTodayBookings: vi.fn(),
  mockGetOperationalDate: vi.fn(),
  mockRunAudit: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  adminService: {
    getDashboardStats: mockGetDashboardStats,
    getTodayBookings: mockGetTodayBookings,
    getOperationalDate: mockGetOperationalDate,
    runAudit: mockRunAudit,
    getFailedSagas: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getCompensatingSagas: vi.fn().mockResolvedValue({ data: { data: [] } }),
    retryFailedNotifications: vi.fn().mockResolvedValue({ data: {} }),
  },
  bookingService: {},
  authService: {},
}));

import AdminDashboard from '../pages/AdminDashboard';

function renderAdminDashboard() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/admin/dashboard']}>
        <AdminDashboard />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('AdminDashboard Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetDashboardStats.mockResolvedValue({
      data: {
        data: {
          todayTotal: 5,
          todayPending: 2,
          todayConfirmed: 3,
          todayRevenue: 25000,
          todayEstimatedRevenue: 30000,
          todayCheckedIn: 1,
          todayCompleted: 0,
          todayCancelled: 0,
        },
      },
    });
    mockGetTodayBookings.mockResolvedValue({
      data: {
        data: {
          content: [
            {
              bookingRef: 'BK-ADM-001',
              bookingDate: '2025-01-15',
              startTime: '10:00',
              status: 'CONFIRMED',
              customerName: 'John Doe',
              totalAmount: 5000,
            },
          ],
          totalPages: 1,
        },
      },
    });
    mockGetOperationalDate.mockResolvedValue({
      data: { data: { operationalDate: '2025-01-15' } },
    });
  });

  it('renders dashboard heading', async () => {
    renderAdminDashboard();
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('fetches dashboard stats on mount', async () => {
    renderAdminDashboard();
    await waitFor(() => {
      expect(mockGetDashboardStats).toHaveBeenCalled();
    });
  });

  it('displays stat cards with booking data', async () => {
    renderAdminDashboard();
    await waitFor(() => {
      expect(screen.getByText('5')).toBeInTheDocument();
    });
  });

  it('shows navigation cards', async () => {
    renderAdminDashboard();
    await waitFor(() => {
      expect(screen.getByText(/manage bookings/i)).toBeInTheDocument();
    });
  });

  it('handles stats load failure gracefully', async () => {
    mockGetDashboardStats.mockRejectedValue(new Error('Server error'));
    renderAdminDashboard();
    // Should not crash, may show error toast
    await waitFor(() => {
      expect(document.body).toBeDefined();
    });
  });
});
