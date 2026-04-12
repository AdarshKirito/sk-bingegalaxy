import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SkeletonLine, SkeletonCard, SkeletonStatCard, SkeletonGrid } from '../components/ui/Skeleton';

describe('SkeletonLine', () => {
  it('renders with default width and height', () => {
    const { container } = render(<SkeletonLine />);
    const line = container.firstChild;
    expect(line).toHaveClass('skeleton-line');
    expect(line).toHaveStyle({ width: '100%', height: '1rem' });
  });

  it('renders with custom width and height', () => {
    const { container } = render(<SkeletonLine width="50%" height="2rem" />);
    const line = container.firstChild;
    expect(line).toHaveStyle({ width: '50%', height: '2rem' });
  });
});

describe('SkeletonCard', () => {
  it('renders with default 3 lines', () => {
    const { container } = render(<SkeletonCard />);
    const lines = container.querySelectorAll('.skeleton-line');
    expect(lines).toHaveLength(3);
  });

  it('renders with custom line count', () => {
    const { container } = render(<SkeletonCard lines={5} />);
    const lines = container.querySelectorAll('.skeleton-line');
    expect(lines).toHaveLength(5);
  });

  it('renders image placeholder when hasImage is true', () => {
    const { container } = render(<SkeletonCard hasImage />);
    expect(container.querySelector('.skeleton-image')).toBeInTheDocument();
  });

  it('does not render image placeholder by default', () => {
    const { container } = render(<SkeletonCard />);
    expect(container.querySelector('.skeleton-image')).toBeNull();
  });
});

describe('SkeletonStatCard', () => {
  it('renders a circle and lines', () => {
    const { container } = render(<SkeletonStatCard />);
    expect(container.querySelector('.skeleton-circle')).toBeInTheDocument();
    expect(container.querySelectorAll('.skeleton-line').length).toBeGreaterThanOrEqual(2);
  });
});

describe('SkeletonGrid', () => {
  it('renders default 4 skeleton cards', () => {
    const { container } = render(<SkeletonGrid />);
    const cards = container.querySelectorAll('.skeleton-card');
    expect(cards).toHaveLength(4);
  });

  it('renders custom count of cards', () => {
    const { container } = render(<SkeletonGrid count={6} />);
    const cards = container.querySelectorAll('.skeleton-card');
    expect(cards).toHaveLength(6);
  });

  it('applies correct grid class', () => {
    const { container } = render(<SkeletonGrid columns={3} />);
    expect(container.firstChild).toHaveClass('grid-3');
  });
});
