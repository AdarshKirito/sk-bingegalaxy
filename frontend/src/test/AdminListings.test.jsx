import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdminListings from '../pages/AdminListings';

const listings = vi.fn();
const publish = vi.fn();
const connections = vi.fn();
const eventTypes = vi.fn();
const requirements = vi.fn();
const evaluate = vi.fn();
vi.mock('../services/endpoints', () => ({
  adminService: {
    getDistributionListings: (...a) => listings(...a),
    publishDistributionListing: (...a) => publish(...a),
    getDistributionConnections: (...a) => connections(...a),
    getDistributionListingRequirements: (...a) => requirements(...a),
    evaluateDistributionListing: (...a) => evaluate(...a),
  },
  // The PUBLIC read, deliberately: /admin/event-types is gated on the EVENT_TYPES
  // module, which the DISTRIBUTION grant does not imply.
  bookingService: {
    getEventTypes: (...a) => eventTypes(...a),
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
    connections.mockReset().mockResolvedValue({ data: { data: [] } });
    eventTypes.mockReset().mockResolvedValue({ data: { data: [] } });
    requirements.mockReset().mockResolvedValue({ data: { data: [] } });
    evaluate.mockReset().mockResolvedValue({ data: { data: { readinessPct: 100 } } });
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

  // ── Creating the first mapping — the action the page described and never offered ──
  //
  // The empty state told an operator to "evaluate an event type against a destination".
  // The endpoint existed, the API wrapper existed, and nothing called it — so a venue's
  // first listing could not be created from the console at all.

  describe('evaluating an event type', () => {
    const pairing = { id: 3, destinationCode: 'SIMULATOR',
      destinationName: 'OCTO Simulator Marketplace', enabled: true };

    const withAPairing = () => {
      connections.mockResolvedValue({ data: { data: [
        { id: 5, providerName: 'OCTO Provider Simulator', destinations: [pairing] },
      ] } });
      eventTypes.mockResolvedValue({ data: { data: [{ id: 14, name: 'Whole-venue hire' }] } });
      requirements.mockResolvedValue({ data: { data: [
        { field: 'title', instruction: 'Add a listing title.' },
        { field: 'price', instruction: 'Set a price.' },
      ] } });
    };

    it('offers the action, not just the instruction to perform it', async () => {
      render(<AdminListings />);
      expect(await screen.findByRole('button', { name: /Evaluate an event type/i }))
        .toBeInTheDocument();
    });

    it('renders one field per requirement the SERVER declares for that destination', async () => {
      withAPairing();
      render(<AdminListings />);

      await userEvent.click(screen.getByRole('button', { name: /Evaluate an event type/i }));
      await screen.findByLabelText('Destination');
      await userEvent.selectOptions(screen.getByLabelText('Destination'), '3');

      // Asked for rather than hardcoded: the same policy decides whether the listing may
      // publish, so a local copy of the field list would drift and an operator would
      // fill in fields that do not count.
      await waitFor(() => expect(requirements).toHaveBeenCalledWith('SIMULATOR'));
      expect(await screen.findByLabelText('title')).toBeInTheDocument();
      expect(screen.getByLabelText('price')).toBeInTheDocument();
      // The instruction, not the field name — one is a schema error, the other is
      // something an operator can act on.
      expect(screen.getByText('Add a listing title.')).toBeInTheDocument();
    });

    it('submits the content against the chosen pairing', async () => {
      withAPairing();
      render(<AdminListings />);

      await userEvent.click(screen.getByRole('button', { name: /Evaluate an event type/i }));
      await screen.findByLabelText('Destination');
      await userEvent.selectOptions(screen.getByLabelText('Destination'), '3');
      await screen.findByLabelText('title');
      await userEvent.selectOptions(screen.getByLabelText('Event type'), '14');
      await userEvent.type(screen.getByLabelText('title'), 'Private hall');

      await userEvent.click(screen.getByRole('button', { name: /^Evaluate$/i }));

      await waitFor(() => expect(evaluate).toHaveBeenCalled());
      // A listing belongs to a connection↔destination PAIRING: the same destination
      // reached through two providers is two routes with their own commercial terms.
      expect(evaluate).toHaveBeenCalledWith(expect.objectContaining({
        connectionDestinationId: 3,
        eventTypeId: 14,
        content: expect.objectContaining({ title: 'Private hall' }),
      }));
    });

    it('says when there is no pairing to evaluate against', async () => {
      connections.mockResolvedValue({ data: { data: [
        { id: 5, providerName: 'OCTO Provider Simulator', destinations: [] },
      ] } });
      render(<AdminListings />);

      await userEvent.click(screen.getByRole('button', { name: /Evaluate an event type/i }));

      // Names the missing prerequisite instead of presenting an empty dropdown.
      await waitFor(() => expect(
        screen.getByText(/no connection pointed at\s+a destination yet/i)).toBeInTheDocument());
    });
  });
});
