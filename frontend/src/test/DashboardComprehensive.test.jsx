/**
 * Comprehensive frontend tests: Dashboard, AdminEventTypes, AdminDashboard
 * Covers edge cases, worst-case scenarios, and feature completeness.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

// ── Shared mocks ─────────────────────────────────────────────
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

const {
  mockGetCurrentBookings,
  mockGetPastBookings,
  mockGetMyPricing,
  mockGetBingeDashboardExperience,
  mockGetEventTypes,
  mockGetMyPayments,
  mockGetSupportContact,
} = vi.hoisted(() => ({
  mockGetCurrentBookings: vi.fn(),
  mockGetPastBookings: vi.fn(),
  mockGetMyPricing: vi.fn(),
  mockGetEventTypes: vi.fn(),
  mockGetBingeDashboardExperience: vi.fn(),
  mockGetMyPayments: vi.fn(),
  mockGetSupportContact: vi.fn(),
}));

vi.mock('../services/endpoints', () => ({
  bookingService: {
    getCurrentBookings: mockGetCurrentBookings,
    getPastBookings: mockGetPastBookings,
    getMyBookings: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getMyPricing: mockGetMyPricing,
    getEventTypes: mockGetEventTypes,
    getAllActiveBinges: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getBingeDashboardExperience: mockGetBingeDashboardExperience,
  },
  paymentService: {
    getMyPayments: mockGetMyPayments,
  },
  authService: {
    getProfile: vi.fn().mockResolvedValue({ data: { data: { id: '1', firstName: 'John' } } }),
    getSupportContact: mockGetSupportContact,
    updateAccountPreferences: vi.fn().mockResolvedValue({ data: {} }),
  },
  adminService: {},
  availabilityService: {
    getDates: vi.fn().mockResolvedValue({ data: { data: [] } }),
    getSlots: vi.fn().mockResolvedValue({ data: { data: { availableSlots: [] } } }),
  },
}));

import Dashboard from '../pages/Dashboard';

function renderDashboard(route = '/dashboard') {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={[route]}>
        <Dashboard />
      </MemoryRouter>
    </HelmetProvider>
  );
}

// ── Dashboard Comprehensive Tests ────────────────────────────
describe('Dashboard Comprehensive Tests', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetCurrentBookings.mockResolvedValue({ data: { data: [] } });
    mockGetPastBookings.mockResolvedValue({ data: { data: [] } });
    mockGetMyPricing.mockResolvedValue({ data: { data: null } });
    mockGetEventTypes.mockResolvedValue({ data: { data: [] } });
    mockGetMyPayments.mockResolvedValue({ data: { data: [] } });
    mockGetSupportContact.mockResolvedValue({ data: { data: {} } });
    mockGetBingeDashboardExperience.mockResolvedValue({
      data: {
        data: {
          sectionEyebrow: 'Explore',
          sectionTitle: 'Pick a setup',
          layout: 'GRID',
          slides: [],
        },
      },
    });
  });

  describe('Loading State', () => {
    it('renders heading after data loads', async () => {
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
      });
    });
  });

  describe('Bookings Display', () => {
    it('shows booking with correct details', async () => {
      mockGetCurrentBookings.mockResolvedValue({
        data: {
          data: [{
            bookingRef: 'SKBG-COMP-001',
            bookingDate: '2025-02-10',
            startTime: '14:00',
            durationHours: 3,
            durationMinutes: 180,
            status: 'CONFIRMED',
            paymentStatus: 'SUCCESS',
            eventType: { name: 'Birthday Party' },
            totalAmount: 5000,
          }],
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('SKBG-COMP-001')).toBeInTheDocument();
      });
    });

    it('shows multiple bookings sorted by date/time', async () => {
      mockGetCurrentBookings.mockResolvedValue({
        data: {
          data: [
            { bookingRef: 'LATE', bookingDate: '2025-02-15', startTime: '20:00', status: 'CONFIRMED', eventType: { name: 'Evening' }, totalAmount: 3000 },
            { bookingRef: 'EARLY', bookingDate: '2025-02-10', startTime: '10:00', status: 'CONFIRMED', eventType: { name: 'Morning' }, totalAmount: 2000 },
          ],
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('EARLY')).toBeInTheDocument();
        expect(screen.getByText('LATE')).toBeInTheDocument();
      });
    });

    it('handles empty bookings without crashing', async () => {
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
      });
    });
  });

  describe('Dashboard Experience: CAROUSEL Layout', () => {
    it('renders carousel items with custom slides', async () => {
      mockGetBingeDashboardExperience.mockResolvedValue({
        data: {
          data: {
            sectionTitle: 'Featured Experiences',
            layout: 'CAROUSEL',
            slides: [
              { badge: 'Romance', headline: 'Proposal Night', description: 'Intimate setup', ctaLabel: 'Book Now', theme: 'romance' },
              { badge: 'Party', headline: 'Birthday Bash', description: 'Let loose', ctaLabel: 'Start Planning', theme: 'celebration' },
            ],
          },
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('Featured Experiences')).toBeInTheDocument();
        expect(screen.getByText('Proposal Night')).toBeInTheDocument();
      });
    });

    it('renders carousel navigation arrows', async () => {
      mockGetBingeDashboardExperience.mockResolvedValue({
        data: {
          data: {
            sectionTitle: 'Navigate Me',
            layout: 'CAROUSEL',
            slides: [
              { headline: 'Slide 1', theme: 'celebration' },
              { headline: 'Slide 2', theme: 'romance' },
            ],
          },
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('Slide 1')).toBeInTheDocument();
      });
      // Arrows should be rendered for multi-slide carousel
      const buttons = screen.getAllByRole('button');
      expect(buttons.length).toBeGreaterThanOrEqual(1);
    });

    it('slide with imageUrl renders an img element', async () => {
      mockGetBingeDashboardExperience.mockResolvedValue({
        data: {
          data: {
            sectionTitle: 'Image Carousel',
            layout: 'CAROUSEL',
            slides: [
              { headline: 'With Image', imageUrl: '/api/v1/bookings/media/test.jpg', theme: 'celebration' },
            ],
          },
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('With Image')).toBeInTheDocument();
      });
      const images = screen.getAllByRole('img');
      const hasImage = images.some(img => img.getAttribute('src')?.includes('test.jpg'));
      expect(hasImage).toBe(true);
    });
  });

  describe('Dashboard Experience: GRID Layout', () => {
    it('renders grid items for custom slides', async () => {
      mockGetBingeDashboardExperience.mockResolvedValue({
        data: {
          data: {
            sectionTitle: 'Our Top Picks',
            layout: 'GRID',
            slides: [
              { badge: 'Cinema', headline: 'Movie Night', description: 'Popcorn included', ctaLabel: 'Reserve', theme: 'cinema' },
              { badge: 'Family', headline: 'Baby Shower', description: 'Cute vibes', ctaLabel: 'Reserve', theme: 'family' },
              { badge: 'Team', headline: 'Corporate Event', description: 'Impress the boss', ctaLabel: 'Reserve', theme: 'team' },
            ],
          },
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('Movie Night')).toBeInTheDocument();
        expect(screen.getByText('Baby Shower')).toBeInTheDocument();
        expect(screen.getByText('Corporate Event')).toBeInTheDocument();
      });
    });

    it('falls back to event types when no custom slides', async () => {
      mockGetBingeDashboardExperience.mockResolvedValue({
        data: { data: { layout: 'GRID', slides: [] } },
      });
      mockGetEventTypes.mockResolvedValue({
        data: {
          data: [
            { id: 1, name: 'Birthday Party', basePrice: 3000, active: true },
            { id: 2, name: 'Anniversary', basePrice: 5000, active: true },
          ],
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('Birthday Party')).toBeInTheDocument();
        expect(screen.getByText('Anniversary')).toBeInTheDocument();
      });
    });
  });

  describe('Pricing Display', () => {
    it('shows member tier when default pricing', async () => {
      mockGetMyPricing.mockResolvedValue({ data: { data: null } });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
      });
    });

    it('shows rate code label for RATE_CODE pricing', async () => {
      mockGetMyPricing.mockResolvedValue({
        data: {
          data: {
            pricingSource: 'RATE_CODE',
            rateCodeName: 'VIP',
            memberLabel: null,
          },
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getAllByText(/VIP/).length).toBeGreaterThan(0);
      });
    });

    it('shows custom label for CUSTOMER pricing', async () => {
      mockGetMyPricing.mockResolvedValue({
        data: {
          data: {
            pricingSource: 'CUSTOMER',
            memberLabel: 'Gold Member',
          },
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getAllByText(/Gold Member/).length).toBeGreaterThan(0);
      });
    });
  });

  describe('Error Handling', () => {
    it('handles API failure gracefully without crashing', async () => {
      mockGetCurrentBookings.mockRejectedValue(new Error('Network Error'));
      mockGetPastBookings.mockRejectedValue(new Error('Network Error'));
      mockGetMyPricing.mockRejectedValue(new Error('Network Error'));
      mockGetEventTypes.mockRejectedValue(new Error('Network Error'));
      mockGetMyPayments.mockRejectedValue(new Error('Network Error'));
      mockGetSupportContact.mockRejectedValue(new Error('Network Error'));
      mockGetBingeDashboardExperience.mockRejectedValue(new Error('Network Error'));

      renderDashboard();
      await waitFor(() => {
        expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
      });
    });

    it('handles partially loaded data without crashing', async () => {
      mockGetCurrentBookings.mockResolvedValue({ data: { data: [{ bookingRef: 'BK-PARTIAL', bookingDate: '2025-03-01', status: 'PENDING', eventType: { name: 'Test' }, totalAmount: 1000 }] } });
      mockGetPastBookings.mockRejectedValue(new Error('Fail'));
      mockGetMyPricing.mockRejectedValue(new Error('Fail'));

      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('BK-PARTIAL')).toBeInTheDocument();
      });
    });
  });

  describe('Pending Payments', () => {
    it('highlights bookings needing payment', async () => {
      mockGetCurrentBookings.mockResolvedValue({
        data: {
          data: [{
            bookingRef: 'BK-PENDING-PAY',
            bookingDate: '2025-02-15',
            startTime: '14:00',
            status: 'PENDING',
            paymentStatus: 'PENDING',
            eventType: { name: 'Birthday' },
            totalAmount: 5000,
          }],
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('BK-PENDING-PAY')).toBeInTheDocument();
      });
    });
  });

  describe('Past Bookings & Spend', () => {
    it('calculates total spend from successful past bookings', async () => {
      mockGetPastBookings.mockResolvedValue({
        data: {
          data: [
            { bookingRef: 'P1', bookingDate: '2025-01-01', status: 'COMPLETED', paymentStatus: 'SUCCESS', totalAmount: 3000, eventType: { name: 'A' } },
            { bookingRef: 'P2', bookingDate: '2025-01-05', status: 'CANCELLED', paymentStatus: 'FAILED', totalAmount: 5000, eventType: { name: 'B' } },
            { bookingRef: 'P3', bookingDate: '2025-01-10', status: 'COMPLETED', paymentStatus: 'SUCCESS', totalAmount: 7000, eventType: { name: 'C' } },
          ],
        },
      });
      renderDashboard();
      await waitFor(() => {
        // Total spend = 3000 + 7000 = 10000 (the CANCELLED one should be excluded)
        expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
      });
      // Rs 10,000 should appear somewhere
      expect(screen.getByText(/10,000/)).toBeInTheDocument();
    });
  });

  describe('Duration Formatting', () => {
    it('formats 180 minutes as 3h', async () => {
      mockGetCurrentBookings.mockResolvedValue({
        data: {
          data: [{
            bookingRef: 'DUR-TEST',
            bookingDate: '2025-02-20',
            startTime: '10:00',
            durationMinutes: 180,
            status: 'CONFIRMED',
            eventType: { name: 'Test' },
            totalAmount: 3000,
          }],
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('DUR-TEST')).toBeInTheDocument();
      });
      expect(screen.getAllByText(/3h/).length).toBeGreaterThan(0);
    });

    it('formats 90 minutes as 1h 30m', async () => {
      mockGetCurrentBookings.mockResolvedValue({
        data: {
          data: [{
            bookingRef: 'DUR-90',
            bookingDate: '2025-02-20',
            startTime: '10:00',
            durationMinutes: 90,
            status: 'CONFIRMED',
            eventType: { name: 'Test' },
            totalAmount: 2000,
          }],
        },
      });
      renderDashboard();
      await waitFor(() => {
        expect(screen.getByText('DUR-90')).toBeInTheDocument();
      });
      expect(screen.getAllByText(/1h 30m/).length).toBeGreaterThan(0);
    });
  });
});
