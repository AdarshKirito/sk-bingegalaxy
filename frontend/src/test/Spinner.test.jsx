import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import Spinner from '../components/ui/Spinner';

describe('Spinner', () => {
  it('renders with default text', () => {
    render(<Spinner />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('renders with custom text', () => {
    render(<Spinner text="Please wait..." />);
    expect(screen.getByText('Please wait...')).toBeInTheDocument();
  });

  it('renders no text when text is empty', () => {
    render(<Spinner text="" />);
    expect(screen.queryByText('Loading...')).toBeNull();
  });

  it('has role="status" for accessibility', () => {
    render(<Spinner />);
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('applies default size of 36px', () => {
    const { container } = render(<Spinner />);
    const spinnerEl = container.querySelector('div[style*="animation"]');
    expect(spinnerEl).toHaveStyle({ width: '36px', height: '36px' });
  });

  it('applies custom size', () => {
    const { container } = render(<Spinner size={48} />);
    const spinnerEl = container.querySelector('div[style*="animation"]');
    expect(spinnerEl).toHaveStyle({ width: '48px', height: '48px' });
  });
});
