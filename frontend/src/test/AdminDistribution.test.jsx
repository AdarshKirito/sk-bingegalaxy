import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AdminDistribution from '../pages/AdminDistribution';

const mockProviders = vi.fn();
const mockConnections = vi.fn();
vi.mock('../services/endpoints', () => ({
  adminService: {
    getDistributionProviders: () => mockProviders(),
    getDistributionConnections: () => mockConnections(),
    createDistributionConnection: vi.fn(),
    pauseDistributionConnection: vi.fn(),
    resumeDistributionConnection: vi.fn(),
    revokeDistributionConnection: vi.fn(),
  },
}));
vi.mock('react-toastify', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

const provider = (over = {}) => ({
  code: 'SIMULATOR', displayName: 'OCTO Provider Simulator',
  authMethod: 'PLATFORM_MANAGED', certificationState: 'NONE',
  requiresCredential: false, credentialSubmissionSupported: false,
  capabilities: [], ...over,
});

const connection = (over = {}) => ({
  id: 1, providerName: 'OCTO Provider Simulator', status: 'PENDING',
  environment: 'SANDBOX', credentialHint: null, credentialConfigured: false,
  capabilities: [], destinations: [], ...over,
});

describe('AdminDistribution', () => {
  beforeEach(() => {
    mockProviders.mockReset().mockResolvedValue({ data: { data: [] } });
    mockConnections.mockReset().mockResolvedValue({ data: { data: [] } });
  });

  it('explains the empty state instead of showing a bare "no data"', async () => {
    render(<AdminDistribution />);
    await waitFor(() => expect(screen.getByText('No connections yet')).toBeInTheDocument());
    // Names the actual reason the dropdown may be empty.
    expect(screen.getByText(/super-admin has activated/i)).toBeInTheDocument();
  });

  it('does NOT ask for a credential when the provider does not need one', async () => {
    mockProviders.mockResolvedValue({ data: { data: [provider()] } });
    render(<AdminDistribution />);

    await waitFor(() => expect(screen.getByLabelText('Provider')).toBeInTheDocument());
    // The field is absent, not merely disabled — a platform-managed provider takes no
    // credential and the server refuses one outright.
    expect(screen.queryByLabelText('Credential reference')).not.toBeInTheDocument();
  });

  it('tells the operator to provision out of band when writes are unsupported', async () => {
    mockProviders.mockResolvedValue({ data: { data: [provider({
      code: 'VIATOR', displayName: 'Viator', authMethod: 'API_KEY',
      requiresCredential: true, credentialSubmissionSupported: false,
      certificationState: 'PILOT_REQUIRED',
    })] } });
    const { container } = render(<AdminDistribution />);

    await waitFor(() => expect(screen.getByLabelText('Provider')).toBeInTheDocument());
    const select = container.querySelector('#provider');
    select.value = 'VIATOR';
    select.dispatchEvent(new Event('change', { bubbles: true }));

    await waitFor(() =>
      expect(screen.getByText(/does not accept secrets through the browser/i)).toBeInTheDocument());
    // Certification is surfaced up front, not discovered after creating the connection.
    expect(screen.getByText(/PILOT_REQUIRED/)).toBeInTheDocument();
  });

  it('offers no actions on a REVOKED connection', async () => {
    mockConnections.mockResolvedValue({ data: { data: [connection({ status: 'REVOKED' })] } });
    render(<AdminDistribution />);

    await waitFor(() => expect(screen.getByText('REVOKED')).toBeInTheDocument());
    // Revocation is terminal; buttons that would only ever 400 are not rendered.
    expect(screen.queryByText('Pause')).not.toBeInTheDocument();
    expect(screen.queryByText('Resume')).not.toBeInTheDocument();
    expect(screen.queryByText('Revoke')).not.toBeInTheDocument();
  });

  it('offers Resume — not Pause — on a paused connection', async () => {
    mockConnections.mockResolvedValue({ data: { data: [connection({ status: 'PAUSED' })] } });
    render(<AdminDistribution />);
    await waitFor(() => expect(screen.getByText('Resume')).toBeInTheDocument());
    expect(screen.queryByText('Pause')).not.toBeInTheDocument();
  });

  it('warns when a stored hint no longer resolves to a secret', async () => {
    mockConnections.mockResolvedValue({ data: { data: [connection({
      status: 'ACTIVE', credentialHint: '••••4821', credentialConfigured: false,
    })] } });
    render(<AdminDistribution />);
    // The hint outlives a rotation that removed the secret; showing only the hint would
    // present a connection as configured when it can no longer authenticate.
    await waitFor(() =>
      expect(screen.getByText(/secret not resolvable/i)).toBeInTheDocument());
  });

  it('says a feed-only destination will never deliver reservations', async () => {
    mockConnections.mockResolvedValue({ data: { data: [connection({
      destinations: [{ id: 9, destinationName: 'Google Things to Do', enabled: true,
        paymentResponsibility: 'CHANNEL_COLLECTS', deliversReservations: false }],
    })] } });
    render(<AdminDistribution />);
    // Stops an operator waiting for bookings that will never arrive.
    await waitFor(() =>
      expect(screen.getByText(/Feed only — no reservations/)).toBeInTheDocument());
  });

  it('survives an API failure without blanking the page', async () => {
    mockProviders.mockRejectedValue({ response: { data: { message: 'boom' } } });
    render(<AdminDistribution />);
    await waitFor(() => expect(screen.getByText('Channel connections')).toBeInTheDocument());
  });
});
