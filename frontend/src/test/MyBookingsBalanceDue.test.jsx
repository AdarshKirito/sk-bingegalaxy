import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

const { getCurrentBookings, getPastBookings } = vi.hoisted(() => ({
  getCurrentBookings: vi.fn(),
  getPastBookings: vi.fn(),
}));

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'John', role: 'USER', active: true },
    isAuthenticated: true,
    isAdmin: false,
  }),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
    selectBinge: vi.fn(),
    clearBinge: vi.fn(),
  }),
}));

vi.mock('../services/endpoints', () => ({
  bookingService: {
    getCurrentBookings,
    getPastBookings,
    getPendingReviews: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getMyWaitlist: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
  authService: {
    getSupportContact: vi.fn().mockResolvedValue({ data: { data: {} } }),
  },
  toArray: (value) => (Array.isArray(value) ? value : []),
}));

import MyBookings from '../pages/MyBookings';

function renderPage() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/my-bookings']}>
        <MyBookings />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('MyBookings outstanding-balance action', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    getCurrentBookings.mockResolvedValue({
      data: {
        data: [
          {
            bookingRef: 'SKBG25123456',
            bookingDate: '2026-04-25',
            startTime: '14:00',
            status: 'CONFIRMED',
            paymentStatus: 'SUCCESS',
            totalAmount: 1200,
            balanceDue: 200,
            eventType: { name: 'Birthday Party' },
            durationMinutes: 120,
            numberOfGuests: 2,
            addOns: [],
            canCustomerCancel: false,
            canCustomerReschedule: false,
            canCustomerTransfer: false,
          },
        ],
      },
    });

    getPastBookings.mockResolvedValue({ data: { data: [] } });
  });

  it('renders Pay Balance action when paymentStatus is SUCCESS but balanceDue is still positive', async () => {
    renderPage();

    await waitFor(() => {
      expect(screen.getAllByRole('link', { name: /Pay Balance ₹200/i }).length).toBeGreaterThan(0);
    });
  });
});
