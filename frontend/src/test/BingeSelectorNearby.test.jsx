import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

const { mockSelectBinge, mockGetAllActiveBinges, mockGetNearbyBinges, mockGetReviewSummary, mockNavigate } = vi.hoisted(() => ({
  mockSelectBinge: vi.fn(),
  mockGetAllActiveBinges: vi.fn(),
  mockGetNearbyBinges: vi.fn(),
  mockGetReviewSummary: vi.fn(),
  mockNavigate: vi.fn(),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({ selectedBinge: null, selectBinge: mockSelectBinge, clearBinge: vi.fn() }),
}));

vi.mock('../services/endpoints', () => ({
  bookingService: {
    getAllActiveBinges: mockGetAllActiveBinges,
    getNearbyBinges: mockGetNearbyBinges,
    getBingeReviewSummary: mockGetReviewSummary,
  },
}));

import BingeSelector from '../pages/BingeSelector';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

function renderSelector() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/binges']}>
        <BingeSelector />
      </MemoryRouter>
    </HelmetProvider>
  );
}

const GEOCODED_BINGES = [
  { id: 1, name: 'Indiranagar Theatre', address: 'Bengaluru', latitude: 12.97, longitude: 77.64 },
  { id: 2, name: 'Mysuru Palace Screen', address: 'Mysuru', latitude: 12.30, longitude: 76.64 },
];

describe('BingeSelector — proximity discovery', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetReviewSummary.mockResolvedValue({ data: { data: { averageRating: 4.5, totalReviews: 10 } } });
  });

  afterEach(() => {
    delete global.navigator.geolocation;
  });

  it('hides the "Use my location" CTA when no venue is geocoded', async () => {
    mockGetAllActiveBinges.mockResolvedValue({
      data: { data: [{ id: 9, name: 'No Coords Venue', address: 'Somewhere' }] },
    });
    renderSelector();
    await waitFor(() => expect(screen.getByText('No Coords Venue')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: /use my location/i })).not.toBeInTheDocument();
  });

  it('shows nearest venues with distance badges after granting location', async () => {
    mockGetAllActiveBinges.mockResolvedValue({ data: { data: GEOCODED_BINGES } });
    mockGetNearbyBinges.mockResolvedValue({
      data: {
        data: [
          { ...GEOCODED_BINGES[0], distanceKm: 1.2 },
          { ...GEOCODED_BINGES[1], distanceKm: 127 },
        ],
      },
    });
    // Stub the Geolocation API to immediately grant a position.
    global.navigator.geolocation = {
      getCurrentPosition: (success) =>
        success({ coords: { latitude: 12.97, longitude: 77.63, accuracy: 20 } }),
    };

    const user = userEvent.setup();
    renderSelector();

    await waitFor(() => expect(screen.getByText('Indiranagar Theatre')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /use my location/i }));

    await waitFor(() =>
      expect(mockGetNearbyBinges).toHaveBeenCalledWith(12.97, 77.63, { radiusKm: 50, limit: 50 })
    );
    // Distance badges rendered, nearest first.
    await waitFor(() => expect(screen.getByText('1.2 km away')).toBeInTheDocument());
    expect(screen.getByText('127 km away')).toBeInTheDocument();
    // Radius control + revert affordance now visible.
    expect(screen.getByRole('button', { name: /show all venues/i })).toBeInTheDocument();
  });

  it('falls back gracefully when the user denies location', async () => {
    const { toast } = await import('react-toastify');
    mockGetAllActiveBinges.mockResolvedValue({ data: { data: GEOCODED_BINGES } });
    global.navigator.geolocation = {
      getCurrentPosition: (_success, error) =>
        error({ code: 1, PERMISSION_DENIED: 1, POSITION_UNAVAILABLE: 2, TIMEOUT: 3 }),
    };

    const user = userEvent.setup();
    renderSelector();
    await waitFor(() => expect(screen.getByText('Indiranagar Theatre')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /use my location/i }));

    await waitFor(() => expect(toast.info).toHaveBeenCalled());
    // Did not switch to nearby mode; the all-venues list is still shown.
    expect(mockGetNearbyBinges).not.toHaveBeenCalled();
    expect(screen.queryByText(/km away/i)).not.toBeInTheDocument();
  });

  it('shows a radius message when nothing is within range', async () => {
    mockGetAllActiveBinges.mockResolvedValue({ data: { data: GEOCODED_BINGES } });
    mockGetNearbyBinges.mockResolvedValue({ data: { data: [] } }); // nothing within radius
    global.navigator.geolocation = {
      getCurrentPosition: (success) => success({ coords: { latitude: 1, longitude: 1, accuracy: 50 } }),
    };

    const user = userEvent.setup();
    renderSelector();
    await waitFor(() => expect(screen.getByText('Indiranagar Theatre')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /use my location/i }));

    await waitFor(() => expect(screen.getByText(/no venues within/i)).toBeInTheDocument());
    expect(screen.getByRole('button', { name: /widen radius/i })).toBeInTheDocument();
  });

  it('says "no matching venues" (not a radius message) when search empties the nearby list', async () => {
    mockGetAllActiveBinges.mockResolvedValue({ data: { data: GEOCODED_BINGES } });
    mockGetNearbyBinges.mockResolvedValue({
      data: { data: [{ ...GEOCODED_BINGES[0], distanceKm: 1.2 }] },
    });
    global.navigator.geolocation = {
      getCurrentPosition: (success) => success({ coords: { latitude: 12.97, longitude: 77.63, accuracy: 20 } }),
    };

    const user = userEvent.setup();
    renderSelector();
    await waitFor(() => expect(screen.getByText('Indiranagar Theatre')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /use my location/i }));
    await waitFor(() => expect(screen.getByText('1.2 km away')).toBeInTheDocument());

    // Search for something no venue matches — it's the search, not the radius, that empties the grid.
    await user.type(screen.getByPlaceholderText(/search venues/i), 'zzz-no-such-venue');

    await waitFor(() => expect(screen.getByText(/no matching venues/i)).toBeInTheDocument());
    expect(screen.queryByText(/no venues within/i)).not.toBeInTheDocument();
  });

  it('disables the radius control while a proximity request is in flight', async () => {
    // The in-flight disable is the primary guard against overlapping radius
    // requests (the loadNearby sequence token is defensive depth behind it).
    const deferred = (() => { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; })();
    mockGetAllActiveBinges.mockResolvedValue({ data: { data: GEOCODED_BINGES } });
    mockGetNearbyBinges.mockReturnValueOnce(deferred.promise);
    global.navigator.geolocation = {
      getCurrentPosition: (success) => success({ coords: { latitude: 12.97, longitude: 77.63, accuracy: 20 } }),
    };

    const user = userEvent.setup();
    renderSelector();
    await waitFor(() => expect(screen.getByText('Indiranagar Theatre')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: /use my location/i }));

    // Request still pending → the "Use my location" CTA is disabled, preventing
    // a second overlapping request.
    expect(screen.getByRole('button', { name: /locating/i })).toBeDisabled();

    deferred.resolve({ data: { data: [{ ...GEOCODED_BINGES[0], distanceKm: 1.2 }] } });
    await waitFor(() => expect(screen.getByText('1.2 km away')).toBeInTheDocument());
    // Now settled, the radius control is interactive again.
    expect(screen.getByLabelText(/search radius/i)).not.toBeDisabled();
  });
});
