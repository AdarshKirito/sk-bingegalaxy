import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ConfirmDialog from '../components/ui/ConfirmDialog';

describe('ConfirmDialog', () => {
  it('renders nothing when open is false', () => {
    const { container } = render(
      <ConfirmDialog open={false} title="Delete?" message="Are you sure?" onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    expect(container.querySelector('dialog')).toBeNull();
  });

  it('renders title and message when open', () => {
    render(
      <ConfirmDialog open={true} title="Delete Item?" message="This cannot be undone." onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    expect(screen.getByText('Delete Item?')).toBeInTheDocument();
    expect(screen.getByText('This cannot be undone.')).toBeInTheDocument();
  });

  it('renders default confirm and cancel labels', () => {
    render(
      <ConfirmDialog open={true} title="Test" message="msg" onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    expect(screen.getByText('Confirm')).toBeInTheDocument();
    expect(screen.getByText('Cancel')).toBeInTheDocument();
  });

  it('renders custom button labels', () => {
    render(
      <ConfirmDialog open={true} title="Test" message="msg" confirmLabel="Yes, delete" cancelLabel="No, keep" onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    expect(screen.getByText('Yes, delete')).toBeInTheDocument();
    expect(screen.getByText('No, keep')).toBeInTheDocument();
  });

  it('calls onConfirm when confirm button is clicked', async () => {
    const onConfirm = vi.fn();
    const user = userEvent.setup();
    render(
      <ConfirmDialog open={true} title="Test" message="msg" onConfirm={onConfirm} onCancel={vi.fn()} />
    );
    await user.click(screen.getByText('Confirm'));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('calls onCancel when cancel button is clicked', async () => {
    const onCancel = vi.fn();
    const user = userEvent.setup();
    render(
      <ConfirmDialog open={true} title="Test" message="msg" onConfirm={vi.fn()} onCancel={onCancel} />
    );
    await user.click(screen.getByText('Cancel'));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('applies danger variant classes by default', () => {
    render(
      <ConfirmDialog open={true} title="Test" message="msg" onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    const confirmBtn = screen.getByText('Confirm');
    expect(confirmBtn).toHaveClass('btn-danger');
  });

  it('applies primary variant when specified', () => {
    render(
      <ConfirmDialog open={true} title="Test" message="msg" variant="primary" onConfirm={vi.fn()} onCancel={vi.fn()} />
    );
    const confirmBtn = screen.getByText('Confirm');
    expect(confirmBtn).toHaveClass('btn-primary');
  });
});
