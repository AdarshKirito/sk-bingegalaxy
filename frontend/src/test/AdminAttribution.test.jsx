import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AdminAttribution from '../pages/AdminAttribution';

const mockGet = vi.fn();
vi.mock('../services/endpoints', () => ({
  adminService: { getAttributionPerformance: (...a) => mockGet(...a) },
}));
vi.mock('react-toastify', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

/**
 * Verifying that the lazy chunk builds and is served proves the file compiled — it
 * proves nothing about whether the page renders. A component that throws on mount
 * ships perfectly and shows the user a blank screen.
 */
describe('AdminAttribution', () => {
  beforeEach(() => {
    mockGet.mockReset();
  });

  it('renders the table with data from the API', async () => {
    mockGet.mockResolvedValue({
      data: { data: [
        { source: 'google_things_to_do', bookings: 12, cancelled: 1, revenue: 45000, currency: 'INR' },
      ] },
    });

    render(<AdminAttribution />);

    await waitFor(() => expect(screen.getByText('Google Things To Do')).toBeInTheDocument());
    // The canonical value is shown alongside the prettified label, so an operator can
    // match what they see against what is actually stored.
    expect(screen.getByText('google_things_to_do')).toBeInTheDocument();

    // Scoped to the table row. A bare getByText('12') is ambiguous — the count also
    // appears in the summary line above — and an ambiguous assertion would fail for a
    // reason that has nothing to do with the behaviour under test.
    const row = screen.getByText('google_things_to_do').closest('tr');
    const cells = row.querySelectorAll('td');
    expect(cells[1]).toHaveTextContent('12');   // bookings
    expect(cells[2]).toHaveTextContent('1');    // cancelled, reported separately
  });

  it('explains WHY the empty state is empty', async () => {
    mockGet.mockResolvedValue({ data: { data: [] } });

    render(<AdminAttribution />);

    // "No data" alone is ambiguous between "no referrals arrived" and "no channel is
    // live yet" — and that ambiguity is the question this screen exists to answer.
    await waitFor(() =>
      expect(screen.getByText(/No referred bookings in this period/i)).toBeInTheDocument());
    expect(screen.getByText(/until a referral channel is live/i)).toBeInTheDocument();
  });

  it('flags a source with a high cancellation rate instead of hiding it', async () => {
    mockGet.mockResolvedValue({
      data: { data: [
        { source: 'flaky_channel', bookings: 2, cancelled: 8, revenue: 1000, currency: 'INR' },
      ] },
    });

    render(<AdminAttribution />);

    // 8 of 10 attempts cancelled. Folding those into the conversion count would make
    // the worst-performing source look like the best.
    await waitFor(() =>
      expect(screen.getByLabelText('High cancellation rate')).toBeInTheDocument());
  });

  it('renders an unrecognised source rather than dropping it', async () => {
    mockGet.mockResolvedValue({
      data: { data: [
        { source: 'channel_nobody_integrated', bookings: 1, cancelled: 0, revenue: 500, currency: 'INR' },
      ] },
    });

    render(<AdminAttribution />);

    // Matches the server rule: an unknown source is the first data about a new channel.
    await waitFor(() =>
      expect(screen.getByText('Channel Nobody Integrated')).toBeInTheDocument());
  });

  it('surfaces a server error without crashing the page', async () => {
    mockGet.mockRejectedValue({ response: { data: { message: 'nope' } } });

    render(<AdminAttribution />);

    await waitFor(() => expect(mockGet).toHaveBeenCalled());
    expect(screen.getByText('Channel attribution')).toBeInTheDocument();
  });
});
