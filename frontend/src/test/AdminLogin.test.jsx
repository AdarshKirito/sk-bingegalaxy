import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

const mockAdminLogin = vi.fn();
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    adminLogin: mockAdminLogin,
    user: null,
    isAuthenticated: false,
    isAdmin: false,
  }),
}));

import AdminLogin from '../pages/AdminLogin';

function renderAdminLogin() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/admin/login']}>
        <AdminLogin />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('AdminLogin Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders admin login form', () => {
    renderAdminLogin();
    expect(document.querySelector('input[type="email"]')).toBeInTheDocument();
    expect(document.querySelector('input[type="password"]')).toBeInTheDocument();
  });

  it('renders admin-specific branding', () => {
    renderAdminLogin();
    expect(screen.getByText('Admin Login')).toBeInTheDocument();
  });

  it('renders submit button', () => {
    renderAdminLogin();
    expect(screen.getByRole('button', { name: /sign in|log in|login/i })).toBeInTheDocument();
  });

  it('calls adminLogin on form submission', async () => {
    mockAdminLogin.mockResolvedValue({ id: 1, role: 'ADMIN' });
    const user = userEvent.setup();
    renderAdminLogin();

    await user.type(document.querySelector('input[type="email"]'), 'admin@test.com');
    await user.type(document.querySelector('input[type="password"]'), 'AdminPass123!');
    await user.click(screen.getByRole('button', { name: /sign in|log in|login/i }));

    await waitFor(() => {
      expect(mockAdminLogin).toHaveBeenCalledWith({
        email: 'admin@test.com',
        password: 'AdminPass123!',
      });
    });
  });

  it('shows error on admin login failure', async () => {
    mockAdminLogin.mockRejectedValue({ userMessage: 'Invalid admin credentials' });
    const user = userEvent.setup();
    renderAdminLogin();

    await user.type(document.querySelector('input[type="email"]'), 'admin@test.com');
    await user.type(document.querySelector('input[type="password"]'), 'wrong');
    await user.click(screen.getByRole('button', { name: /sign in|log in|login/i }));

    await waitFor(() => {
      expect(screen.getByText(/invalid|failed|error/i)).toBeInTheDocument();
    });
  });

  it('does not submit with empty credentials', async () => {
    const user = userEvent.setup();
    renderAdminLogin();
    await user.click(screen.getByRole('button', { name: /sign in|log in|login/i }));
    expect(mockAdminLogin).not.toHaveBeenCalled();
  });
});
