import axios from 'axios';

/**
 * A reseller's client, not SK Binge's.
 *
 * <p><b>Why this cannot use the shared `api` instance.</b> The OCTO surface is
 * machine-to-machine: another company's system authenticating with a per-reseller Bearer
 * token that {@code ResellerAuthenticator} resolves to a connection. Driving it through
 * the admin client would be wrong in three separate ways, each of which matters:
 *
 * <ol>
 *   <li><b>It sends admin credentials.</b> `api` sets `withCredentials: true`, so every
 *       call would carry the operator's httpOnly session to an endpoint whose entire
 *       design is that a stray admin session must NOT satisfy it. Simulating a third
 *       party while holding the keys to the building does not simulate anything.</li>
 *   <li><b>It logs you out on 401.</b> `api`'s response interceptor treats 401 as an
 *       expired session, attempts a refresh and then force-logs-out. Testing a wrong or
 *       revoked reseller key — the single most important thing this screen proves — would
 *       therefore end the operator's session instead of showing them the 401.</li>
 *   <li><b>It rewrites errors.</b> The interceptor toasts and reshapes 403/429/5xx into
 *       user-facing copy. Here the raw provider-facing body IS the artefact under test.</li>
 * </ol>
 *
 * <p>So: no interceptors, no cookies, no CSRF token. The gateway prefix-exempts
 * {@code /api/v1/distribution/octo/} from CSRF and treats it as public precisely because
 * the Bearer token is the only thing that may open it.
 */
const octo = axios.create({
  baseURL: '/api/v1/distribution/octo',
  headers: { 'Content-Type': 'application/json' },
  // Deliberate. See above — this client must be indistinguishable from a reseller's.
  withCredentials: false,
  timeout: 15000,
  // Every status is a result to display, including 401 and 400. Throwing would send
  // the interesting cases through a catch block and lose the response body.
  validateStatus: () => true,
});

const auth = (key) => ({ Authorization: `Bearer ${(key || '').trim()}` });

/**
 * Every call returns the same envelope so the screen can log a transcript uniformly:
 * what was sent, what came back, and how long it took. That transcript is the point —
 * it is what makes "the channel works" something an operator can see rather than
 * something they are told.
 */
async function call(label, method, url, { key, body } = {}) {
  const startedAt = Date.now();
  const res = await octo.request({ method, url, headers: auth(key), data: body });
  return {
    label,
    method: method.toUpperCase(),
    url: `/api/v1/distribution/octo${url}`,
    request: body ?? null,
    status: res.status,
    response: res.data,
    ms: Date.now() - startedAt,
    at: new Date().toISOString(),
  };
}

export const octoSimulator = {
  listProducts: (key) => call('List products', 'get', '/products', { key }),

  checkAvailability: (key, productId, localDate) =>
    call('Check availability', 'post', '/availability', {
      key, body: { productId, localDate },
    }),

  /** OCTO's ON_HOLD step — the slot is held, not sold. */
  reserve: (key, body) => call('Reserve (hold)', 'post', '/bookings', { key, body }),

  /**
   * The reseller took payment. Sent with no body, which is what resellers actually do —
   * and which is why the PMS has to resolve the booking from the uuid rather than from
   * reservation detail the message does not carry.
   */
  confirm: (key, uuid) =>
    call('Confirm', 'post', `/bookings/${encodeURIComponent(uuid)}/confirm`, { key }),

  /**
   * The result contract. Every write here is accepted asynchronously and answered
   * PENDING, so without this a reseller could never learn whether the reservation
   * became a booking, was refused, or was superseded.
   */
  status: (key, uuid) =>
    call('Get status', 'get', `/bookings/${encodeURIComponent(uuid)}`, { key }),

  cancel: (key, uuid) =>
    call('Cancel', 'delete', `/bookings/${encodeURIComponent(uuid)}`, { key }),
};

export default octoSimulator;
