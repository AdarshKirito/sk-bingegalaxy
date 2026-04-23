import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

const { bookingGetByRef, paymentGetByBooking, paymentInitiate, paymentCallback } = vi.hoisted(() => ({
  bookingGetByRef: vi.fn(),
  paymentGetByBooking: vi.fn(),
  paymentInitiate: vi.fn(),
  paymentCallback: vi.fn(),
}));

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

vi.mock('../services/analytics', () => ({
  trackPaymentStarted: vi.fn(),
  trackPaymentCompleted: vi.fn(),
  trackPaymentFailed: vi.fn(),
}));

vi.mock('../services/endpoints', () => ({
  bookingService: {
    getByRef: bookingGetByRef,
  },
  paymentService: {
    getByBooking: paymentGetByBooking,
    initiate: paymentInitiate,
    callback: paymentCallback,
  },
  toArray: (value) => (Array.isArray(value) ? value : []),
}));

vi.mock('../stores/bingeStore', () => ({
  default: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

import PaymentPage from '../pages/PaymentPage';

function renderPaymentRoute() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/payment/SKBG25123456']}>
        <Routes>
          <Route path="/payment/:ref" element={<PaymentPage />} />
        </Routes>
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('PaymentPage balance-due behavior', () => {
  beforeEach(() => {
    vi.clearAllMocks();

    bookingGetByRef.mockResolvedValue({
      data: {
        data: {
          bookingRef: 'SKBG25123456',
          bookingDate: '2026-04-25',
          startTime: '14:00',
          eventType: { name: 'Birthday Party' },
          status: 'CONFIRMED',
          paymentStatus: 'SUCCESS',
          totalAmount: 1200,
          collectedAmount: 1000,
          balanceDue: 200,
          baseAmount: 1200,
          addOnAmount: 0,
          guestAmount: 0,
          numberOfGuests: 2,
          durationMinutes: 120,
          customerName: 'John Doe',
          customerEmail: 'john@example.com',
        },
      },
    });

    paymentGetByBooking.mockResolvedValue({
      data: {
        data: [
          {
            bookingRef: 'SKBG25123456',
            status: 'SUCCESS',
            transactionId: 'txn_001',
            paymentMethod: 'UPI',
            amount: 1000,
            createdAt: '2026-04-20T12:00:00Z',
          },
        ],
      },
    });
  });

  it('shows outstanding balance call-to-action instead of settled success CTA', async () => {
    renderPaymentRoute();

    await waitFor(() => {
      expect(screen.getByText('Outstanding balance of ₹200')).toBeInTheDocument();
    });

    expect(screen.getByRole('button', { name: /Pay Outstanding Balance ₹200/i })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Open Booking Summary/i })).not.toBeInTheDocument();
  });
});
