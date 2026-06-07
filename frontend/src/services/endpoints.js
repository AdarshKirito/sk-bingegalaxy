import api from './api';
import loyaltyV2 from './loyaltyV2';

/**
 * Safely coerce an API response value to an array.
 * Prevents "x.filter/map is not a function" crashes when the backend returns
 * an unexpected object `{}` instead of an array `[]`.
 * Use: toArray(res.data?.data) instead of (res.data.data || [])
 */
export const toArray = (val) => Array.isArray(val) ? val : [];

const asApiResponse = (data) => ({ data: { data } });

// Helper: returns the local date (YYYY-MM-DD) and time (HH:MM) from the browser
const clientDate = () => {
  const now = new Date();
  return now.toLocaleDateString('en-CA'); // YYYY-MM-DD in local timezone
};
const clientTime = () => {
  const now = new Date();
  return `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}:${String(now.getSeconds()).padStart(2,'0')}`;
};

export const authService = {
  // Registration writes a user, fires Kafka events, sends a welcome email and
  // touches multiple downstream consumers — first-call cold paths can occasionally
  // exceed the global 15s ceiling. Give it a longer timeout so a slow-but-successful
  // response doesn't surface as a (false-negative) "timeout" toast.
  register: (data) => api.post('/auth/register', data, { timeout: 45000 }),
  login: (data) => api.post('/auth/login', data, { timeout: 30000 }),
  googleLogin: (data) => api.post('/auth/google', data, { timeout: 30000 }),
  adminLogin: (data) => api.post('/auth/admin/login', data, { timeout: 30000 }),
  adminRegister: (data) => api.post('/auth/admin/register', data, { timeout: 45000 }),
  getProfile: () => api.get('/auth/profile'),
  updateAccountPreferences: (data) => api.put('/auth/profile/preferences', data),
  getSupportContact: () => api.get('/auth/support-contact'),
  completeProfile: (data) => api.put('/auth/complete-profile', data),
  changePassword: (data) => api.put('/auth/change-password', data),
  updateProfile: (data) => api.put('/auth/profile', data),
  changeEmail: (data) => api.put('/auth/change-email', data),
  forgotPassword: (data) => api.post('/auth/forgot-password', data),
  resetPassword: (data) => api.post('/auth/reset-password', data),
  verifyOtp: (data) => api.post('/auth/verify-otp', data),
  searchCustomers: (q) => api.get('/auth/admin/search-customers', { params: { q } }),
  getAllCustomers: () => api.get('/auth/admin/customers'),
  adminCreateCustomer: (data) => api.post('/auth/admin/create-customer', data),
  getCustomerById: (id) => api.get(`/auth/admin/customer/${id}`),
  adminUpdateCustomer: (id, data) => api.put(`/auth/admin/customer/${id}`, data),
  getAllAdmins: () => api.get('/auth/admin/admins'),
  updateAdmin: (id, data) => api.put(`/auth/admin/admins/${id}`, data),
  deleteUser: (id) => api.delete(`/auth/admin/user/${id}`),
  bulkBan: (userIds) => api.post('/auth/admin/bulk-ban', userIds),
  bulkUnban: (userIds) => api.post('/auth/admin/bulk-unban', userIds),
  bulkDelete: (userIds) => api.post('/auth/admin/bulk-delete', userIds),
  // ── MFA (TOTP) ─────────────────────────────────────────────
  enrollMfa: () => api.post('/auth/mfa/enroll'),
  confirmMfa: (data) => api.post('/auth/mfa/confirm', data),
  disableMfa: (data) => api.post('/auth/mfa/disable', data),
  // ── Email verification ─────────────────────────────────────
  verifyEmail: (data) => api.post('/auth/verify-email', data),
  resendVerification: () => api.post('/auth/resend-verification'),
  // ── Privacy / right-to-erasure (DPDP Act 2023 / GDPR) ──────
  // Soft-deletes the account immediately, revokes all sessions, and schedules
  // permanent PII anonymization after the retention window (30 days).
  requestAccountDeletion: () => api.delete('/auth/privacy/me'),
  // ── Sessions (self) ────────────────────────────────────────
  getMySessions: () => api.get('/auth/sessions'),
  revokeMySession: (id) => api.delete(`/auth/sessions/${id}`),
  revokeMyOtherSessions: () => api.post('/auth/sessions/revoke-others'),
  // ── Super-admin ────────────────────────────────────────────
  getAllActiveSessions: (params = {}) => api.get('/auth/admin/sessions', { params }),
  revokeAnySession: (id) => api.delete(`/auth/admin/sessions/${id}`),
  revokeAllSessionsForUser: (userId) => api.delete(`/auth/admin/users/${userId}/sessions`),
  getAuditLog: (params = {}) => api.get('/auth/admin/audit-log', { params }),
  promoteAdmin: (id) => api.post(`/auth/admin/admins/${id}/promote`),
  demoteAdmin: (id) => api.post(`/auth/admin/admins/${id}/demote`),
  getSuperAdminStats: () => api.get('/auth/admin/super-admin/stats'),
};

/**
 * Authority Handover (delegated super-admin powers + per-record locks).
 * Backed by the auth-service AuthorityController at /api/v1/auth/authority.
 *
 * - Native super-admins use this surface to grant temporary super-admin authority
 *   to an admin (scoped to one or more pages, capped to 24h, audit-logged).
 * - Anyone authenticated can call getMyAuthority() and lookupLock() so the UI
 *   can render the delegation banner and lock badges.
 * - Lock placement / release / list endpoints are super-admin only (or lock owner
 *   for release) and enforced server-side.
 */
export const authorityService = {
  // Self
  getMyAuthority: () => api.get('/auth/authority/me'),
  // Grants
  listGrants: (params = {}) => api.get('/auth/authority/grants', { params }),
  listGrantsForUser: (userId) => api.get(`/auth/authority/grants/by-user/${userId}`),
  createGrant: (data) => api.post('/auth/authority/grants', data),
  revokeGrant: (id, reason) => api.delete(`/auth/authority/grants/${id}`, { params: { reason } }),
  // Locks
  lookupLock: (type, id) => api.get('/auth/authority/locks/lookup', { params: { type, id } }),
  listLocks: (params = {}) => api.get('/auth/authority/locks', { params }),
  createLock: (data) => api.post('/auth/authority/locks', data),
  releaseLock: (id, reason) => api.delete(`/auth/authority/locks/${id}`, { params: { reason } }),
};

export const bookingService = {
  getEventTypes: () => api.get('/bookings/event-types'),
  getAddOns: () => api.get('/bookings/add-ons'),
  // V55 — category taxonomy (globals ∪ binge-scoped, active only)
  getEventCategories: () => api.get('/bookings/event-categories'),
  getAddOnCategories: () => api.get('/bookings/addon-categories'),
  createBooking: (data) => api.post('/bookings', data),
  getByRef: (ref) => api.get(`/bookings/${ref}`),
  getMyBookings: () => api.get('/bookings/my'),
  getCurrentBookings: () => api.get('/bookings/my/current', { params: { clientDate: clientDate() } }),
  getPastBookings: () => api.get('/bookings/my/past', { params: { clientDate: clientDate() } }),
  getPendingReviews: () => api.get('/bookings/my/reviews/pending', { params: { clientDate: clientDate() } }),
  getBookedSlots: (date) => api.get('/bookings/booked-slots', { params: { date } }),
  cancelBooking: (ref) => api.post(`/bookings/${ref}/cancel`),
  rescheduleBooking: (ref, data) => api.post(`/bookings/${ref}/reschedule`, data),
  transferBooking: (ref, data) => api.post(`/bookings/${ref}/transfer`, data),
  // Customer-safe lifecycle timeline (privacy-filtered, no internal IDs)
  getMyTimeline: (ref) => api.get(`/bookings/${ref}/timeline`),
  // Invoice PDF download — server returns application/pdf, so we ask axios
  // for a Blob and the caller drives the file-save / preview UX.
  downloadInvoice: (ref) => api.get(`/bookings/${ref}/invoice`, { responseType: 'blob' }),
  createRecurringBookings: (data) => api.post('/bookings/recurring', data),
  getRecurringGroup: (groupId) => api.get(`/bookings/recurring/${groupId}`),
  getCustomerReview: (ref) => api.get(`/bookings/${ref}/reviews/customer`),
  submitCustomerReview: (ref, data) => api.post(`/bookings/${ref}/reviews/customer`, data),
  getMyPricing: () => api.get('/bookings/my-pricing'),
  getSlotCapacity: (date, startMinute, durationMinutes) => api.get('/bookings/slot-capacity', { params: { date, startMinute, durationMinutes } }),
  // Waitlist
  joinWaitlist: (data) => api.post('/bookings/waitlist', data),
  leaveWaitlist: (entryId) => api.delete(`/bookings/waitlist/${entryId}`),
  getMyWaitlist: () => api.get('/bookings/waitlist/my'),
  // Binge (public)
  getAllActiveBinges: () => api.get('/bookings/binges'),
  getBingeById: (id) => api.get(`/bookings/binges/${id}`),
  getBingeDashboardExperience: (id) => api.get(`/bookings/binges/${id}/customer-dashboard`),
  getBingeAboutExperience: (id) => api.get(`/bookings/binges/${id}/customer-about`),
  getBingeReviewSummary: (id) => api.get(`/bookings/binges/${id}/reviews/summary`),
  getBingeReviews: (id, page = 0, size = 10) => api.get(`/bookings/binges/${id}/reviews`, { params: { page, size } }),
  getCancellationTiers: (bingeId) => api.get(`/bookings/binges/${bingeId}/cancellation-tiers`),
  // Venue rooms (public)
  getVenueRooms: () => api.get('/bookings/venue-rooms'),
  getAvailableRooms: (date, startMinute, durationMinutes) => api.get('/bookings/venue-rooms/available', { params: { date, startMinute, durationMinutes } }),
  // Loyalty (customer)
  getMyLoyalty: async () => asApiResponse(await loyaltyV2.getMyLegacyAccount()),
  // Surge rules (public)
  getActiveSurgeRules: () => api.get('/bookings/surge-rules'),
  // Customer freezes (booking-flow lock self-view)
  getMyFreezes: () => api.get('/bookings/freezes/me'),
  getMyFreezeForBinge: (bingeId) => api.get(`/bookings/freezes/me/binge/${bingeId}`),
  // Per-binge CMS — venue-specific FAQ / offers / support overrides on the
  // customer Account Center. Read is authenticated, write is admin-only.
  getBingeSiteContent: (bingeId, slug) => api.get(`/bookings/binges/${bingeId}/site-content/${slug}`),
  upsertBingeSiteContent: (bingeId, slug, contentJson) =>
    api.put(`/bookings/admin/binges/${bingeId}/site-content/${slug}`, { contentJson }),
  // Tax preview (public) — used by checkout to show breakdown before commit.
  previewTaxes: (params = {}) => api.get('/bookings/taxes/preview', { params }),
  // Active currencies (public) — used by CurrencyContext on app boot.
  getActiveCurrencies: () => api.get('/bookings/currencies'),
};

// ── Slot holds (customer + admin) ─────────────────────────
// Pre-booking holds (60s TTL) so customers don't lose a slot mid-checkout.
export const slotHoldService = {
  create: (data) => api.post('/bookings/slot-holds', data),
  getByToken: (token) => api.get(`/bookings/slot-holds/${token}`),
  release: (token, reason) => api.delete(`/bookings/slot-holds/${token}`, { params: reason ? { reason } : {} }),
  myHolds: () => api.get('/bookings/slot-holds/my'),
  // Admin: list active holds for current binge + force-release.
  adminList: () => api.get('/bookings/slot-holds/admin'),
  adminRelease: (token, reason) =>
    api.delete(`/bookings/slot-holds/admin/${token}`, { params: reason ? { reason } : {} }),
};

// ── Tax rules (admin) ─────────────────────────────────────
// Binge-scoped rules under /admin/taxes; super-admin global rules under /admin/taxes/global.
export const taxService = {
  list: () => api.get('/bookings/admin/taxes'),
  create: (data) => api.post('/bookings/admin/taxes', data),
  update: (id, data) => api.put(`/bookings/admin/taxes/${id}`, data),
  remove: (id) => api.delete(`/bookings/admin/taxes/${id}`),
  listGlobal: () => api.get('/bookings/admin/taxes/global'),
  createGlobal: (data) => api.post('/bookings/admin/taxes/global', data),
  // Public preview — same calc the checkout uses. Admins use it as a calculator
  // to verify their rule set produces the expected breakdown.
  preview: (params = {}) => api.get('/bookings/taxes/preview', { params }),
};

// ── Currencies / FX rates (super-admin write, public read) ─
export const currencyService = {
  // Public: only active currencies + their FX rates.
  listActive: () => api.get('/bookings/currencies'),
  // Admin: full list including inactive rows.
  listAll: () => api.get('/bookings/admin/currencies'),
  upsert: (data) => api.post('/bookings/admin/currencies', data),
  toggle: (code) => api.post(`/bookings/admin/currencies/${code}/toggle`),
  remove: (code) => api.delete(`/bookings/admin/currencies/${code}`),
};

// ── Checkout (multi-currency quote + FX rate lock) ─────────
// Used only when the customer chooses to pay in a foreign currency the super-admin
// has enabled (CurrencyRate.supportsPayment). lockFx fixes the INR→currency rate for a
// short window; the returned token is passed to createBooking, which records the locked
// currency + rate so the payment is charged at exactly that rate.
export const checkoutService = {
  preview: (data) => api.post('/bookings/checkout/preview', data),
  lockFx: (data) => api.post('/bookings/checkout/lock-fx', data),
};

// ── Notifications (customer + admin) ───────────────────────
// Customer-facing endpoints rely on the X-User-Email header injected by the
// gateway; admin/template endpoints require ROLE_ADMIN at the gateway.
export const notificationService = {
  // Customer
  myNotifications: () => api.get('/notifications/my'),
  byBooking: (ref) => api.get(`/notifications/booking/${ref}`),
  // Preferences (per recipient email)
  getPreferences: () => api.get('/notifications/preferences'),
  updatePreferences: (data) => api.put('/notifications/preferences', data),
  // Admin
  retryFailed: () => api.post('/notifications/admin/retry-failed'),
  // Email/SMS templates (versioned, channel-scoped)
  listTemplates: (params = {}) => api.get('/notifications/admin/templates', { params }),
  upsertTemplate: (data) => api.post('/notifications/admin/templates', data),
  activateTemplate: (params) => api.post('/notifications/admin/templates/activate', null, { params }),
  // WhatsApp content templates (Twilio Content SIDs)
  listWhatsAppTemplates: (params = { page: 0, size: 50 }) =>
    api.get('/notifications/admin/whatsapp-templates', { params }),
  getWhatsAppTemplate: (id) => api.get(`/notifications/admin/whatsapp-templates/${id}`),
  createWhatsAppTemplate: (data) => api.post('/notifications/admin/whatsapp-templates', data),
  updateWhatsAppTemplate: (id, data) => api.put(`/notifications/admin/whatsapp-templates/${id}`, data),
  deleteWhatsAppTemplate: (id) => api.delete(`/notifications/admin/whatsapp-templates/${id}`),
};

export const availabilityService = {
  getDates: (from, to) => api.get('/availability/dates', { params: { from, to, clientDate: clientDate() } }),
  getSlots: (date) => api.get('/availability/slots', { params: { date } }),
};

export const paymentService = {
  initiate: (data) => api.post('/payments/initiate', data),
  callback: (data) => api.post('/payments/callback', data),
  getByBooking: (ref) => api.get(`/payments/booking/${ref}`),
  getMyPayments: () => api.get('/payments/my'),
  cancelPayment: (txnId) => api.post(`/payments/cancel/${txnId}`),
  getByTransactionId: (txnId) => api.get(`/payments/transaction/${txnId}`),
};

export const siteContentService = {
  // Public read used by the landing page on every visitor load.
  getPublic: (slug) => api.get(`/site-content/public/${slug}`),
  // Super-admin write — guarded by SUPER_ADMIN role at the gateway + auth-service.
  upsert: (slug, contentJson) => api.put(`/site-content/admin/${slug}`, { contentJson }),
};

export const adminService = {
  getDashboardStats: () => api.get('/bookings/admin/dashboard-stats', { params: { clientDate: clientDate() } }),
  getAllBookings: (page, size) => api.get('/bookings/admin', { params: { page, size } }),
  getTodayBookings: (page, size) => api.get('/bookings/admin/today', { params: { page, size, clientDate: clientDate() } }),
  getUpcomingBookings: (page, size) => api.get('/bookings/admin/upcoming', { params: { page, size, clientDate: clientDate() } }),
  getBookingsByDate: (date, page, size) => api.get('/bookings/admin/by-date', { params: { date, page, size } }),
  getBookingsByStatus: (status, page, size) => api.get('/bookings/admin/by-status', { params: { status, page, size, clientDate: clientDate() } }),
  // House accounts = every booking still awaiting payment, across all dates
  // (the full accounts-receivable list, unlike the today-only "Pending Payment" sub-tab).
  getHouseAccounts: (page, size) => api.get('/bookings/admin/house-accounts', { params: { page, size } }),
  searchBookings: (keyword) => api.get('/bookings/admin/search', { params: { q: keyword, clientDate: clientDate() } }),
  updateBooking: (ref, data) => api.patch(`/bookings/admin/${ref}`, data),
  cancelBooking: (ref, reason) => api.post(`/bookings/admin/${ref}/cancel`, { reason: reason || '' }),
  confirmBooking: (ref) => api.post(`/bookings/admin/${ref}/confirm`),
  checkIn: (ref) => api.post(`/bookings/admin/${ref}/check-in`, null, { params: { clientDate: clientDate() } }),
  checkout: (ref) => api.post(`/bookings/admin/${ref}/checkout`, null, { params: { clientDate: clientDate(), clientTime: clientTime() } }),
  undoCheckIn: (ref, reason) => api.post(`/bookings/admin/${ref}/undo-check-in`, { reason: reason || '' }, { params: { clientDate: clientDate() } }),
  // ── QR / OTP check-in ──
  issueCheckInQr: (ref) => api.post(`/bookings/admin/${ref}/check-in/qr/issue`),
  issueCheckInOtp: (ref) => api.post(`/bookings/admin/${ref}/check-in/otp/issue`),
  verifyCheckIn: (payload) => api.post(`/bookings/admin/check-in/verify`, {
    ...payload,
    clientDate: clientDate(),
  }),
  getBlockedDates: () => api.get('/availability/admin/blocked-dates'),
  getBlockedSlots: () => api.get('/availability/admin/blocked-slots'),
  blockDate: (data) => api.post('/availability/admin/block-date', data),
  unblockDate: (date) => api.delete('/availability/admin/unblock-date', { params: { date } }),
  blockSlot: (data) => api.post('/availability/admin/block-slot', data),
  unblockSlot: (date, startMinute) => api.delete('/availability/admin/unblock-slot', { params: { date, startMinute } }),
  initiateRefund: (data) => api.post('/payments/admin/refund', data),
  getRefundsForPayment: (paymentId) => api.get(`/payments/admin/refunds/${paymentId}`),
  getPaymentStats: () => api.get('/payments/admin/stats'),
  simulatePayment: (txnId) => api.post(`/payments/admin/simulate/${txnId}`),
  recordCashPayment: (data) => api.post('/payments/admin/record-cash', data),
  addPayment: (data) => api.post('/payments/admin/add-payment', data),
  // Event type management
  getAllEventTypes: () => api.get('/bookings/admin/event-types'),
  createEventType: (data) => api.post('/bookings/admin/event-types', data),
  updateEventType: (id, data) => api.put(`/bookings/admin/event-types/${id}`, data),
  toggleEventType: (id) => api.patch(`/bookings/admin/event-types/${id}/toggle-active`),
  deleteEventType: (id) => api.delete(`/bookings/admin/event-types/${id}`),
  // Add-on management
  getAllAddOns: () => api.get('/bookings/admin/add-ons'),
  createAddOn: (data) => api.post('/bookings/admin/add-ons', data),
  updateAddOn: (id, data) => api.put(`/bookings/admin/add-ons/${id}`, data),
  toggleAddOn: (id) => api.patch(`/bookings/admin/add-ons/${id}/toggle-active`),
  deleteAddOn: (id) => api.delete(`/bookings/admin/add-ons/${id}`),
  // Event-category management (V55) — per-binge
  getEventCategories: () => api.get('/bookings/admin/event-categories'),
  createEventCategory: (data) => api.post('/bookings/admin/event-categories', data),
  updateEventCategory: (id, data) => api.put(`/bookings/admin/event-categories/${id}`, data),
  toggleEventCategory: (id) => api.patch(`/bookings/admin/event-categories/${id}/toggle-active`),
  deleteEventCategory: (id) => api.delete(`/bookings/admin/event-categories/${id}`),
  // Event-category management — SUPER_ADMIN globals
  getGlobalEventCategories: () => api.get('/bookings/admin/event-categories/global'),
  createGlobalEventCategory: (data) => api.post('/bookings/admin/event-categories/global', data),
  updateGlobalEventCategory: (id, data) => api.put(`/bookings/admin/event-categories/global/${id}`, data),
  toggleGlobalEventCategory: (id) => api.patch(`/bookings/admin/event-categories/global/${id}/toggle-active`),
  deleteGlobalEventCategory: (id) => api.delete(`/bookings/admin/event-categories/global/${id}`),
  // Add-on-category management — per-binge
  getAddOnCategories: () => api.get('/bookings/admin/addon-categories'),
  createAddOnCategory: (data) => api.post('/bookings/admin/addon-categories', data),
  updateAddOnCategory: (id, data) => api.put(`/bookings/admin/addon-categories/${id}`, data),
  toggleAddOnCategory: (id) => api.patch(`/bookings/admin/addon-categories/${id}/toggle-active`),
  deleteAddOnCategory: (id) => api.delete(`/bookings/admin/addon-categories/${id}`),
  // Add-on-category management — SUPER_ADMIN globals
  getGlobalAddOnCategories: () => api.get('/bookings/admin/addon-categories/global'),
  createGlobalAddOnCategory: (data) => api.post('/bookings/admin/addon-categories/global', data),
  updateGlobalAddOnCategory: (id, data) => api.put(`/bookings/admin/addon-categories/global/${id}`, data),
  toggleGlobalAddOnCategory: (id) => api.patch(`/bookings/admin/addon-categories/global/${id}/toggle-active`),
  deleteGlobalAddOnCategory: (id) => api.delete(`/bookings/admin/addon-categories/global/${id}`),
  // Invoices (admin)
  getAdminInvoices: () => api.get('/bookings/admin/invoices'),
  resendInvoice: (ref) => api.post(`/bookings/admin/${ref}/invoice/resend`),
  // Reports
  getReport: (period) => api.get('/bookings/admin/reports', { params: { period, clientDate: clientDate() } }),
  getReportByDateRange: (from, to) => api.get('/bookings/admin/reports/date-range', { params: { from, to, clientDate: clientDate() } }),
  // Operational date (current business day driven by audit)
  getOperationalDate: () => api.get('/bookings/admin/operational-date', { params: { clientDate: clientDate(), clientTime: clientTime() } }),
  // SUPER_ADMIN: advance operational date by one day (manual rollover for clock drift / late audit windows)
  advanceOperationalDate: () => api.post('/bookings/admin/operational-date/advance', null, { params: { clientDate: clientDate() } }),
  // SUPER_ADMIN: override the operational date to a specific value (90d back / 30d forward)
  setOperationalDate: (operationalDate) =>
    api.post('/bookings/admin/operational-date/set', { operationalDate }, { params: { clientDate: clientDate() } }),
  // Audit (no date picker — always audits the current operational date)
  runAudit: () => api.post('/bookings/admin/audit', null, { params: { clientDate: clientDate(), clientTime: clientTime() } }),
  // Admin create booking
  adminCreateBooking: (data) => api.post('/bookings/admin/create-booking', data),
  // Customer booking count
  getCustomerBookingCount: (customerId) => api.get(`/bookings/admin/customer-booking-count/${customerId}`),
  // Booked slots for a date (double-booking prevention)
  getBookedSlots: (date) => api.get('/bookings/admin/booked-slots', { params: { date } }),
  // Booking event log (audit trail)
  getBookingEvents: (ref, page, size) => api.get(`/bookings/admin/${ref}/events`, { params: { page, size } }),
  getBookingReviews: (ref) => api.get(`/bookings/admin/${ref}/reviews`),
  submitBookingReview: (ref, data) => api.post(`/bookings/admin/${ref}/reviews`, data),
  // CQRS projection replay
  replayBooking: (ref) => api.post(`/bookings/admin/${ref}/replay`),
  replayAll: () => api.post('/bookings/admin/replay-all'),
  // Super-admin: force a booking into a target status, bypassing the
  // normal transition table. Reason is mandatory and recorded as a
  // MANUAL_REVIEW_FLAGGED audit row. Idempotency-Key prevents
  // double-application on retries.
  overrideBookingStatus: (ref, targetStatus, reason, idempotencyKey) =>
    api.post(`/bookings/admin/${ref}/override-status`,
      { targetStatus, reason },
      idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : {}),
  // Saga monitoring (super admin)
  getFailedSagas: () => api.get('/bookings/admin/sagas/failed'),
  getCompensatingSagas: () => api.get('/bookings/admin/sagas/compensating'),
  // Retry failed notifications
  retryFailedNotifications: () => api.post('/notifications/admin/retry-failed'),

  // ── Recovery queues (admin) ─────────────────────────────
  // Each endpoint returns { queue, page, size, total, rows }
  getRecoveryStuckPending: (params = {}) => api.get('/bookings/admin/recovery/stuck-pending', { params }),
  getRecoveryExpiredHolds: (params = {}) => api.get('/bookings/admin/recovery/expired-holds', { params }),
  getRecoveryPaidNotConfirmed: (params = {}) => api.get('/bookings/admin/recovery/paid-not-confirmed', { params }),
  getRecoveryNoShow: (params = {}) => api.get('/bookings/admin/recovery/no-show', { params }),
  getRecoverySummary: () => api.get('/bookings/admin/recovery/summary'),
  // Conversion / abandonment funnel for the selected binge.
  // params: { from?: 'YYYY-MM-DD', to?: 'YYYY-MM-DD' } — defaults last 7 days.
  getRecoveryFunnel: (params = {}) => api.get('/bookings/admin/recovery/funnel', { params }),
  // Recovery actions — idempotent, audited
  releaseStaleHold: (token, reason) => api.post(`/bookings/admin/recovery/expired-holds/${token}/release`, { reason }),
  cancelStuckPending: (ref, reason) => api.post(`/bookings/admin/recovery/stuck-pending/${ref}/cancel`, { reason }),
  replayPaidNotConfirmed: (ref) => api.post(`/bookings/admin/recovery/paid-not-confirmed/${ref}/replay`),

  // ── Maker-checker approvals (payment-service) ───────────
  // Risky actions above their configured threshold create an approval
  // request that a *different* admin must approve. Currently wired:
  // refund retry > ₹5000.
  listApprovals: (params = {}) => api.get('/payments/admin/approvals', { params }),
  getApproval: (id) => api.get(`/payments/admin/approvals/${id}`),
  approveApproval: (id, reason) => api.post(`/payments/admin/approvals/${id}/approve`, { reason }),
  rejectApproval: (id, reason) => api.post(`/payments/admin/approvals/${id}/reject`, { reason }),
  cancelApproval: (id, reason) => api.post(`/payments/admin/approvals/${id}/cancel`, { reason }),
  executeApprovedRefundRetry: (id) => api.post(`/payments/admin/approvals/${id}/execute-refund-retry`),
  // Global failed-refund queue (money owed to customers that didn't settle).
  // Returns a Spring Page<RefundDto>; read .content / .totalElements.
  getFailedRefunds: (params = { page: 0, size: 20 }) => api.get('/payments/admin/refunds/failed', { params }),
  // Retry a FAILED refund. Below threshold: completes immediately. Above
  // threshold: server creates an approval request and returns HTTP 202
  // with the approval id in the message body — caller must inspect the
  // response status / message and route the admin to /admin/approvals.
  retryFailedRefund: (refundId) => api.post(`/payments/admin/refunds/${refundId}/retry`),
  // Pricing: Rate Codes
  getRateCodes: () => api.get('/bookings/admin/pricing/rate-codes'),
  getActiveRateCodes: () => api.get('/bookings/admin/pricing/rate-codes/active'),
  getRateCode: (id) => api.get(`/bookings/admin/pricing/rate-codes/${id}`),
  createRateCode: (data) => api.post('/bookings/admin/pricing/rate-codes', data),
  updateRateCode: (id, data) => api.put(`/bookings/admin/pricing/rate-codes/${id}`, data),
  toggleRateCode: (id) => api.patch(`/bookings/admin/pricing/rate-codes/${id}/toggle-active`),
  deleteRateCode: (id) => api.delete(`/bookings/admin/pricing/rate-codes/${id}`),
  // Pricing: Customer
  getCustomerPricing: (customerId) => api.get(`/bookings/admin/pricing/customer/${customerId}`),
  saveCustomerPricing: (data) => api.post('/bookings/admin/pricing/customer', data),
  deleteCustomerPricing: (customerId) => api.delete(`/bookings/admin/pricing/customer/${customerId}`),
  bulkAssignRateCode: (data) => api.post('/bookings/admin/pricing/bulk-assign-rate-code', data),
  updateMemberLabel: (customerId, memberLabel) => api.patch(`/bookings/admin/pricing/customer/${customerId}/member-label`, { memberLabel }),
  resolveCustomerPricing: (customerId, overrideRateCodeId) => {
    // When an override rate code is supplied, the server mirrors the actual
    // booking-time precedence (customer-specific > override > profile rate code > default)
    // so the admin preview matches what will be charged.
    const params = overrideRateCodeId ? { overrideRateCodeId } : {};
    return api.get(`/bookings/admin/pricing/resolve/${customerId}`, { params });
  },
  resolveRateCodePricing: (rateCodeId) => api.get(`/bookings/admin/pricing/resolve-rate-code/${rateCodeId}`),
  getCustomerDetail: (customerId) => api.get(`/bookings/admin/pricing/customer-detail/${customerId}`),
  // Binge management (admin)
  getAdminBinges: () => api.get('/bookings/admin/binges'),
  getBingesByAdmin: (adminId) => api.get(`/bookings/admin/binges/by-admin/${adminId}`),
  createBinge: (data) => api.post(`/bookings/admin/binges?clientDate=${clientDate()}`, data),
  updateBinge: (id, data) => api.put(`/bookings/admin/binges/${id}`, data),
  getBingeDashboardExperience: (id) => api.get(`/bookings/admin/binges/${id}/customer-dashboard`),
  updateBingeDashboardExperience: (id, data) => api.put(`/bookings/admin/binges/${id}/customer-dashboard`, data),
  getBingeAboutExperience: (id) => api.get(`/bookings/admin/binges/${id}/customer-about`),
  updateBingeAboutExperience: (id, data) => api.put(`/bookings/admin/binges/${id}/customer-about`, data),
  toggleBinge: (id) => api.patch(`/bookings/admin/binges/${id}/toggle-active`),
  deleteBinge: (id) => api.delete(`/bookings/admin/binges/${id}`),
  // Binge approval workflow (super-admin)
  getPendingBinges: () => api.get('/bookings/admin/binges/pending'),
  approveBinge: (id) => api.post(`/bookings/admin/binges/${id}/approve`),
  rejectBinge: (id, reason) => api.post(`/bookings/admin/binges/${id}/reject`, { reason: reason || '' }),
  // In-app admin notifications inbox (used by the bell + entrance panel)
  listAdminNotifications: (page = 0, size = 20) =>
    api.get('/bookings/admin/notifications', { params: { page, size } }),
  getAdminNotificationsUnreadCount: () =>
    api.get('/bookings/admin/notifications/unread-count'),
  markAdminNotificationRead: (id) =>
    api.post(`/bookings/admin/notifications/${id}/read`),
  markAllAdminNotificationsRead: () =>
    api.post('/bookings/admin/notifications/read-all'),
  // Cancellation tiers
  getCancellationTiers: (bingeId) => api.get(`/bookings/admin/binges/${bingeId}/cancellation-tiers`),
  saveCancellationTiers: (bingeId, data) => api.put(`/bookings/admin/binges/${bingeId}/cancellation-tiers`, data),
  // Cancellation policy (binge-level freeze + refund flags)
  getCancellationPolicy: (bingeId) => api.get(`/bookings/admin/binges/${bingeId}/cancellation-policy`),
  saveCancellationPolicy: (bingeId, data) => api.put(`/bookings/admin/binges/${bingeId}/cancellation-policy`, data),
  // Customer freezes (admin)
  listFreezes: (bingeId, activeOnly = true) => api.get('/bookings/admin/freezes', { params: { bingeId, activeOnly } }),
  createFreeze: (data) => api.post('/bookings/admin/freezes', data),
  liftFreeze: (id, reason) => api.delete(`/bookings/admin/freezes/${id}`, { data: { reason: reason || '' } }),
  // Waitlist (admin)
  getWaitlistForDate: (date) => api.get('/bookings/waitlist/admin', { params: { date } }),
  getWaitlistCount: (date) => api.get('/bookings/waitlist/admin/count', { params: { date } }),
  cancelWaitlistEntry: (entryId) => api.delete(`/bookings/waitlist/admin/${entryId}`),
  offerWaitlistEntry: (entryId) => api.post(`/bookings/waitlist/admin/${entryId}/offer`),
  // priority: int (0=standard, 10=silver, 20=gold, 30=platinum, 100=ops override)
  updateWaitlistPriority: (entryId, priority) =>
    api.patch(`/bookings/waitlist/admin/${entryId}/priority`, null, { params: { priority } }),
  // ── Admin Ops (super-admin maintenance) ────────────────────
  // Replay poisoned messages from a dead-letter topic back to the live topic.
  // sourceTopic must be in the server-side allow-list.
  replayDlt: (sourceTopic, max = 100) =>
    api.post('/bookings/admin/ops/replay-dlt', null, { params: { sourceTopic, max } }),
  // Reset failedPermanent=false on a single outbox row (id provided) or all.
  retryOutbox: (id) =>
    api.post('/bookings/admin/ops/outbox/retry-failed', null, { params: id ? { id } : {} }),
  getOpsHealth: () => api.get('/bookings/admin/ops/health'),
  // Media upload
  uploadMedia: (formData) => api.post('/bookings/admin/media/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
  // Venue rooms (admin)
  getVenueRooms: () => api.get('/bookings/admin/venue-rooms'),
  createVenueRoom: (data) => api.post('/bookings/admin/venue-rooms', data),
  updateVenueRoom: (id, data) => api.put(`/bookings/admin/venue-rooms/${id}`, data),
  toggleVenueRoom: (id) => api.patch(`/bookings/admin/venue-rooms/${id}/toggle-active`),
  deleteVenueRoom: (id) => api.delete(`/bookings/admin/venue-rooms/${id}`),
  // V56: SUPER_ADMIN approval workflow for ADMIN-created rooms
  approveVenueRoom: (id) => api.post(`/bookings/admin/venue-rooms/${id}/approve`),
  rejectVenueRoom: (id, reason) => api.post(`/bookings/admin/venue-rooms/${id}/reject`, { reason }),
  // V57: maintenance / hold windows on a specific room
  listRoomBlocks: (roomId) => api.get(`/bookings/admin/venue-rooms/${roomId}/blocks`),
  createRoomBlock: (roomId, data) => api.post(`/bookings/admin/venue-rooms/${roomId}/blocks`, data),
  deleteRoomBlock: (blockId) => api.delete(`/bookings/admin/venue-rooms/blocks/${blockId}`),
  // Surge pricing rules (admin)
  getSurgeRules: () => api.get('/bookings/admin/pricing/surge-rules'),
  createSurgeRule: (data) => api.post('/bookings/admin/pricing/surge-rules', data),
  updateSurgeRule: (id, data) => api.put(`/bookings/admin/pricing/surge-rules/${id}`, data),
  toggleSurgeRule: (id) => api.patch(`/bookings/admin/pricing/surge-rules/${id}/toggle-active`),
  deleteSurgeRule: (id) => api.delete(`/bookings/admin/pricing/surge-rules/${id}`),
  // Loyalty (admin)
  getCustomerLoyalty: async (customerId) => asApiResponse(await loyaltyV2.getCustomerAccount(customerId)),
  adjustLoyaltyPoints: async (customerId, data) => asApiResponse(await loyaltyV2.adjustCustomerPoints(customerId, data)),
  // Customer review assessment (admin-side)
  getCustomerReviewSummary: (customerId) => api.get(`/bookings/admin/customers/${customerId}/review-summary`),
  getCustomerAdminReviews: (customerId, page = 0, size = 10) => api.get(`/bookings/admin/customers/${customerId}/reviews`, { params: { page, size } }),
};

// ── Support console (Item 24) ───────────────────────────────────────────────
export const adminSupportService = {
  // Booking lookup (single ref, any date — unlike searchBookings which is today-only)
  getByRef: (ref) => api.get(`/bookings/admin/support/${ref}`),
  // Threaded notes
  listNotes: (ref) => api.get(`/bookings/admin/support/${ref}/notes`),
  addNote: (ref, payload) => api.post(`/bookings/admin/support/${ref}/notes`, payload),
  editNote: (id, body) => api.patch(`/bookings/admin/support/notes/${id}`, { body }),
  deleteNote: (id) => api.delete(`/bookings/admin/support/notes/${id}`),
  pinNote: (id, pinned = true) => api.post(`/bookings/admin/support/notes/${id}/pin`, { pinned }),
  // Operator actions
  resendConfirmation: (ref) => api.post(`/bookings/admin/support/${ref}/resend-confirmation`),
  escalate: (ref, level, reason) => api.post(`/bookings/admin/support/${ref}/escalate`, { level, reason }),
  goodwill: (ref, amount, reason) => api.post(`/bookings/admin/support/${ref}/goodwill`, { amount, reason }),
  // Per-row notification retry
  retryNotification: (id) => api.post(`/notifications/admin/${id}/retry`),
};

// ── Payment disputes / chargebacks (admin) ──────────────────────────────────
// Razorpay chargebacks are ingested by payment-service's DisputeWebhookService
// and triaged here. Every endpoint is binge-scoped server-side via the
// X-Binge-Id header (auto-attached by api.js), so the selected binge must be set.
// Disputes are money-at-risk with a hard Razorpay respond-by deadline, so the
// list is sorted most-urgent-first and the count drives the dashboard alert.
export const disputeService = {
  // Open (non-terminal) disputes, sorted by respond-by deadline ASC.
  listOpen: (params = { page: 0, size: 20 }) => api.get('/payments/admin/disputes', { params }),
  // Full history including WON / LOST / ACCEPTED — for audit + win/loss reporting.
  listAll: (params = { page: 0, size: 20 }) => api.get('/payments/admin/disputes/all', { params }),
  // { openDisputes: N } — non-zero lights up the dashboard alert badge.
  count: () => api.get('/payments/admin/disputes/count'),
  // Append a timestamped ops note (evidence trail before responding to Razorpay).
  updateNotes: (disputeId, notes) => api.patch(`/payments/admin/disputes/${disputeId}/notes`, { notes }),
};

// ── Risk flags (Item 23) ────────────────────────────────────────────────────
export const adminRiskService = {
  list: (bingeId, openOnly = true, page = 0, size = 50) =>
    api.get('/bookings/admin/risk-flags', { params: { bingeId, openOnly, page, size } }),
  listForBooking: (ref) => api.get(`/bookings/admin/risk-flags/booking/${ref}`),
  acknowledge: (id, note) => api.post(`/bookings/admin/risk-flags/${id}/acknowledge`, { note }),
  createManual: (ref, severity, reason) =>
    api.post(`/bookings/admin/risk-flags/booking/${ref}/manual`, { severity, reason }),
};
