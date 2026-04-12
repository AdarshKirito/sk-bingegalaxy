import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import LazyImage from '../components/ui/LazyImage';

describe('LazyImage', () => {
  it('renders fallback when src is not provided', () => {
    render(<LazyImage alt="No image test" />);
    expect(screen.getByText('No image test')).toBeInTheDocument();
  });

  it('renders custom fallback text when provided', () => {
    render(<LazyImage fallback="Custom fallback" />);
    expect(screen.getByText('Custom fallback')).toBeInTheDocument();
  });

  it('renders "No image" when no src, alt, or fallback', () => {
    render(<LazyImage />);
    expect(screen.getByText('No image')).toBeInTheDocument();
  });

  it('renders img element when src is provided', () => {
    render(<LazyImage src="https://example.com/image.jpg" alt="Test image" />);
    const img = screen.getByAltText('Test image');
    expect(img).toBeInTheDocument();
    expect(img.tagName).toBe('IMG');
    expect(img).toHaveAttribute('loading', 'lazy');
  });

  it('applies className to fallback container', () => {
    const { container } = render(<LazyImage className="test-class" alt="Alt" />);
    expect(container.firstChild).toHaveClass('test-class');
  });
});
