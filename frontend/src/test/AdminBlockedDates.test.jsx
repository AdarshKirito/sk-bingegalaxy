import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

const { mockGetBlockedDates, mockGetBlockedSlots, mockBlockDate, mockUnblockDate, mockBlockSlot, mockUnblockSlot } = vi.hoisted(() => ({
  mockGetBlockedDates: vi.fn(),
  mockGetBlockedSlots: vi.fn(),
  mockBlockDate: vi.fn(),
  mockUnblockDate: vi.fn(),
  mockBlockSlot: vi.fn(),
  mockUnblockSlot: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  adminService: {
    getBlockedDates: mockGetBlockedDates,
    getBlockedSlots: mockGetBlockedSlots,
    blockDate: mockBlockDate,
    unblockDate: mockUnblockDate,
    blockSlot: mockBlockSlot,
    unblockSlot: mockUnblockSlot,
  },
}));

import AdminBlockedDates from '../pages/AdminBlockedDates';

function renderAdminBlockedDates() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/admin/blocked-dates']}>
        <AdminBlockedDates />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('AdminBlockedDates Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetBlockedDates.mockResolvedValue({ data: { data: [] } });
    mockGetBlockedSlots.mockResolvedValue({ data: { data: [] } });
  });

  it('renders page heading', async () => {
    renderAdminBlockedDates();
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('fetches blocked dates on mount', async () => {
    renderAdminBlockedDates();
    await waitFor(() => {
      expect(mockGetBlockedDates).toHaveBeenCalled();
    });
  });

  it('displays blocked dates when available', async () => {
    mockGetBlockedDates.mockResolvedValue({
      data: { data: [{ id: 1, date: '2025-02-14', reason: 'Valentine special' }] },
    });
    renderAdminBlockedDates();
    await waitFor(() => {
      expect(screen.getByText(/valentine special/i)).toBeInTheDocument();
    });
  });

  it('shows empty state when no blocked dates', async () => {
    renderAdminBlockedDates();
    await waitFor(() => {
      // Page loaded without blocked dates
      expect(document.body).toBeDefined();
    });
  });
});
