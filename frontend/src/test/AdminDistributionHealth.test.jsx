import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AdminDistributionHealth from '../pages/AdminDistributionHealth';

const health = vi.fn();
vi.mock('../services/endpoints', () => ({
  adminService: { getDistributionHealth: (...a) => health(...a) },
}));
vi.mock('react-toastify', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const payload = (over = {}) => ({
  connectionsTotal: 2, connectionsActive: 1, connectionsDegraded: 0, connectionsPaused: 1,
  credentialsExpiringSoon: 0, credentialsMissing: 0,
  listingsLive: 3, listingsBlocked: 0,
  inboxFailed: 0, inboxSuperseded: 0,
  generatedAt: '2026-08-05T10:00:00', alerts: [], ...over,
});

const renderPage = () =>
  render(<MemoryRouter><AdminDistributionHealth /></MemoryRouter>);

describe('AdminDistributionHealth', () => {
  beforeEach(() => {
    health.mockReset();
    health.mockResolvedValue({ data: { data: payload() } });
  });

  it('says plainly when nothing needs attention', async () => {
    renderPage();
    // An empty state has to be trustworthy, or an operator learns to ignore the panel
    // and then misses the day it is not empty.
    await waitFor(() => expect(screen.getByText(/Nothing needs attention/i)).toBeInTheDocument());
  });

  it('renders alerts worst-first, in the order the server sent them', async () => {
    health.mockResolvedValue({ data: { data: payload({ alerts: [
      { severity: 'CRITICAL', message: 'no resolvable credential', action: 'Provision the secret' },
      { severity: 'WARNING', message: 'credentials expire soon', action: 'Rotate the secret' },
      { severity: 'INFO', message: 'connections paused', action: 'Resume when ready' },
    ] }) } });

    renderPage();

    await waitFor(() => expect(screen.getByText(/no resolvable credential/)).toBeInTheDocument());
    const badges = screen.getAllByText(/CRITICAL|WARNING|INFO/);
    // Missing means dead NOW; expiring means it will be. Re-sorting client-side would
    // risk burying the outage under the warning.
    expect(badges.map((b) => b.textContent)).toEqual(['CRITICAL', 'WARNING', 'INFO']);
  });

  it('every alert carries an action', async () => {
    health.mockResolvedValue({ data: { data: payload({ alerts: [
      { severity: 'CRITICAL', message: 'inbound messages failed', action: 'Open the reservation inbox and retry them' },
    ] }) } });

    renderPage();

    // An alert with no action is just anxiety.
    await waitFor(() =>
      expect(screen.getByText(/Open the reservation inbox and retry them/)).toBeInTheDocument());
  });

  it('shows the counters, including superseded which raises no alert', async () => {
    health.mockResolvedValue({ data: { data: payload({ inboxSuperseded: 12 }) } });
    renderPage();

    await waitFor(() => expect(screen.getByText('Superseded')).toBeInTheDocument());
    expect(screen.getByText('12')).toBeInTheDocument();
    // Counted but not alerted: each superseded row is the ordering rule working.
    expect(screen.getByText(/Nothing needs attention/i)).toBeInTheDocument();
  });

  it('links to the screens that resolve the problems', async () => {
    renderPage();
    // Queried by ROLE, not text: "Connections" is also a stat label, and a bare
    // getByText would fail for a reason unrelated to the behaviour under test.
    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'Connections' })).toHaveAttribute(
        'href', '/admin/distribution'));
    expect(screen.getByRole('link', { name: 'Reservation inbox' }))
      .toHaveAttribute('href', '/admin/inbox');
    expect(screen.getByRole('link', { name: 'Listings' }))
      .toHaveAttribute('href', '/admin/listings');
  });

  it('survives an API failure without taking the page down', async () => {
    health.mockRejectedValue({ response: { data: { message: 'boom' } } });
    renderPage();
    await waitFor(() => expect(health).toHaveBeenCalled());
    expect(screen.getByText(/Channel health/i)).toBeInTheDocument();
  });
});
