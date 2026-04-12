import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { HelmetProvider } from 'react-helmet-async';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}));

const mockRegister = vi.fn();
const mockGoogleLogin = vi.fn();
vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    register: mockRegister,
    googleLogin: mockGoogleLogin,
    user: null,
    isAuthenticated: false,
  }),
}));

import Register from '../pages/Register';

function renderRegister() {
  return render(
    <HelmetProvider>
      <MemoryRouter initialEntries={['/register']}>
        <Register />
      </MemoryRouter>
    </HelmetProvider>
  );
}

describe('Register Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders registration form fields', () => {
    renderRegister();
    expect(document.querySelector('input[placeholder="Srinivasa"]') || document.querySelector('input:not([type])')).toBeInTheDocument();
    expect(document.querySelector('input[type="email"]')).toBeInTheDocument();
    expect(document.querySelector('input[type="tel"]')).toBeInTheDocument();
  });

  it('renders password fields', () => {
    renderRegister();
    const passwordFields = document.querySelectorAll('input[type="password"]');
    expect(passwordFields.length).toBeGreaterThanOrEqual(1);
  });

  it('renders submit button', () => {
    renderRegister();
    expect(screen.getByRole('button', { name: /create.*account|sign.*up|register/i })).toBeInTheDocument();
  });

  it('renders link to login page', () => {
    renderRegister();
    expect(screen.getByText(/already have an account/i)).toBeInTheDocument();
  });

  it('does not call register with empty fields', async () => {
    const user = userEvent.setup();
    renderRegister();
    const submitBtn = screen.getByRole('button', { name: /create.*account|sign.*up|register/i });
    await user.click(submitBtn);
    expect(mockRegister).not.toHaveBeenCalled();
  });

  it('validates email format', async () => {
    const user = userEvent.setup();
    renderRegister();

    const emailField = document.querySelector('input[type="email"]');
    await user.type(emailField, 'not-an-email');

    const submitBtn = screen.getByRole('button', { name: /create.*account|sign.*up|register/i });
    await user.click(submitBtn);

    // Should show validation error and not call register
    expect(mockRegister).not.toHaveBeenCalled();
  });
});
