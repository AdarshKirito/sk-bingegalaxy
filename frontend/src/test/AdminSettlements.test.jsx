import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AdminSettlements from '../pages/AdminSettlements';

const list = vi.fn();
const totals = vi.fn();
const payout = vi.fn();
vi.mock('../services/endpoints', () => ({
  adminService: {
    getDistributionSettlements: (...a) => list(...a),
    getDistributionSettlementTotals: (...a) => totals(...a),
    recordDistributionPayout: (...a) => payout(...a),
  },
}));
vi.mock('react-toastify', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const row = (over = {}) => ({
  id: 1, bookingRef: 'SKBG26A', destinationCode: 'VIATOR', settlementCurrency: 'INR',
  grossMinor: 300000, commissionMinor: 60000, outstandingMinor: 240000,
  collectedBy: 'CHANNEL', settlementStatus: 'EXPECTED', reconciliationStatus: 'UNMATCHED',
  expectedPayoutAt: '2026-09-01', actualPayoutAt: null, actualPayoutMinor: null,
  varianceMinor: 0, overdue: false, ...over,
});

describe('AdminSettlements', () => {
  beforeEach(() => {
    list.mockReset(); totals.mockReset(); payout.mockReset();
    list.mockResolvedValue({ data: { data: [] } });
    totals.mockResolvedValue({ data: { data: {} } });
  });

  it('renders and explains the empty state', async () => {
    render(<AdminSettlements />);
    await waitFor(() => expect(screen.getByText(/Nothing outstanding/i)).toBeInTheDocument());
    // Direct bookings settle through payments; saying so removes the ambiguity.
    expect(screen.getByText(/Direct bookings are settled through payments/i)).toBeInTheDocument();
  });

  it('shows one total per currency and NO grand total', async () => {
    totals.mockResolvedValue({ data: { data: { INR: 400000, USD: 5000 } } });
    render(<AdminSettlements />);

    await waitFor(() => expect(screen.getByText(/Outstanding · INR/)).toBeInTheDocument());
    expect(screen.getByText(/Outstanding · USD/)).toBeInTheDocument();
    // Summing across currencies would be a confident, meaningless number.
    expect(screen.queryByText(/Outstanding · Total/i)).not.toBeInTheDocument();
  });

  it('calls these receivables, not revenue', async () => {
    render(<AdminSettlements />);
    // Viator pays only AFTER the experience, so presenting this as cash in the bank
    // would misstate the venue's cash flow.
    await waitFor(() => expect(screen.getByText(/these are receivables/i)).toBeInTheDocument());
  });

  it('flags an overdue settlement', async () => {
    list.mockResolvedValue({ data: { data: [row({ overdue: true })] } });
    render(<AdminSettlements />);
    await waitFor(() => expect(screen.getByLabelText('Overdue')).toBeInTheDocument());
    expect(screen.getByText(/past the expected payout date/i)).toBeInTheDocument();
  });

  it('shows a short payment as SHORT_PAID with its variance', async () => {
    list.mockResolvedValue({ data: { data: [
      row({ settlementStatus: 'SHORT_PAID', varianceMinor: -50000 }),
    ] } });
    render(<AdminSettlements />);

    // A shortfall must stay visible until chased, not be settled quietly.
    await waitFor(() => expect(screen.getByText('SHORT_PAID')).toBeInTheDocument());
    expect(screen.getByText(/-₹500\.00|-INR 500\.00|₹-500\.00/)).toBeInTheDocument();
  });

  it('survives an API failure without taking the page down', async () => {
    list.mockRejectedValue({ response: { data: { message: 'boom' } } });
    render(<AdminSettlements />);
    await waitFor(() => expect(list).toHaveBeenCalled());
    expect(screen.getByText(/Channel settlements/i)).toBeInTheDocument();
  });
});
