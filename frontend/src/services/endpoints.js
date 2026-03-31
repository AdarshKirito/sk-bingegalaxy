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
  adminLogin: (data) => api.post('/auth/admin/login', data),
  getProfile: () => api.get('/auth/profile'),
  forgotPassword: (data) => api.post('/auth/forgot-password', data),
  resetPassword: (data) => api.post('/auth/reset-password', data),
  verifyOtp: (data) => api.post('/auth/verify-otp', data),
  searchCustomers: (q) => api.get('/auth/admin/search-customers', { params: { q } }),
  getAllCustomers: () => api.get('/auth/admin/customers'),
  adminCreateCustomer: (data) => api.post('/auth/admin/create-customer', data),
  getCustomerById: (id) => api.get(`/auth/admin/customer/${id}`),
  adminUpdateCustomer: (id, data) => api.put(`/auth/admin/customer/${id}`, data),
};

export const bookingService = {
  getEventTypes: () => api.get('/bookings/event-types'),
  getAddOns: () => api.get('/bookings/add-ons'),
  createBooking: (data) => api.post('/bookings', data),
  getByRef: (ref) => api.get(`/bookings/${ref}`),
  getMyBookings: () => api.get('/bookings/my'),
  getCurrentBookings: () => api.get('/bookings/my/current', { params: { clientDate: clientDate() } }),
  getPastBookings: () => api.get('/bookings/my/past', { params: { clientDate: clientDate() } }),
  getBookedSlots: (date) => api.get('/bookings/booked-slots', { params: { date } }),
  getMyPricing: () => api.get('/bookings/my-pricing'),
};

export const availabilityService = {
  getDates: (from, to) => api.get('/availability/dates', { params: { from, to, clientDate: clientDate() } }),
  getSlots: (date) => api.get('/availability/slots', { params: { date } }),
};

export const paymentService = {
  initiate: (data) => api.post('/payments/initiate', data),
  simulate: (txnId) => api.post(`/payments/simulate/${txnId}`),
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
  getBookingsByStatus: (status, page, size) => api.get('/bookings/admin/by-status', { params: { status, page, size } }),
  searchBookings: (keyword) => api.get('/bookings/admin/search', { params: { q: keyword } }),
  updateBooking: (ref, data) => api.patch(`/bookings/admin/${ref}`, data),
  cancelBooking: (ref, reason) => api.post(`/bookings/admin/${ref}/cancel`, null, { params: { reason } }),
  confirmBooking: (ref) => api.post(`/bookings/admin/${ref}/confirm`),
  checkIn: (ref) => api.post(`/bookings/admin/${ref}/check-in`),
  checkout: (ref) => api.post(`/bookings/admin/${ref}/checkout`, null, { params: { clientDate: clientDate(), clientTime: clientTime() } }),
  undoCheckIn: (ref) => api.post(`/bookings/admin/${ref}/undo-check-in`),
  getBlockedDates: () => api.get('/availability/admin/blocked-dates'),
  getBlockedSlots: () => api.get('/availability/admin/blocked-slots'),
  blockDate: (data) => api.post('/availability/admin/block-date', data),
  unblockDate: (date) => api.delete('/availability/admin/unblock-date', { params: { date } }),
  blockSlot: (data) => api.post('/availability/admin/block-slot', data),
  unblockSlot: (date, startHour) => api.delete('/availability/admin/unblock-slot', { params: { date, startHour } }),
  initiateRefund: (data) => api.post('/payments/admin/refund', data),
  getRefundsForPayment: (paymentId) => api.get(`/payments/admin/refunds/${paymentId}`),
  getPaymentStats: () => api.get('/payments/admin/stats'),
  recordCashPayment: (data) => api.post('/payments/admin/record-cash', data),
  addPayment: (data) => api.post('/payments/admin/add-payment', data),
  // Event type management
  getAllEventTypes: () => api.get('/bookings/admin/event-types'),
  createEventType: (data) => api.post('/bookings/admin/event-types', data),
  updateEventType: (id, data) => api.put(`/bookings/admin/event-types/${id}`, data),
  toggleEventType: (id) => api.delete(`/bookings/admin/event-types/${id}`),
  // Add-on management
  getAllAddOns: () => api.get('/bookings/admin/add-ons'),
  createAddOn: (data) => api.post('/bookings/admin/add-ons', data),
  updateAddOn: (id, data) => api.put(`/bookings/admin/add-ons/${id}`, data),
  toggleAddOn: (id) => api.delete(`/bookings/admin/add-ons/${id}`),
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
  // Pricing: Rate Codes
  getRateCodes: () => api.get('/bookings/admin/pricing/rate-codes'),
  getActiveRateCodes: () => api.get('/bookings/admin/pricing/rate-codes/active'),
  getRateCode: (id) => api.get(`/bookings/admin/pricing/rate-codes/${id}`),
  createRateCode: (data) => api.post('/bookings/admin/pricing/rate-codes', data),
  updateRateCode: (id, data) => api.put(`/bookings/admin/pricing/rate-codes/${id}`, data),
  toggleRateCode: (id) => api.delete(`/bookings/admin/pricing/rate-codes/${id}`),
  // Pricing: Customer
  getCustomerPricing: (customerId) => api.get(`/bookings/admin/pricing/customer/${customerId}`),
  saveCustomerPricing: (data) => api.post('/bookings/admin/pricing/customer', data),
  bulkAssignRateCode: (data) => api.post('/bookings/admin/pricing/bulk-assign-rate-code', data),
  resolveCustomerPricing: (customerId) => api.get(`/bookings/admin/pricing/resolve/${customerId}`),
  resolveRateCodePricing: (rateCodeId) => api.get(`/bookings/admin/pricing/resolve-rate-code/${rateCodeId}`),
};
