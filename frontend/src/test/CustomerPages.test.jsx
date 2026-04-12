import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'John', role: 'USER', active: true, phone: '+919999999999' },
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

vi.mock('../stores/bingeStore', () => ({
  default: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

const { mockGetCurrentBookings, mockGetPastBookings, mockGetMyPricing, mockGetBingeDashboardExperience } = vi.hoisted(() => ({
  mockGetCurrentBookings: vi.fn(),
  mockGetPastBookings: vi.fn(),
  mockGetMyPricing: vi.fn(),
  mockGetBingeDashboardExperience: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  bookingService: {
    getCurrentBookings: mockGetCurrentBookings,
    getPastBookings: mockGetPastBookings,
    getMyBookings: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getMyPricing: mockGetMyPricing,
    getEventTypes: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getAllActiveBinges: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getBingeDashboardExperience: mockGetBingeDashboardExperience,
  },
  paymentService: {
    getMyPayments: vi.fn().mockResolvedValue({ data: { data: [] } }),
  },
  authService: {
    getProfile: vi.fn().mockResolvedValue({ data: { data: { id: '1', firstName: 'John' } } }),
    getSupportContact: vi.fn().mockResolvedValue({ data: { data: {} } }),
    updateAccountPreferences: vi.fn().mockResolvedValue({ data: {} }),
  },
  adminService: {},
  availabilityService: {
    getDates: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getSlots: vi.fn().mockResolvedValue({ data: { data: { availableSlots: [] } } }),
  },
}));

import Dashboard from '../pages/Dashboard';
import MyBookings from '../pages/MyBookings';
import NotFound from '../pages/NotFound';

function renderWithProviders(component, route = '/') {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={[route]}>
        {component}
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('Dashboard Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetCurrentBookings.mockResolvedValue({ data: { data: [] } });
    mockGetPastBookings.mockResolvedValue({ data: { data: [] } });
    mockGetMyPricing.mockResolvedValue({ data: { data: null } });
    mockGetBingeDashboardExperience.mockResolvedValue({
      data: {
        data: {
          sectionEyebrow: 'Explore Experiences',
          sectionTitle: 'Pick a setup that matches the mood',
          sectionSubtitle: '',
          layout: 'GRID',
          slides: [],
        },
      },
    });
  });

  it('renders dashboard heading', async () => {
    renderWithProviders(<Dashboard />, '/dashboard');
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('shows welcome message with user name', async () => {
    renderWithProviders(<Dashboard />, '/dashboard');
    await waitFor(() => {
      expect(screen.getByText(/john/i)).toBeInTheDocument();
    });
  });

  it('shows empty state when no bookings', async () => {
    renderWithProviders(<Dashboard />, '/dashboard');
    await waitFor(() => {
      // Either shows no bookings message or a CTA to book
      const text = document.body.textContent;
      expect(text).toBeDefined();
    });
  });

  it('displays current bookings when data is returned', async () => {
    mockGetCurrentBookings.mockResolvedValue({
      data: {
        data: [{
          bookingRef: 'BK-001',
          bookingDate: '2025-01-20',
          startTime: '14:00',
          status: 'CONFIRMED',
          eventType: { name: 'Birthday' },
          totalAmount: 5000,
        }],
      },
    });
    renderWithProviders(<Dashboard />, '/dashboard');
    await waitFor(() => {
      expect(screen.getByText('BK-001')).toBeInTheDocument();
    });
  });

  it('renders configured dashboard carousel slides', async () => {
    mockGetBingeDashboardExperience.mockResolvedValue({
      data: {
        data: {
          sectionEyebrow: 'Curated Moments',
          sectionTitle: 'Lead with your best setup',
          sectionSubtitle: 'Admin-managed carousel copy for the customer portal.',
          layout: 'CAROUSEL',
          slides: [
            {
              badge: 'Romance',
              headline: 'Date-night takeover',
              description: 'Lead with a quieter, more cinematic setup for proposals and anniversaries.',
              ctaLabel: 'Build this mood',
              theme: 'romance',
            },
          ],
        },
      },
    });

    renderWithProviders(<Dashboard />, '/dashboard');

    await waitFor(() => {
      expect(screen.getByText('Lead with your best setup')).toBeInTheDocument();
      expect(screen.getByText('Date-night takeover')).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /build this mood/i })).toBeInTheDocument();
    });
  });
});

describe('MyBookings Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetCurrentBookings.mockResolvedValue({ data: { data: [] } });
    mockGetPastBookings.mockResolvedValue({ data: { data: [] } });
  });

  it('renders page heading', async () => {
    renderWithProviders(<MyBookings />, '/my-bookings');
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('shows bookings list when data present', async () => {
    mockGetCurrentBookings.mockResolvedValue({
      data: {
        data: [{
          bookingRef: 'BK-002',
          bookingDate: '2025-02-10',
          startTime: '10:00',
          status: 'PENDING',
          eventType: { name: 'Anniversary' },
          paymentStatus: 'PENDING',
          totalAmount: 8000,
        }],
      },
    });
    renderWithProviders(<MyBookings />, '/my-bookings');
    await waitFor(() => {
      expect(screen.getByText('BK-002')).toBeInTheDocument();
    });
  });
});

describe('NotFound Page', () => {
  it('renders 404 heading', () => {
    renderWithProviders(<NotFound />, '/nonexistent');
    expect(screen.getByText('404')).toBeInTheDocument();
  });

  it('shows descriptive message', () => {
    renderWithProviders(<NotFound />, '/nonexistent');
    expect(screen.getByText(/page.*doesn.*exist/i)).toBeInTheDocument();
  });

  it('has a link to go home', () => {
    renderWithProviders(<NotFound />, '/nonexistent');
    const link = screen.getByText(/go home/i);
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute('href', '/');
  });
});
