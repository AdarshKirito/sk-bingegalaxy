import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Modal from '../components/ui/Modal';

describe('Modal', () => {
  it('renders nothing when open is false', () => {
    const { container } = render(<Modal open={false} title="Test"><p>Content</p></Modal>);
    expect(container.querySelector('dialog')).toBeNull();
  });

  it('renders dialog with title and content when open', () => {
    render(<Modal open={true} title="My Modal"><p>Modal body</p></Modal>);
    expect(screen.getByText('My Modal')).toBeInTheDocument();
    expect(screen.getByText('Modal body')).toBeInTheDocument();
  });

  it('renders close button with aria-label', () => {
    render(<Modal open={true} title="Close Test">Content</Modal>);
    expect(screen.getByLabelText('Close dialog')).toBeInTheDocument();
  });

  it('calls onClose when close button is clicked', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<Modal open={true} title="Close" onClose={onClose}>Content</Modal>);
    await user.click(screen.getByLabelText('Close dialog'));
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders without title when title is not provided', () => {
    render(<Modal open={true}><p>No title content</p></Modal>);
    expect(screen.getByText('No title content')).toBeInTheDocument();
    expect(screen.queryByLabelText('Close dialog')).toBeNull();
  });

  it('applies custom style', () => {
    render(<Modal open={true} title="Styled" style={{ width: '600px' }}>Styled content</Modal>);
    const dialog = screen.getByText('Styled').closest('dialog');
    expect(dialog).toHaveStyle({ width: '600px' });
  });
});
