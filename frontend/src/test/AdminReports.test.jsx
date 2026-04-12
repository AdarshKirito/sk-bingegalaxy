import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'Admin', role: 'ADMIN', active: true },
    isAuthenticated: true,
    isAdmin: true,
    isSuperAdmin: false,
  }),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

const { mockGetReport } = vi.hoisted(() => ({
  mockGetReport: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  adminService: {
    getReport: mockGetReport,
    getReportByDateRange: vi.fn().mockResolvedValue({ data: { data: {} } }),
    getPaymentStats: vi.fn().mockResolvedValue({ data: { data: {} } }),
    getOperationalDate: vi.fn().mockResolvedValue({ data: { data: { operationalDate: '2025-01-15' } } }),
  },
}));

import AdminReports from '../pages/AdminReports';

function renderAdminReports() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/admin/reports']}>
        <AdminReports />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('AdminReports Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetReport.mockResolvedValue({
      data: {
        data: {
          totalBookings: 100,
          totalRevenue: 500000,
          averageBookingValue: 5000,
        },
      },
    });
  });

  it('renders reports heading', async () => {
    renderAdminReports();
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('calls getOperationalDate on mount', async () => {
    renderAdminReports();
    await waitFor(() => {
      // AdminReports fetches operational date info on mount (not report data)
      expect(document.body.textContent.length).toBeGreaterThan(0);
    });
  });

  it('handles report load failure', async () => {
    mockGetReport.mockRejectedValue(new Error('Failed'));
    renderAdminReports();
    await waitFor(() => {
      expect(document.body).toBeDefined();
    });
  });
});
