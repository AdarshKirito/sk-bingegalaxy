import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdminChannelSimulator from '../pages/AdminChannelSimulator';

const listProducts = vi.fn();
const checkAvailability = vi.fn();
const reserve = vi.fn();
const confirm = vi.fn();
const status = vi.fn();
const cancel = vi.fn();

vi.mock('../services/octoSimulator', () => ({
  octoSimulator: {
    listProducts: (...a) => listProducts(...a),
    checkAvailability: (...a) => checkAvailability(...a),
    reserve: (...a) => reserve(...a),
    confirm: (...a) => confirm(...a),
    status: (...a) => status(...a),
    cancel: (...a) => cancel(...a),
  },
}));
vi.mock('react-toastify', () => ({ toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() } }));

const entry = (over = {}) => ({
  label: 'List products', method: 'GET', url: '/api/v1/distribution/octo/products',
  request: null, status: 200, response: [], ms: 12, at: '2026-08-12T10:00:00Z', ...over,
});

/**
 * The screen that makes "the channel works" checkable.
 *
 * <p>Every OCTO write is answered PENDING because the canonical booking is created by a
 * later sweep, so a 201 proves nothing about whether the venue received anything.
 * Confirming that previously took a terminal, a hand-built Bearer token and knowledge of
 * the payload shape.
 */
describe('AdminChannelSimulator', () => {
  beforeEach(() => {
    [listProducts, checkAvailability, reserve, confirm, status, cancel]
      .forEach((m) => m.mockReset());
    listProducts.mockResolvedValue(entry());
  });

  const typeKey = async (key = 'skbg_octo_test') => {
    await userEvent.type(screen.getByLabelText(/Reseller key/i), key);
  };

  it('refuses to call anything without a reseller key', async () => {
    const { toast } = await import('react-toastify');
    render(<AdminChannelSimulator />);

    await userEvent.click(screen.getByRole('button', { name: /Load products/i }));

    // There is no other way into this surface, and pretending otherwise would send a
    // request that 401s for a reason the operator would misread as a broken key.
    expect(listProducts).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/reseller key/i));
  });

  it('passes the typed key to every call, and nothing else', async () => {
    listProducts.mockResolvedValue(entry({
      response: [{ id: '14', internalName: 'event-type-14' }],
    }));
    render(<AdminChannelSimulator />);
    await typeKey();

    await userEvent.click(screen.getByRole('button', { name: /Load products/i }));

    // The key is the ONLY credential. It is never persisted and never travels with the
    // operator's admin session — a surface an admin session could open would prove
    // nothing about a reseller.
    await waitFor(() => expect(listProducts).toHaveBeenCalledWith('skbg_octo_test'));
  });

  it('records the raw request and response, which is the artefact under test', async () => {
    listProducts.mockResolvedValue(entry({
      status: 401,
      response: { error: 'INVALID_TOKEN', errorMessage: 'The presented token is not valid.' },
    }));
    render(<AdminChannelSimulator />);
    await typeKey('wrong-key');

    await userEvent.click(screen.getByRole('button', { name: /Load products/i }));

    // A 401 must be shown, not swallowed — testing a revoked key is the single most
    // important thing this screen proves, and the shared API client would have logged
    // the operator out instead of displaying it.
    await waitFor(() => expect(screen.getByText('401')).toBeInTheDocument());
    expect(screen.getByText(/The presented token is not valid/)).toBeInTheDocument();
  });

  it('reuses one uuid across the whole lifecycle', async () => {
    listProducts.mockResolvedValue(entry({ response: [{ id: '14', internalName: 'e' }] }));
    checkAvailability.mockResolvedValue(entry({
      label: 'Check availability',
      response: [{ id: '2026-09-01T18:00|120',
                   localDateTimeStart: '2026-09-01T18:00', localDateTimeEnd: '2026-09-01T20:00',
                   available: true }],
    }));
    reserve.mockResolvedValue(entry({ label: 'Reserve (hold)', status: 201,
      response: { uuid: 'SIM-1', status: 'PENDING' } }));
    confirm.mockResolvedValue(entry({ label: 'Confirm', response: { status: 'PENDING' } }));

    render(<AdminChannelSimulator />);
    await typeKey();
    await userEvent.click(screen.getByRole('button', { name: /Load products/i }));
    await waitFor(() => expect(listProducts).toHaveBeenCalled());
    await userEvent.click(screen.getByRole('button', { name: /Check availability/i }));
    await waitFor(() => expect(checkAvailability).toHaveBeenCalled());

    await userEvent.click(screen.getByRole('button', { name: /Reserve \(hold\)/i }));
    await waitFor(() => expect(reserve).toHaveBeenCalled());

    const [, body] = reserve.mock.calls[0];
    await userEvent.click(screen.getByRole('button', { name: /Confirm/i }));

    // The reseller owns the uuid and it is the idempotency key for the ENTIRE lifecycle.
    // A confirm carrying a different one would address a reservation that does not exist.
    await waitFor(() => expect(confirm).toHaveBeenCalledWith('skbg_octo_test', body.uuid));
  });

  it('surfaces the outcome the status endpoint reports', async () => {
    status.mockResolvedValue(entry({
      label: 'Get status',
      response: { uuid: 'SIM-1', status: 'CONFIRMED',
                  supplierReference: 'SKBG26X', errorMessage: null, pending: false },
    }));
    render(<AdminChannelSimulator />);
    await typeKey();
    await userEvent.type(screen.getByLabelText(/Reservation uuid/i), 'SIM-1');

    await userEvent.click(screen.getByRole('button', { name: /Check status/i }));

    // Without this endpoint a refused reservation was indistinguishable from a
    // successful one, and the traveller is told they are booked either way.
    await waitFor(() => expect(screen.getByText('CONFIRMED')).toBeInTheDocument());
    // Twice, deliberately: once in the summary line an operator reads at a glance, and
    // once inside the raw transcript they can hand to a partner as evidence.
    expect(screen.getAllByText(/SKBG26X/)).toHaveLength(2);
  });

  it('will not confirm or cancel before there is a reservation to address', async () => {
    const { toast } = await import('react-toastify');
    render(<AdminChannelSimulator />);
    await typeKey();

    await userEvent.click(screen.getByRole('button', { name: /Cancel/i }));

    expect(cancel).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/Reserve first/i));
  });
});
