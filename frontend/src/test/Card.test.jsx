import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Card from '../components/ui/Card';

describe('Card', () => {
  it('renders children', () => {
    render(<Card>Card content</Card>);
    expect(screen.getByText('Card content')).toBeInTheDocument();
  });

  it('applies "card" base class', () => {
    const { container } = render(<Card>Test</Card>);
    expect(container.firstChild).toHaveClass('card');
  });

  it('applies "selected" class when selected prop is true', () => {
    const { container } = render(<Card selected>Selected</Card>);
    expect(container.firstChild).toHaveClass('card', 'selected');
  });

  it('does not apply "selected" class when selected is false', () => {
    const { container } = render(<Card selected={false}>Not Selected</Card>);
    expect(container.firstChild).not.toHaveClass('selected');
  });

  it('applies custom className', () => {
    const { container } = render(<Card className="custom-class">Custom</Card>);
    expect(container.firstChild).toHaveClass('card', 'custom-class');
  });

  it('fires onClick handler', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(<Card onClick={onClick}>Clickable</Card>);
    await user.click(screen.getByText('Clickable'));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('applies inline style', () => {
    const { container } = render(<Card style={{ background: 'red' }}>Styled</Card>);
    expect(container.firstChild).toHaveStyle({ background: 'red' });
  });

  it('passes through additional props', () => {
    const { container } = render(<Card data-testid="my-card" role="button">Props</Card>);
    expect(screen.getByTestId('my-card')).toBeInTheDocument();
    expect(container.firstChild).toHaveAttribute('role', 'button');
  });
});
