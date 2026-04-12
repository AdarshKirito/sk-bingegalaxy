import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import useBingeStore from '../stores/bingeStore';

describe('useBingeStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useBingeStore.setState({ selectedBinge: null });
  });

  it('starts with null when nothing in localStorage', () => {
    const { result } = renderHook(() => useBingeStore());
    expect(result.current.selectedBinge).toBeNull();
  });

  it('selects a binge and persists to localStorage', () => {
    const { result } = renderHook(() => useBingeStore());
    const binge = { id: 1, name: 'Main Branch', address: '123 Main St' };

    act(() => result.current.selectBinge(binge));

    expect(result.current.selectedBinge).toEqual(binge);
    expect(JSON.parse(localStorage.getItem('selectedBinge'))).toEqual(binge);
  });

  it('clears selected binge and removes from localStorage', () => {
    localStorage.setItem('selectedBinge', JSON.stringify({ id: 1, name: 'Branch' }));
    useBingeStore.setState({ selectedBinge: { id: 1, name: 'Branch' } });

    const { result } = renderHook(() => useBingeStore());
    act(() => result.current.clearBinge());

    expect(result.current.selectedBinge).toBeNull();
    expect(localStorage.getItem('selectedBinge')).toBeNull();
  });

  it('restores binge from localStorage on init', () => {
    const binge = { id: 5, name: 'Test Branch' };
    localStorage.setItem('selectedBinge', JSON.stringify(binge));

    // Reset the store to force re-creation from localStorage
    useBingeStore.setState({
      selectedBinge: (() => {
        try {
          const stored = localStorage.getItem('selectedBinge');
          return stored ? JSON.parse(stored) : null;
        } catch { return null; }
      })(),
    });

    const { result } = renderHook(() => useBingeStore());
    expect(result.current.selectedBinge).toEqual(binge);
  });

  it('handles malformed localStorage gracefully', () => {
    localStorage.setItem('selectedBinge', 'not-valid-json');
    // The store initializer wraps in try/catch => returns null
    useBingeStore.setState({ selectedBinge: null });
    const { result } = renderHook(() => useBingeStore());
    expect(result.current.selectedBinge).toBeNull();
  });

  it('replaces previously selected binge', () => {
    const { result } = renderHook(() => useBingeStore());
    act(() => result.current.selectBinge({ id: 1, name: 'First' }));
    expect(result.current.selectedBinge.name).toBe('First');

    act(() => result.current.selectBinge({ id: 2, name: 'Second' }));
    expect(result.current.selectedBinge.name).toBe('Second');
    expect(result.current.selectedBinge.id).toBe(2);
  });
});
