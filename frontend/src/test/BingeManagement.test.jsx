import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'Admin', role: 'SUPER_ADMIN', active: true },
    isAuthenticated: true,
    isAdmin: true,
    isSuperAdmin: true,
  }),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: { id: 1, name: 'Test Branch' },
  }),
}));

const {
  mockGetAdminBinges,
  mockCreateBinge,
  mockUpdateBinge,
  mockToggleBinge,
  mockDeleteBinge,
  mockGetAllAdmins,
  mockGetBingesByAdmin,
  mockGetBingeDashboardExperience,
  mockUpdateBingeDashboardExperience,
} = vi.hoisted(() => ({
  mockGetAdminBinges: vi.fn(),
  mockCreateBinge: vi.fn(),
  mockUpdateBinge: vi.fn(),
  mockToggleBinge: vi.fn(),
  mockDeleteBinge: vi.fn(),
  mockGetAllAdmins: vi.fn(),
  mockGetBingesByAdmin: vi.fn(),
  mockGetBingeDashboardExperience: vi.fn(),
  mockUpdateBingeDashboardExperience: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  adminService: {
    getAdminBinges: mockGetAdminBinges,
    createBinge: mockCreateBinge,
    updateBinge: mockUpdateBinge,
    toggleBinge: mockToggleBinge,
    deleteBinge: mockDeleteBinge,
    getBingesByAdmin: mockGetBingesByAdmin,
    getBingeDashboardExperience: mockGetBingeDashboardExperience,
    updateBingeDashboardExperience: mockUpdateBingeDashboardExperience,
  },
  authService: {
    getAllAdmins: mockGetAllAdmins,
  },
}));

import BingeManagement from '../pages/BingeManagement';

function renderBingeManagement() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/admin/binges']}>
        <BingeManagement />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('BingeManagement (Super Admin)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetAdminBinges.mockResolvedValue({
      data: {
        data: [
          { id: 1, name: 'Main Branch', address: '123 Main St', active: true },
          { id: 2, name: 'Outlet 2', address: '456 Oak Ave', active: false },
        ],
      },
    });
    mockGetAllAdmins.mockResolvedValue({ data: { data: [] } });
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
    mockUpdateBingeDashboardExperience.mockResolvedValue({ data: { data: {} } });
  });

  it('renders binge management heading', async () => {
    renderBingeManagement();
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('fetches admin binges on mount', async () => {
    renderBingeManagement();
    await waitFor(() => {
      expect(mockGetAdminBinges).toHaveBeenCalled();
    });
  });

  it('displays binge list', async () => {
    renderBingeManagement();
    await waitFor(() => {
      expect(screen.getByText('Main Branch')).toBeInTheDocument();
      expect(screen.getByText('Outlet 2')).toBeInTheDocument();
    });
  });

  it('loads and saves customer dashboard design', async () => {
    const user = userEvent.setup();
    renderBingeManagement();

    await waitFor(() => {
      expect(screen.getByText('Main Branch')).toBeInTheDocument();
    });

    await user.click(screen.getAllByRole('button', { name: /dashboard design/i })[0]);

    await waitFor(() => {
      expect(mockGetBingeDashboardExperience).toHaveBeenCalledWith(1);
      expect(screen.getByRole('heading', { name: /customer dashboard design/i })).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByDisplayValue('Grid cards'), 'CAROUSEL');
    await user.click(screen.getByRole('button', { name: /add slide/i }));
    await user.type(screen.getByPlaceholderText('Date-night takeover'), 'Date-night takeover');
    await user.type(screen.getByPlaceholderText('Describe the feeling or setup you want to surface in the customer portal'), 'Lead with a quieter, more cinematic setup for proposals and anniversaries.');
    await user.type(screen.getByPlaceholderText('Open Booking'), 'Build this mood');
    await user.click(screen.getByRole('button', { name: /save customer dashboard/i }));

    await waitFor(() => {
      expect(mockUpdateBingeDashboardExperience).toHaveBeenCalledWith(1, expect.objectContaining({
        layout: 'CAROUSEL',
        slides: [expect.objectContaining({
          headline: 'Date-night takeover',
          ctaLabel: 'Build this mood',
        })],
      }));
    });
  }, 15000);
});
