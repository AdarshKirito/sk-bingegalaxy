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
    user: { id: '1', firstName: 'Admin', role: 'ADMIN', active: true },
    isAuthenticated: true,
    isAdmin: true,
    isSuperAdmin: false,
  }),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

const { mockGetAllEventTypes, mockGetAllAddOns } = vi.hoisted(() => ({
  mockGetAllEventTypes: vi.fn(),
  mockGetAllAddOns: vi.fn(),
}));
vi.mock('../services/endpoints', () => ({
  adminService: {
    getAllEventTypes: mockGetAllEventTypes,
    createEventType: vi.fn().mockResolvedValue({ data: {} }),
    updateEventType: vi.fn().mockResolvedValue({ data: {} }),
    toggleEventType: vi.fn().mockResolvedValue({ data: {} }),
    deleteEventType: vi.fn().mockResolvedValue({ data: {} }),
    getAllAddOns: mockGetAllAddOns,
    createAddOn: vi.fn().mockResolvedValue({ data: {} }),
    updateAddOn: vi.fn().mockResolvedValue({ data: {} }),
    toggleAddOn: vi.fn().mockResolvedValue({ data: {} }),
    deleteAddOn: vi.fn().mockResolvedValue({ data: {} }),
  },
}));

import AdminEventTypes from '../pages/AdminEventTypes';

function renderAdminEventTypes() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/admin/event-types']}>
        <AdminEventTypes />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('AdminEventTypes Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetAllEventTypes.mockResolvedValue({
      data: {
        data: [
          { id: 1, name: 'Birthday Party', basePrice: 3000, hourlyRate: 500, active: true },
          { id: 2, name: 'Anniversary', basePrice: 5000, hourlyRate: 800, active: false },
        ],
      },
    });
    mockGetAllAddOns.mockResolvedValue({
      data: {
        data: [
          { id: 1, name: 'DJ Setup', price: 1500, active: true },
        ],
      },
    });
  });

  it('renders catalog heading', async () => {
    renderAdminEventTypes();
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 });
      expect(heading).toBeInTheDocument();
    });
  });

  it('fetches event types on mount', async () => {
    renderAdminEventTypes();
    await waitFor(() => {
      expect(mockGetAllEventTypes).toHaveBeenCalled();
    });
  });

  it('displays event types', async () => {
    renderAdminEventTypes();
    await waitFor(() => {
      expect(screen.getByText('Birthday Party')).toBeInTheDocument();
      expect(screen.getByText('Anniversary')).toBeInTheDocument();
    });
  });

  it('displays add-ons after switching tab', async () => {
    const user = userEvent.setup();
    renderAdminEventTypes();
    await waitFor(() => {
      expect(screen.getByText('Birthday Party')).toBeInTheDocument();
    });
    await user.click(screen.getByText('Add-Ons'));
    await waitFor(() => {
      expect(screen.getByText('DJ Setup')).toBeInTheDocument();
    });
  });
});
