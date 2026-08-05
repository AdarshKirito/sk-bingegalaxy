import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdminListings from '../pages/AdminListings';

const listings = vi.fn();
const publish = vi.fn();
vi.mock('../services/endpoints', () => ({
  adminService: {
    getDistributionListings: (...a) => listings(...a),
    publishDistributionListing: (...a) => publish(...a),
    evaluateDistributionListing: vi.fn(),
  },
}));
vi.mock('react-toastify', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const row = (over = {}) => ({
  id: 1, eventTypeId: 14, destinationCode: 'VIATOR', destinationName: 'Viator',
  publishState: 'DRAFT', readinessPct: 60,
  blockingReasons: ['Meeting point is required', 'Age policy is required'],
  externalProductId: null, lastPublishedAt: null, ...over,
});

describe('AdminListings', () => {
  beforeEach(() => {
    listings.mockReset();
    publish.mockReset();
    listings.mockResolvedValue({ data: { data: [] } });
  });

  it('renders and explains the empty state', async () => {
    render(<AdminListings />);
    await waitFor(() => expect(screen.getByText(/No listings yet/i)).toBeInTheDocument());
  });

  it('lists every blocking reason rather than a count', async () => {
    listings.mockResolvedValue({ data: { data: [row()] } });
    render(<AdminListings />);

    // "2 issues" sends the operator hunting; the reasons are what they act on.
    await waitFor(() => expect(screen.getByText('Meeting point is required')).toBeInTheDocument());
    expect(screen.getByText('Age policy is required')).toBeInTheDocument();
    expect(screen.getByText('60%')).toBeInTheDocument();
  });

  it('disables Publish below 100% and says why', async () => {
    listings.mockResolvedValue({ data: { data: [row()] } });
    render(<AdminListings />);

    const btn = await screen.findByRole('button', { name: /Publish/i });
    expect(btn).toBeDisabled();
    // A greyed button with no explanation is a dead end.
    expect(btn).toHaveAttribute('title', expect.stringMatching(/Resolve the blocking/i));
    expect(publish).not.toHaveBeenCalled();
  });

  it('enables Publish at 100% and calls the API', async () => {
    listings.mockResolvedValue({ data: { data: [row({ readinessPct: 100, blockingReasons: [] })] } });
    publish.mockResolvedValue({ data: { data: {} } });
    render(<AdminListings />);

    const btn = await screen.findByRole('button', { name: /Publish/i });
    expect(btn).toBeEnabled();
    await userEvent.click(btn);
    await waitFor(() => expect(publish).toHaveBeenCalledWith(1));
  });

  it('a LIVE listing offers no Publish button', async () => {
    listings.mockResolvedValue({ data: { data: [
      row({ publishState: 'LIVE', readinessPct: 100, blockingReasons: [],
            lastPublishedAt: '2026-08-01T10:00:00' }),
    ] } });
    render(<AdminListings />);

    await waitFor(() => expect(screen.getByText('LIVE')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /Publish/i })).not.toBeInTheDocument();
  });

  it('surfaces the server refusal rather than a generic error', async () => {
    listings.mockResolvedValue({ data: { data: [row({ readinessPct: 100, blockingReasons: [] })] } });
    publish.mockRejectedValue({ response: { data: {
      message: 'This destination is not enabled for the connection' } } });
    const { toast } = await import('react-toastify');
    render(<AdminListings />);

    await userEvent.click(await screen.findByRole('button', { name: /Publish/i }));

    // The server names the actual blocker — readiness, connection state, stop-sell, or
    // a destination the venue never enabled. Replacing that with "Could not publish"
    // would throw away the only actionable part.
    await waitFor(() => expect(toast.error)
      .toHaveBeenCalledWith('This destination is not enabled for the connection'));
  });

  it('survives an API failure without taking the page down', async () => {
    listings.mockRejectedValue({ response: { data: { message: 'boom' } } });
    render(<AdminListings />);
    await waitFor(() => expect(listings).toHaveBeenCalled());
    expect(screen.getByText(/Listing readiness/i)).toBeInTheDocument();
  });
});
