import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

const { mockSelectBinge, mockGetAllActiveBinges, mockNavigate } = vi.hoisted(() => ({
  mockSelectBinge: vi.fn(),
  mockGetAllActiveBinges: vi.fn(),
  mockNavigate: vi.fn(),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: null,
    selectBinge: mockSelectBinge,
    clearBinge: vi.fn(),
  }),
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'John', role: 'USER' },
    isAuthenticated: true,
    isAdmin: false,
  }),
}));

vi.mock('../services/endpoints', () => ({
  bookingService: {
    getAllActiveBinges: mockGetAllActiveBinges,
  },
}));

import BingeSelector from '../pages/BingeSelector';

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

function renderBingeSelector() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/binges']}>
        <BingeSelector />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('BingeSelector Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    mockGetAllActiveBinges.mockReturnValue(new Promise(() => {})); // never resolves
    renderBingeSelector();
    expect(screen.getByText(/loading/i)).toBeInTheDocument();
  });

  it('shows empty state when no binges', async () => {
    mockGetAllActiveBinges.mockResolvedValue({ data: { data: [] } });
    renderBingeSelector();
    await waitFor(() => {
      expect(screen.getByText(/no venues available/i)).toBeInTheDocument();
    });
  });

  it('renders binge cards when data is available', async () => {
    mockGetAllActiveBinges.mockResolvedValue({
      data: {
        data: [
          { id: 1, name: 'Downtown Cinema', address: '123 Main St' },
          { id: 2, name: 'Uptown Lounge', address: '456 Oak Ave' },
        ],
      },
    });
    renderBingeSelector();
    await waitFor(() => {
      expect(screen.getByText('Downtown Cinema')).toBeInTheDocument();
      expect(screen.getByText('Uptown Lounge')).toBeInTheDocument();
    });
  });

  it('selects a binge and navigates to dashboard', async () => {
    mockGetAllActiveBinges.mockResolvedValue({
      data: { data: [{ id: 1, name: 'Main Branch', address: '100 Center Rd' }] },
    });
    const user = userEvent.setup();
    renderBingeSelector();

    await waitFor(() => {
      expect(screen.getByText('Main Branch')).toBeInTheDocument();
    });

    // Click the select button within the card
    await user.click(screen.getByRole('button', { name: /select/i }));

    expect(mockSelectBinge).toHaveBeenCalledWith({
      id: 1, name: 'Main Branch', address: '100 Center Rd',
    });
    expect(mockNavigate).toHaveBeenCalledWith('/dashboard');
  });

  it('shows error toast on load failure', async () => {
    const { toast } = await import('react-toastify');
    mockGetAllActiveBinges.mockRejectedValue(new Error('Network error'));
    renderBingeSelector();

    await waitFor(() => {
      expect(toast.error).toHaveBeenCalledWith('Failed to load venues');
    });
  });
});
