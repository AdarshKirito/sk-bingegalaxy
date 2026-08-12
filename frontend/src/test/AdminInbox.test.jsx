import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
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

/**
 * Wrapped in a router because the canonical booking reference is now a LINK into the
 * PMS, not printed text. That reference is the answer to "did the reservation actually
 * arrive?", and leaving it inert meant copying it into the bookings search by hand.
 */
function renderInbox() {
  return render(
    <MemoryRouter initialEntries={['/admin/inbox']}>
      <AdminInbox />
    </MemoryRouter>
  );
}

describe('AdminInbox', () => {
  beforeEach(() => {
    inbox.mockReset();
    retry.mockReset();
    inbox.mockResolvedValue({ data: { data: [] } });
  });

  it('renders and explains the empty state', async () => {
    renderInbox();
    await waitFor(() => expect(screen.getByText(/No messages yet/i)).toBeInTheDocument());
    // Empty is ambiguous between "no channel live" and "something broken".
    expect(screen.getByText(/Feed-only destinations/i)).toBeInTheDocument();
  });

  it('shows an applied message with its canonical booking reference', async () => {
    inbox.mockResolvedValue({ data: { data: [entry()] } });
    renderInbox();
    await waitFor(() => expect(screen.getByText('APPLIED')).toBeInTheDocument());
    expect(screen.getByText(/SKBG26ABC/)).toBeInTheDocument();
  });

  it('links that reference into the PMS, using the param AdminBookings reads', async () => {
    inbox.mockResolvedValue({ data: { data: [entry()] } });
    renderInbox();

    const link = await screen.findByRole('link', { name: /SKBG26ABC/ });
    // ?ref= specifically: AdminBookings used to read only ?search=, so every existing
    // ?ref= deep link in the console silently did nothing. Both are accepted now, and
    // this pins the one the rest of the app already sends.
    expect(link).toHaveAttribute('href', '/admin/bookings?ref=SKBG26ABC');
  });

  it('offers Retry only for FAILED, never for REJECTED or SUPERSEDED', async () => {
    inbox.mockResolvedValue({ data: { data: [
      entry({ id: 1, status: 'REJECTED', rejectReason: 'slot taken 40s earlier' }),
      entry({ id: 2, status: 'SUPERSEDED', rejectReason: 'sequence 6 does not exceed applied 7' }),
    ] } });
    renderInbox();

    await waitFor(() => expect(screen.getByText('REJECTED')).toBeInTheDocument());
    // Retrying a rejection fails identically or succeeds against a slot since taken;
    // retrying a superseded message re-applies one already overtaken.
    expect(screen.queryByRole('button', { name: /Retry/i })).not.toBeInTheDocument();
  });

  it('retries a FAILED message', async () => {
    inbox.mockResolvedValue({ data: { data: [entry({ status: 'FAILED', rejectReason: 'timeout' })] } });
    retry.mockResolvedValue({});
    renderInbox();

    await userEvent.click(await screen.findByRole('button', { name: /Retry/i }));
    await waitFor(() => expect(retry).toHaveBeenCalledWith(1));
  });

  it('distinguishes SUPERSEDED from an error, in plain language', async () => {
    inbox.mockResolvedValue({ data: { data: [
      entry({ status: 'SUPERSEDED', rejectReason: 'sequence 6 does not exceed applied 7' }),
    ] } });
    renderInbox();

    // Superseded means a newer message won — nothing is wrong. Presenting it as an
    // error would send an operator chasing a non-problem.
    await waitFor(() => expect(screen.getByText('SUPERSEDED')).toBeInTheDocument());
    expect(screen.getByText(/does not exceed applied 7/)).toBeInTheDocument();
  });

  it('flags when ordering was receipt order rather than provider-supplied', async () => {
    inbox.mockResolvedValue({ data: { data: [entry({ orderingBasis: 'RECEIPT_ORDER' })] } });
    renderInbox();
    // An operator reconciling a dispute needs to know the sequence was luck.
    await waitFor(() =>
      expect(screen.getByText(/order not provider-supplied/i)).toBeInTheDocument());
  });

  it('survives an API failure without taking the page down', async () => {
    inbox.mockRejectedValue({ response: { data: { message: 'boom' } } });
    renderInbox();
    await waitFor(() => expect(inbox).toHaveBeenCalled());
    expect(screen.getByText(/Reservation inbox/i)).toBeInTheDocument();
  });
});
