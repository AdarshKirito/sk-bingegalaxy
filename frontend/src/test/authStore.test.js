import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useAuthStore from '../stores/authStore';

// Mock the authService
vi.mock('../services/endpoints', () => ({
  authService: {
    login: vi.fn(),
    adminLogin: vi.fn(),
    register: vi.fn(),
    googleLogin: vi.fn(),
    adminRegister: vi.fn(),
  },
}));

describe('useAuthStore', () => {
  beforeEach(() => {
    localStorage.clear();
    // Reset the store state
    useAuthStore.setState({
      user: null,
      loading: false,
    });
  });

  it('starts with null user when nothing in localStorage', () => {
    const { result } = renderHook(() => useAuthStore());
    expect(result.current.user).toBeNull();
  });

  it('logout clears user-owned localStorage state', () => {
    localStorage.setItem('user', JSON.stringify({ id: 1, role: 'USER' }));
    localStorage.setItem('selectedBinge', JSON.stringify({ id: 7, name: 'Main Branch' }));
    useAuthStore.setState({ user: { id: 1, role: 'USER' } });

    const { result } = renderHook(() => useAuthStore());
    act(() => result.current.logout());

    expect(result.current.user).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
    expect(localStorage.getItem('selectedBinge')).toBeNull();
  });

  it('setUser updates user in state and localStorage', () => {
    const { result } = renderHook(() => useAuthStore());
    const userData = { id: 2, firstName: 'John', role: 'USER' };
    act(() => result.current.setUser(userData));

    expect(result.current.user).toEqual(userData);
    expect(JSON.parse(localStorage.getItem('user'))).toEqual(userData);
  });
});
