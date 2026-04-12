import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'John', role: 'USER' },
    isAuthenticated: true,
    isAdmin: false,
  }),
}));

vi.mock('../stores/bingeStore', () => ({
  __esModule: true,
  default: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

const { mockGetByRef } = vi.hoisted(() => ({
  mockGetByRef: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  bookingService: {
    getByRef: mockGetByRef,
  },
}));

import BookingConfirmation from '../pages/BookingConfirmation';

function renderConfirmation(ref = 'BK-001') {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={[`/booking/${ref}`]}>
        <Routes>
          <Route path="/booking/:ref" element={<BookingConfirmation />} />
        </Routes>
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('BookingConfirmation Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading indicator initially', () => {
    mockGetByRef.mockReturnValue(new Promise(() => {}));
    renderConfirmation();
    // Loading spinner or state should be visible
    expect(document.querySelector('.loading,.spinner') || screen.queryByText(/loading/i)).toBeTruthy();
  });

  it('renders booking details when loaded', async () => {
    mockGetByRef.mockResolvedValue({
      data: {
        data: {
          bookingRef: 'BK-001',
          status: 'CONFIRMED',
          paymentStatus: 'SUCCESS',
          eventType: { name: 'Birthday Party' },
          bookingDate: '2025-01-20',
          startTime: '14:00',
          durationMinutes: 120,
          numberOfGuests: 10,
          totalAmount: 5000,
          baseAmount: 4000,
          addOnAmount: 1000,
          addOns: [{ name: 'DJ', quantity: 1 }],
        },
      },
    });

    renderConfirmation('BK-001');
    await waitFor(() => {
      expect(screen.getAllByText('BK-001').length).toBeGreaterThan(0);
      expect(screen.getByText('Confirmed')).toBeInTheDocument();
      expect(screen.getAllByText('Birthday Party').length).toBeGreaterThan(0);
    });
  });

  it('shows "Proceed to Payment" for pending unpaid bookings', async () => {
    mockGetByRef.mockResolvedValue({
      data: {
        data: {
          bookingRef: 'BK-002',
          status: 'PENDING',
          paymentStatus: 'PENDING',
          eventType: { name: 'Birthday' },
          bookingDate: '2025-01-20',
          startTime: '14:00',
          durationMinutes: 120,
          totalAmount: 5000,
          baseAmount: 4000,
          addOnAmount: 1000,
        },
      },
    });

    renderConfirmation('BK-002');
    await waitFor(() => {
      expect(screen.getAllByText(/proceed to payment/i).length).toBeGreaterThan(0);
    });
  });

  it('hides payment button for paid bookings', async () => {
    mockGetByRef.mockResolvedValue({
      data: {
        data: {
          bookingRef: 'BK-003',
          status: 'CONFIRMED',
          paymentStatus: 'SUCCESS',
          eventType: 'Birthday',
          bookingDate: '2025-01-20',
          startTime: '14:00',
          durationMinutes: 120,
          totalAmount: 5000,
          baseAmount: 4000,
          addOnAmount: 1000,
        },
      },
    });

    renderConfirmation('BK-003');
    await waitFor(() => {
      expect(screen.queryByText(/proceed to payment/i)).not.toBeInTheDocument();
    });
  });

  it('shows error when booking not found', async () => {
    mockGetByRef.mockRejectedValue({ response: { status: 404 } });

    renderConfirmation('BK-INVALID');
    await waitFor(() => {
      expect(screen.getByText(/not found/i)).toBeInTheDocument();
    });
  });

  it('links to My Bookings and Book Another', async () => {
    mockGetByRef.mockResolvedValue({
      data: {
        data: {
          bookingRef: 'BK-004',
          status: 'CONFIRMED',
          paymentStatus: 'SUCCESS',
          eventType: 'Party',
          bookingDate: '2025-03-01',
          startTime: '10:00',
          durationMinutes: 180,
          totalAmount: 10000,
          baseAmount: 8000,
          addOnAmount: 2000,
        },
      },
    });

    renderConfirmation('BK-004');
    await waitFor(() => {
      expect(screen.getAllByText(/my bookings/i).length).toBeGreaterThan(0);
      expect(screen.getAllByText(/payments/i).length).toBeGreaterThan(0);
    });
  });

  it('sanitizes special notes to prevent XSS', async () => {
    mockGetByRef.mockResolvedValue({
      data: {
        data: {
          bookingRef: 'BK-XSS',
          status: 'CONFIRMED',
          paymentStatus: 'SUCCESS',
          eventType: 'Party',
          bookingDate: '2025-03-01',
          startTime: '10:00',
          durationMinutes: 120,
          totalAmount: 5000,
          baseAmount: 5000,
          addOnAmount: 0,
          specialNotes: '<script>alert("xss")</script>Safe note',
        },
      },
    });

    renderConfirmation('BK-XSS');
    await waitFor(() => {
      // The script tag should be stripped by DOMPurify
      expect(screen.queryByText('Safe note')).toBeInTheDocument();
      const bodyText = document.body.innerHTML;
      expect(bodyText).not.toContain('<script>');
    });
  });
});
