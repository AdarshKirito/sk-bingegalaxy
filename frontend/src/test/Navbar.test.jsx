import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}));

let authState;
let bingeState;
const mockNavigate = vi.fn();

vi.mock('../context/AuthContext', () => ({
  useAuth: () => authState,
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => bingeState,
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

import Navbar from '../components/Navbar';

function renderNavbar(route = '/') {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <Navbar />
    </MemoryRouter>
  );
}

describe('Navbar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authState = {
      user: null,
      isAuthenticated: false,
      isAdmin: false,
      isSuperAdmin: false,
      loading: false,
      logout: vi.fn(),
    };
    bingeState = {
      selectedBinge: null,
      clearBinge: vi.fn(),
    };
  });

  describe('Unauthenticated', () => {
    it('renders brand name', () => {
      renderNavbar();
      expect(screen.getByText('SK Binge Galaxy')).toBeInTheDocument();
    });

    it('shows Login and Sign Up links', () => {
      renderNavbar();
      expect(screen.getByText('Login')).toBeInTheDocument();
      expect(screen.getByText('Sign Up')).toBeInTheDocument();
    });

    it('shows "Private Screenings" label', () => {
      renderNavbar();
      expect(screen.getByText('Private Screenings')).toBeInTheDocument();
    });

    it('has hamburger menu button', () => {
      renderNavbar();
      expect(screen.getByLabelText(/open menu/i)).toBeInTheDocument();
    });
  });

  describe('Authenticated Customer', () => {
    beforeEach(() => {
      authState = {
        user: { id: '1', firstName: 'John', role: 'USER', active: true, phone: '+91999' },
        isAuthenticated: true,
        isAdmin: false,
        isSuperAdmin: false,
        loading: false,
        logout: vi.fn(),
      };
      bingeState = {
        selectedBinge: { id: 1, name: 'Main Branch' },
        clearBinge: vi.fn(),
      };
    });

    it('shows customer navigation links', () => {
      renderNavbar('/dashboard');
      expect(screen.getAllByText('Dashboard').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Book').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Bookings').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Payments').length).toBeGreaterThanOrEqual(1);
    });

    it('shows "Customer Hub" label', () => {
      renderNavbar('/dashboard');
      expect(screen.getByText('Customer Hub')).toBeInTheDocument();
    });

    it('shows user first name', () => {
      renderNavbar('/dashboard');
      const userNames = screen.getAllByText('John');
      expect(userNames.length).toBeGreaterThanOrEqual(1);
    });

    it('shows venue button with binge name', () => {
      renderNavbar('/dashboard');
      const venueNames = screen.getAllByText('Main Branch');
      expect(venueNames.length).toBeGreaterThanOrEqual(1);
    });

    it('calls logout on logout click', async () => {
      const user = userEvent.setup();
      renderNavbar('/dashboard');
      const logoutBtns = screen.getAllByLabelText('Logout');
      await user.click(logoutBtns[0]);
      expect(authState.logout).toHaveBeenCalled();
      expect(mockNavigate).toHaveBeenCalledWith('/');
    });

    it('changes binge on venue button click', async () => {
      const user = userEvent.setup();
      renderNavbar('/dashboard');
      const venueButtons = screen.getAllByTitle('Change venue');
      await user.click(venueButtons[0]);
      expect(bingeState.clearBinge).toHaveBeenCalled();
      expect(mockNavigate).toHaveBeenCalledWith('/binges');
    });
  });

  describe('Authenticated Customer without Binge', () => {
    beforeEach(() => {
      authState = {
        user: { id: '1', firstName: 'John', role: 'USER', active: true, phone: '+91999' },
        isAuthenticated: true,
        isAdmin: false,
        isSuperAdmin: false,
        loading: false,
        logout: vi.fn(),
      };
      bingeState = {
        selectedBinge: null,
        clearBinge: vi.fn(),
      };
    });

    it('shows Venues link', () => {
      renderNavbar('/binges');
      expect(screen.getAllByText(/venues/i).length).toBeGreaterThanOrEqual(1);
    });

    it('shows Account link', () => {
      renderNavbar('/binges');
      expect(screen.getAllByText('Account').length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('Admin with Binge', () => {
    beforeEach(() => {
      authState = {
        user: { id: '1', firstName: 'AdminUser', role: 'ADMIN', active: true },
        isAuthenticated: true,
        isAdmin: true,
        isSuperAdmin: false,
        loading: false,
        logout: vi.fn(),
      };
      bingeState = {
        selectedBinge: { id: 1, name: 'Admin Branch' },
        clearBinge: vi.fn(),
      };
    });

    it('shows "Admin Console" label', () => {
      renderNavbar('/admin/dashboard');
      expect(screen.getByText('Admin Console')).toBeInTheDocument();
    });

    it('shows admin navigation links', () => {
      renderNavbar('/admin/dashboard');
      expect(screen.getAllByText('Dashboard').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Bookings').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Create').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Availability').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Catalog').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Users').length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText('Reports').length).toBeGreaterThanOrEqual(1);
    });

    it('shows "Admin" role label', () => {
      renderNavbar('/admin/dashboard');
      const adminLabels = screen.getAllByText('Admin');
      expect(adminLabels.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('Super Admin with Binge', () => {
    beforeEach(() => {
      authState = {
        user: { id: '1', firstName: 'Boss', role: 'SUPER_ADMIN', active: true },
        isAuthenticated: true,
        isAdmin: true,
        isSuperAdmin: true,
        loading: false,
        logout: vi.fn(),
      };
      bingeState = {
        selectedBinge: { id: 1, name: 'HQ' },
        clearBinge: vi.fn(),
      };
    });

    it('shows "Super Admin" role label', () => {
      renderNavbar('/admin/dashboard');
      const saLabels = screen.getAllByText('Super Admin');
      expect(saLabels.length).toBeGreaterThanOrEqual(1);
    });

    it('shows "Add Admin" link', () => {
      renderNavbar('/admin/dashboard');
      const addAdminLinks = screen.getAllByText('Add Admin');
      expect(addAdminLinks.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('Hamburger menu', () => {
    it('toggles menu open/close', async () => {
      const user = userEvent.setup();
      renderNavbar();
      const hamburger = screen.getByLabelText(/open menu/i);
      await user.click(hamburger);
      expect(screen.getByLabelText(/close menu/i)).toBeInTheDocument();
      await user.click(screen.getByLabelText(/close menu/i));
      expect(screen.getByLabelText(/open menu/i)).toBeInTheDocument();
    });
  });
});
