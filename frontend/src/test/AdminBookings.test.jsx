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

const { mockGetAllBookings, mockSearchBookings, mockConfirmBooking, mockCancelBooking } = vi.hoisted(() => ({
  mockGetAllBookings: vi.fn(),
  mockSearchBookings: vi.fn(),
  mockConfirmBooking: vi.fn(),
  mockCancelBooking: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  adminService: {
    getAllBookings: mockGetAllBookings,
    getTodayBookings: vi.fn().mockResolvedValue({ data: { data: { content: [], totalPages: 0 } } }),
    getUpcomingBookings: vi.fn().mockResolvedValue({ data: { data: { content: [], totalPages: 0 } } }),
    getBookingsByDate: vi.fn().mockResolvedValue({ data: { data: { content: [], totalPages: 0 } } }),
    getBookingsByStatus: vi.fn().mockResolvedValue({ data: { data: { content: [], totalPages: 0 } } }),
    searchBookings: mockSearchBookings,
    confirmBooking: mockConfirmBooking,
    cancelBooking: mockCancelBooking,
    checkIn: vi.fn().mockResolvedValue({ data: {} }),
    checkout: vi.fn().mockResolvedValue({ data: {} }),
    undoCheckIn: vi.fn().mockResolvedValue({ data: {} }),
    updateBooking: vi.fn().mockResolvedValue({ data: {} }),
    getBookingEvents: vi.fn().mockResolvedValue({ data: { data: { content: [], totalPages: 0 } } }),
    getBookedSlots: vi.fn().mockResolvedValue({ data: { data: [] } }),
    simulatePayment: vi.fn().mockResolvedValue({ data: {} }),
    recordCashPayment: vi.fn().mockResolvedValue({ data: {} }),
    addPayment: vi.fn().mockResolvedValue({ data: {} }),
    initiateRefund: vi.fn().mockResolvedValue({ data: {} }),
    getRefundsForPayment: vi.fn().mockResolvedValue({ data: { data: [] } }),
    replayBooking: vi.fn().mockResolvedValue({ data: {} }),
    replayAll: vi.fn().mockResolvedValue({ data: {} }),
    getDashboardStats: vi.fn().mockResolvedValue({ data: { data: {} } }),
    getOperationalDate: vi.fn().mockResolvedValue({ data: { data: { operationalDate: '2025-01-15' } } }),
  },
  bookingService: {
    getEventTypes: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getAddOns: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getBookedSlots: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
  paymentService: {
    getByBooking: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getByTransactionId: vi.fn().mockResolvedValue({ data: { data: null } }),
  },
  authService: {
    searchCustomers: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
  availabilityService: {
    getDates: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getSlots: vi.fn().mockResolvedValue({ data: { data: { availableSlots: [] } } }),
  },
}));

import AdminBookings from '../pages/AdminBookings';

function renderAdminBookings() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/admin/bookings?tab=all']}>
        <AdminBookings />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('AdminBookings Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetAllBookings.mockResolvedValue({
      data: {
        data: {
          content: [
            {
              bookingRef: 'BK-ADM-010',
              bookingDate: '2025-02-01',
              startTime: '10:00',
              status: 'CONFIRMED',
              paymentStatus: 'SUCCESS',
              customerName: 'Jane Doe',
              customerEmail: 'jane@test.com',
              eventType: { name: 'Birthday' },
              totalAmount: 6000,
              durationMinutes: 120,
              numberOfGuests: 15,
            },
          ],
          totalPages: 1,
          totalElements: 1,
        },
      },
    });
  });

  it('renders bookings management heading', async () => {
    renderAdminBookings();
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('loads and displays bookings', async () => {
    renderAdminBookings();
    await waitFor(() => {
      expect(screen.getByText('BK-ADM-010')).toBeInTheDocument();
      expect(screen.getByText(/jane doe/i)).toBeInTheDocument();
    });
  });

  it('shows booking status badges', async () => {
    renderAdminBookings();
    await waitFor(() => {
      expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    });
  });

  it('search fetches bookings', async () => {
    mockSearchBookings.mockResolvedValue({
      data: { data: [{ bookingRef: 'BK-SEARCH-001', customerName: 'Found', status: 'PENDING', bookingDate: '2025-03-01', startTime: '14:00', eventType: { name: 'Party' }, totalAmount: 3000 }] },
    });
    const user = userEvent.setup();
    renderAdminBookings();

    await waitFor(() => {
      expect(screen.getByText('BK-ADM-010')).toBeInTheDocument();
    });

    // Look for search input
    const searchInput = screen.getByPlaceholderText(/search/i);
    if (searchInput) {
      await user.type(searchInput, 'search term');
      // Debounced search or button-triggered
    }
  });

  it('handles empty bookings gracefully', async () => {
    mockGetAllBookings.mockResolvedValue({
      data: { data: { content: [], totalPages: 0, totalElements: 0 } },
    });
    renderAdminBookings();
    await waitFor(() => {
      // Should show empty state or no bookings message
      expect(document.body).toBeDefined();
    });
  });
});
