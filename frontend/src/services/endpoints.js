import api from './api';

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
  register: (data) => api.post('/auth/register', data),
  login: (data) => api.post('/auth/login', data),
  googleLogin: (data) => api.post('/auth/google', data),
  adminLogin: (data) => api.post('/auth/admin/login', data),
  adminRegister: (data) => api.post('/auth/admin/register', data),
  getProfile: () => api.get('/auth/profile'),
  updateAccountPreferences: (data) => api.put('/auth/profile/preferences', data),
  getSupportContact: () => api.get('/auth/support-contact'),
  completeProfile: (data) => api.put('/auth/complete-profile', data),
  changePassword: (data) => api.put('/auth/change-password', data),
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
};

export const bookingService = {
  getEventTypes: () => api.get('/bookings/event-types'),
  getAddOns: () => api.get('/bookings/add-ons'),
  createBooking: (data) => api.post('/bookings', data),
  getByRef: (ref) => api.get(`/bookings/${ref}`),
  getMyBookings: () => api.get('/bookings/my'),
  getCurrentBookings: () => api.get('/bookings/my/current', { params: { clientDate: clientDate() } }),
  getPastBookings: () => api.get('/bookings/my/past', { params: { clientDate: clientDate() } }),
  getPendingReviews: () => api.get('/bookings/my/reviews/pending', { params: { clientDate: clientDate() } }),
  getBookedSlots: (date) => api.get('/bookings/booked-slots', { params: { date } }),
  cancelBooking: (ref) => api.post(`/bookings/${ref}/cancel`),
  getCustomerReview: (ref) => api.get(`/bookings/${ref}/reviews/customer`),
  submitCustomerReview: (ref, data) => api.post(`/bookings/${ref}/reviews/customer`, data),
  getMyPricing: () => api.get('/bookings/my-pricing'),
  // Binge (public)
  getAllActiveBinges: () => api.get('/bookings/binges'),
  getBingeById: (id) => api.get(`/bookings/binges/${id}`),
  getBingeDashboardExperience: (id) => api.get(`/bookings/binges/${id}/customer-dashboard`),
  getBingeAboutExperience: (id) => api.get(`/bookings/binges/${id}/customer-about`),
  getBingeReviewSummary: (id) => api.get(`/bookings/binges/${id}/reviews/summary`),
  getBingeReviews: (id, page = 0, size = 10) => api.get(`/bookings/binges/${id}/reviews`, { params: { page, size } }),
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

export const adminService = {
  getDashboardStats: () => api.get('/bookings/admin/dashboard-stats', { params: { clientDate: clientDate() } }),
  getAllBookings: (page, size) => api.get('/bookings/admin', { params: { page, size } }),
  getTodayBookings: (page, size) => api.get('/bookings/admin/today', { params: { page, size, clientDate: clientDate() } }),
  getUpcomingBookings: (page, size) => api.get('/bookings/admin/upcoming', { params: { page, size, clientDate: clientDate() } }),
  getBookingsByDate: (date, page, size) => api.get('/bookings/admin/by-date', { params: { date, page, size } }),
  getBookingsByStatus: (status, page, size) => api.get('/bookings/admin/by-status', { params: { status, page, size, clientDate: clientDate() } }),
  searchBookings: (keyword) => api.get('/bookings/admin/search', { params: { q: keyword, clientDate: clientDate() } }),
  updateBooking: (ref, data) => api.patch(`/bookings/admin/${ref}`, data),
  cancelBooking: (ref, reason) => api.post(`/bookings/admin/${ref}/cancel`, null, { params: { reason } }),
  confirmBooking: (ref) => api.post(`/bookings/admin/${ref}/confirm`),
  checkIn: (ref) => api.post(`/bookings/admin/${ref}/check-in`, null, { params: { clientDate: clientDate() } }),
  checkout: (ref) => api.post(`/bookings/admin/${ref}/checkout`, null, { params: { clientDate: clientDate(), clientTime: clientTime() } }),
  undoCheckIn: (ref) => api.post(`/bookings/admin/${ref}/undo-check-in`, null, { params: { clientDate: clientDate() } }),
  getBlockedDates: () => api.get('/availability/admin/blocked-dates'),
  getBlockedSlots: () => api.get('/availability/admin/blocked-slots'),
  blockDate: (data) => api.post('/availability/admin/block-date', data),
  unblockDate: (date) => api.delete('/availability/admin/unblock-date', { params: { date } }),
  blockSlot: (data) => api.post('/availability/admin/block-slot', data),
  unblockSlot: (date, startHour) => api.delete('/availability/admin/unblock-slot', { params: { date, startHour } }),
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
  // Reports
  getReport: (period) => api.get('/bookings/admin/reports', { params: { period, clientDate: clientDate() } }),
  getReportByDateRange: (from, to) => api.get('/bookings/admin/reports/date-range', { params: { from, to, clientDate: clientDate() } }),
  // Operational date (current business day driven by audit)
  getOperationalDate: () => api.get('/bookings/admin/operational-date', { params: { clientDate: clientDate(), clientTime: clientTime() } }),
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
  // Saga monitoring (super admin)
  getFailedSagas: () => api.get('/bookings/admin/sagas/failed'),
  getCompensatingSagas: () => api.get('/bookings/admin/sagas/compensating'),
  // Retry failed notifications
  retryFailedNotifications: () => api.post('/notifications/admin/retry-failed'),
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
  resolveCustomerPricing: (customerId) => api.get(`/bookings/admin/pricing/resolve/${customerId}`),
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
  // Media upload
  uploadMedia: (formData) => api.post('/bookings/admin/media/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } }),
};
