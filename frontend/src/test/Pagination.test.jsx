import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Pagination from '../components/ui/Pagination';

describe('Pagination', () => {
  it('renders nothing when totalPages is 1', () => {
    const { container } = render(<Pagination page={1} totalPages={1} onPageChange={vi.fn()} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders nothing when totalPages is 0', () => {
    const { container } = render(<Pagination page={1} totalPages={0} onPageChange={vi.fn()} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders page buttons for small page counts', () => {
    render(<Pagination page={1} totalPages={3} onPageChange={vi.fn()} />);
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('marks current page as active', () => {
    render(<Pagination page={2} totalPages={3} onPageChange={vi.fn()} />);
    const page2 = screen.getByText('2');
    expect(page2).toHaveClass('active');
    expect(page2).toHaveAttribute('aria-current', 'page');
  });

  it('disables previous button on first page', () => {
    render(<Pagination page={1} totalPages={5} onPageChange={vi.fn()} />);
    expect(screen.getByLabelText('Previous page')).toBeDisabled();
  });

  it('disables next button on last page', () => {
    render(<Pagination page={5} totalPages={5} onPageChange={vi.fn()} />);
    expect(screen.getByLabelText('Next page')).toBeDisabled();
  });

  it('calls onPageChange with correct page on click', async () => {
    const onPageChange = vi.fn();
    const user = userEvent.setup();
    render(<Pagination page={2} totalPages={5} onPageChange={onPageChange} />);
    await user.click(screen.getByText('3'));
    expect(onPageChange).toHaveBeenCalledWith(3);
  });

  it('calls onPageChange with previous page on prev click', async () => {
    const onPageChange = vi.fn();
    const user = userEvent.setup();
    render(<Pagination page={3} totalPages={5} onPageChange={onPageChange} />);
    await user.click(screen.getByLabelText('Previous page'));
    expect(onPageChange).toHaveBeenCalledWith(2);
  });

  it('calls onPageChange with next page on next click', async () => {
    const onPageChange = vi.fn();
    const user = userEvent.setup();
    render(<Pagination page={3} totalPages={5} onPageChange={onPageChange} />);
    await user.click(screen.getByLabelText('Next page'));
    expect(onPageChange).toHaveBeenCalledWith(4);
  });

  it('shows ellipsis for large page counts', () => {
    render(<Pagination page={5} totalPages={20} onPageChange={vi.fn()} />);
    const dots = screen.getAllByText('…');
    expect(dots.length).toBeGreaterThanOrEqual(1);
  });

  it('shows first page link when not in visible range', () => {
    render(<Pagination page={10} totalPages={20} onPageChange={vi.fn()} />);
    expect(screen.getByText('1')).toBeInTheDocument();
  });

  it('shows last page link when not in visible range', () => {
    render(<Pagination page={5} totalPages={20} onPageChange={vi.fn()} />);
    expect(screen.getByText('20')).toBeInTheDocument();
  });
});
