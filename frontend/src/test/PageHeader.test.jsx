import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import PageHeader from '../components/ui/PageHeader';

describe('PageHeader', () => {
  it('renders title', () => {
    render(<PageHeader title="Dashboard" />);
    expect(screen.getByText('Dashboard')).toBeInTheDocument();
    expect(screen.getByText('Dashboard').tagName).toBe('H1');
  });

  it('renders subtitle when provided', () => {
    render(<PageHeader title="Reports" subtitle="View analytics" />);
    expect(screen.getByText('View analytics')).toBeInTheDocument();
  });

  it('does not render subtitle when not provided', () => {
    const { container } = render(<PageHeader title="Simple" />);
    expect(container.querySelectorAll('p')).toHaveLength(0);
  });

  it('renders children', () => {
    render(
      <PageHeader title="Test">
        <button>Action</button>
      </PageHeader>
    );
    expect(screen.getByText('Action')).toBeInTheDocument();
  });

  it('applies page-header class', () => {
    const { container } = render(<PageHeader title="Styled" />);
    expect(container.firstChild).toHaveClass('page-header');
  });
});
