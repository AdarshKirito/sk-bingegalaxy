import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdminInbox from '../pages/AdminInbox';

const inbox = vi.fn();
const retry = vi.fn();
vi.mock('../services/endpoints', () => ({
  adminService: {
    getDistributionInbox: (...a) => inbox(...a),
    retryDistributionInboxEntry: (...a) => retry(...a),
  },
}));
vi.mock('react-toastify', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const entry = (over = {}) => ({
  id: 1, connectionId: 7, destinationCode: 'VIATOR', destinationName: 'Viator',
  externalRef: 'EXT-1', messageType: 'CREATE', status: 'APPLIED',
  orderingBasis: 'PROVIDER_SEQUENCE', externalSequence: 3,
  receivedAt: '2026-08-01T10:00:00', processedAt: '2026-08-01T10:00:01',
  bookingRef: 'SKBG26ABC', rejectReason: null, ...over,
});

describe('AdminInbox', () => {
  beforeEach(() => {
    inbox.mockReset();
    retry.mockReset();
    inbox.mockResolvedValue({ data: { data: [] } });
  });

  it('renders and explains the empty state', async () => {
    render(<AdminInbox />);
    await waitFor(() => expect(screen.getByText(/No messages yet/i)).toBeInTheDocument());
    // Empty is ambiguous between "no channel live" and "something broken".
    expect(screen.getByText(/Feed-only destinations/i)).toBeInTheDocument();
  });

  it('shows an applied message with its canonical booking reference', async () => {
    inbox.mockResolvedValue({ data: { data: [entry()] } });
    render(<AdminInbox />);
    await waitFor(() => expect(screen.getByText('APPLIED')).toBeInTheDocument());
    expect(screen.getByText(/SKBG26ABC/)).toBeInTheDocument();
  });

  it('offers Retry only for FAILED, never for REJECTED or SUPERSEDED', async () => {
    inbox.mockResolvedValue({ data: { data: [
      entry({ id: 1, status: 'REJECTED', rejectReason: 'slot taken 40s earlier' }),
      entry({ id: 2, status: 'SUPERSEDED', rejectReason: 'sequence 6 does not exceed applied 7' }),
    ] } });
    render(<AdminInbox />);

    await waitFor(() => expect(screen.getByText('REJECTED')).toBeInTheDocument());
    // Retrying a rejection fails identically or succeeds against a slot since taken;
    // retrying a superseded message re-applies one already overtaken.
    expect(screen.queryByRole('button', { name: /Retry/i })).not.toBeInTheDocument();
  });

  it('retries a FAILED message', async () => {
    inbox.mockResolvedValue({ data: { data: [entry({ status: 'FAILED', rejectReason: 'timeout' })] } });
    retry.mockResolvedValue({});
    render(<AdminInbox />);

    await userEvent.click(await screen.findByRole('button', { name: /Retry/i }));
    await waitFor(() => expect(retry).toHaveBeenCalledWith(1));
  });

  it('distinguishes SUPERSEDED from an error, in plain language', async () => {
    inbox.mockResolvedValue({ data: { data: [
      entry({ status: 'SUPERSEDED', rejectReason: 'sequence 6 does not exceed applied 7' }),
    ] } });
    render(<AdminInbox />);

    // Superseded means a newer message won — nothing is wrong. Presenting it as an
    // error would send an operator chasing a non-problem.
    await waitFor(() => expect(screen.getByText('SUPERSEDED')).toBeInTheDocument());
    expect(screen.getByText(/does not exceed applied 7/)).toBeInTheDocument();
  });

  it('flags when ordering was receipt order rather than provider-supplied', async () => {
    inbox.mockResolvedValue({ data: { data: [entry({ orderingBasis: 'RECEIPT_ORDER' })] } });
    render(<AdminInbox />);
    // An operator reconciling a dispute needs to know the sequence was luck.
    await waitFor(() =>
      expect(screen.getByText(/order not provider-supplied/i)).toBeInTheDocument());
  });

  it('survives an API failure without taking the page down', async () => {
    inbox.mockRejectedValue({ response: { data: { message: 'boom' } } });
    render(<AdminInbox />);
    await waitFor(() => expect(inbox).toHaveBeenCalled());
    expect(screen.getByText(/Reservation inbox/i)).toBeInTheDocument();
  });
});
