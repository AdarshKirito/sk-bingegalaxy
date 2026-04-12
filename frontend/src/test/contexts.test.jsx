import { describe, it, expect, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { AuthProvider, useAuth } from '../context/AuthContext';
import { BingeProvider, useBinge } from '../context/BingeContext';

// Mock stores
vi.mock('../stores/authStore', () => ({
  default: vi.fn(() => ({
    user: { id: 1, firstName: 'Test', role: 'USER' },
    isAuthenticated: true,
    isAdmin: false,
    login: vi.fn(),
    logout: vi.fn(),
  })),
}));

vi.mock('../stores/bingeStore', () => ({
  default: vi.fn(() => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
    selectBinge: vi.fn(),
    clearBinge: vi.fn(),
  })),
}));

describe('AuthContext', () => {
  it('provides auth store values through context', () => {
    const { result } = renderHook(() => useAuth(), {
      wrapper: ({ children }) => <AuthProvider>{children}</AuthProvider>,
    });
    expect(result.current.user).toEqual({ id: 1, firstName: 'Test', role: 'USER' });
    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.isAdmin).toBe(false);
  });

  it('throws when useAuth is used outside provider', () => {
    // renderHook without wrapper should throw
    expect(() => {
      renderHook(() => useAuth());
    }).toThrow('useAuth must be used within AuthProvider');
  });
});

describe('BingeContext', () => {
  it('provides binge store values through context', () => {
    const { result } = renderHook(() => useBinge(), {
      wrapper: ({ children }) => <BingeProvider>{children}</BingeProvider>,
    });
    expect(result.current.selectedBinge).toEqual({ id: 1, name: 'Main Branch' });
    expect(typeof result.current.selectBinge).toBe('function');
    expect(typeof result.current.clearBinge).toBe('function');
  });

  it('throws when useBinge is used outside provider', () => {
    expect(() => {
      renderHook(() => useBinge());
    }).toThrow('useBinge must be used within BingeProvider');
  });
});
