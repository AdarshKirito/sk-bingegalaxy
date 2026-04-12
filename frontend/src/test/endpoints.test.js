import { describe, it, expect, vi, beforeEach } from 'vitest';

// Mock api module
vi.mock('../services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

import api from '../services/api';
import {
  authService,
  bookingService,
  availabilityService,
  paymentService,
  adminService,
} from '../services/endpoints';

describe('authService', () => {
  beforeEach(() => vi.clearAllMocks());

  it('register calls POST /auth/register', async () => {
    api.post.mockResolvedValue({ data: { data: { user: {}, token: '' } } });
    await authService.register({ email: 'test@test.com', password: '123' });
    expect(api.post).toHaveBeenCalledWith('/auth/register', { email: 'test@test.com', password: '123' });
  });

  it('login calls POST /auth/login', async () => {
    api.post.mockResolvedValue({ data: {} });
    await authService.login({ email: 'a@b.com', password: 'pw' });
    expect(api.post).toHaveBeenCalledWith('/auth/login', { email: 'a@b.com', password: 'pw' });
  });

  it('adminLogin calls POST /auth/admin/login', async () => {
    api.post.mockResolvedValue({ data: {} });
    await authService.adminLogin({ email: 'admin@test.com', password: 'pw' });
    expect(api.post).toHaveBeenCalledWith('/auth/admin/login', { email: 'admin@test.com', password: 'pw' });
  });

  it('getProfile calls GET /auth/profile', async () => {
    api.get.mockResolvedValue({ data: {} });
    await authService.getProfile();
    expect(api.get).toHaveBeenCalledWith('/auth/profile');
  });

  it('googleLogin sends credential', async () => {
    api.post.mockResolvedValue({ data: {} });
    await authService.googleLogin({ credential: 'google-token' });
    expect(api.post).toHaveBeenCalledWith('/auth/google', { credential: 'google-token' });
  });

  it('forgotPassword calls POST /auth/forgot-password', async () => {
    api.post.mockResolvedValue({ data: {} });
    await authService.forgotPassword({ email: 'user@test.com' });
    expect(api.post).toHaveBeenCalledWith('/auth/forgot-password', { email: 'user@test.com' });
  });

  it('resetPassword calls POST /auth/reset-password', async () => {
    api.post.mockResolvedValue({ data: {} });
    await authService.resetPassword({ token: 'abc', password: 'new' });
    expect(api.post).toHaveBeenCalledWith('/auth/reset-password', { token: 'abc', password: 'new' });
  });

  it('searchCustomers calls GET with query param', async () => {
    api.get.mockResolvedValue({ data: {} });
    await authService.searchCustomers('john');
    expect(api.get).toHaveBeenCalledWith('/auth/admin/search-customers', { params: { q: 'john' } });
  });

  it('deleteUser calls DELETE with id', async () => {
    api.delete.mockResolvedValue({ data: {} });
    await authService.deleteUser('user123');
    expect(api.delete).toHaveBeenCalledWith('/auth/admin/user/user123');
  });
});

describe('bookingService', () => {
  beforeEach(() => vi.clearAllMocks());

  it('getEventTypes calls GET /bookings/event-types', async () => {
    api.get.mockResolvedValue({ data: { data: [] } });
    await bookingService.getEventTypes();
    expect(api.get).toHaveBeenCalledWith('/bookings/event-types');
  });

  it('getAddOns calls GET /bookings/add-ons', async () => {
    api.get.mockResolvedValue({ data: { data: [] } });
    await bookingService.getAddOns();
    expect(api.get).toHaveBeenCalledWith('/bookings/add-ons');
  });

  it('createBooking calls POST /bookings with payload', async () => {
    api.post.mockResolvedValue({ data: { data: { bookingRef: 'BK-001' } } });
    const payload = { eventTypeId: 1, bookingDate: '2025-01-15' };
    await bookingService.createBooking(payload);
    expect(api.post).toHaveBeenCalledWith('/bookings', payload);
  });

  it('getByRef calls GET /bookings/:ref', async () => {
    api.get.mockResolvedValue({ data: { data: {} } });
    await bookingService.getByRef('BK-001');
    expect(api.get).toHaveBeenCalledWith('/bookings/BK-001');
  });

  it('cancelBooking calls POST /bookings/:ref/cancel', async () => {
    api.post.mockResolvedValue({ data: {} });
    await bookingService.cancelBooking('BK-001');
    expect(api.post).toHaveBeenCalledWith('/bookings/BK-001/cancel');
  });

  it('getMyBookings calls GET /bookings/my', async () => {
    api.get.mockResolvedValue({ data: { data: [] } });
    await bookingService.getMyBookings();
    expect(api.get).toHaveBeenCalledWith('/bookings/my');
  });

  it('getBookedSlots calls GET with date param', async () => {
    api.get.mockResolvedValue({ data: { data: [] } });
    await bookingService.getBookedSlots('2025-01-15');
    expect(api.get).toHaveBeenCalledWith('/bookings/booked-slots', { params: { date: '2025-01-15' } });
  });

  it('getAllActiveBinges calls GET /bookings/binges', async () => {
    api.get.mockResolvedValue({ data: { data: [] } });
    await bookingService.getAllActiveBinges();
    expect(api.get).toHaveBeenCalledWith('/bookings/binges');
  });

  it('getBingeDashboardExperience calls GET /bookings/binges/:id/customer-dashboard', async () => {
    api.get.mockResolvedValue({ data: { data: {} } });
    await bookingService.getBingeDashboardExperience(3);
    expect(api.get).toHaveBeenCalledWith('/bookings/binges/3/customer-dashboard');
  });
});

describe('availabilityService', () => {
  beforeEach(() => vi.clearAllMocks());

  it('getDates calls GET with from/to params', async () => {
    api.get.mockResolvedValue({ data: { data: [] } });
    await availabilityService.getDates('2025-01-01', '2025-01-31');
    expect(api.get).toHaveBeenCalledWith('/availability/dates', expect.objectContaining({
      params: expect.objectContaining({ from: '2025-01-01', to: '2025-01-31' }),
    }));
  });

  it('getSlots calls GET with date param', async () => {
    api.get.mockResolvedValue({ data: { data: {} } });
    await availabilityService.getSlots('2025-01-15');
    expect(api.get).toHaveBeenCalledWith('/availability/slots', { params: { date: '2025-01-15' } });
  });
});

describe('paymentService', () => {
  beforeEach(() => vi.clearAllMocks());

  it('initiate calls POST /payments/initiate', async () => {
    api.post.mockResolvedValue({ data: {} });
    await paymentService.initiate({ bookingRef: 'BK-001', amount: 5000 });
    expect(api.post).toHaveBeenCalledWith('/payments/initiate', { bookingRef: 'BK-001', amount: 5000 });
  });

  it('getByBooking calls GET /payments/booking/:ref', async () => {
    api.get.mockResolvedValue({ data: {} });
    await paymentService.getByBooking('BK-001');
    expect(api.get).toHaveBeenCalledWith('/payments/booking/BK-001');
  });

  it('getMyPayments calls GET /payments/my', async () => {
    api.get.mockResolvedValue({ data: {} });
    await paymentService.getMyPayments();
    expect(api.get).toHaveBeenCalledWith('/payments/my');
  });

  it('cancelPayment calls POST /payments/cancel/:txnId', async () => {
    api.post.mockResolvedValue({ data: {} });
    await paymentService.cancelPayment('TXN-001');
    expect(api.post).toHaveBeenCalledWith('/payments/cancel/TXN-001');
  });
});

describe('adminService', () => {
  beforeEach(() => vi.clearAllMocks());

  it('getDashboardStats calls GET /bookings/admin/dashboard-stats', async () => {
    api.get.mockResolvedValue({ data: {} });
    await adminService.getDashboardStats();
    expect(api.get).toHaveBeenCalledWith('/bookings/admin/dashboard-stats', expect.anything());
  });

  it('getAllBookings calls GET with page/size', async () => {
    api.get.mockResolvedValue({ data: {} });
    await adminService.getAllBookings(0, 10);
    expect(api.get).toHaveBeenCalledWith('/bookings/admin', { params: { page: 0, size: 10 } });
  });

  it('cancelBooking calls POST with reason', async () => {
    api.post.mockResolvedValue({ data: {} });
    await adminService.cancelBooking('BK-001', 'No show');
    expect(api.post).toHaveBeenCalledWith('/bookings/admin/BK-001/cancel', null, { params: { reason: 'No show' } });
  });

  it('confirmBooking calls POST /bookings/admin/:ref/confirm', async () => {
    api.post.mockResolvedValue({ data: {} });
    await adminService.confirmBooking('BK-001');
    expect(api.post).toHaveBeenCalledWith('/bookings/admin/BK-001/confirm');
  });

  it('blockDate calls POST /availability/admin/block-date', async () => {
    api.post.mockResolvedValue({ data: {} });
    await adminService.blockDate({ date: '2025-01-15', reason: 'Holiday' });
    expect(api.post).toHaveBeenCalledWith('/availability/admin/block-date', { date: '2025-01-15', reason: 'Holiday' });
  });

  it('createEventType calls POST', async () => {
    api.post.mockResolvedValue({ data: {} });
    await adminService.createEventType({ name: 'Birthday Party' });
    expect(api.post).toHaveBeenCalledWith('/bookings/admin/event-types', { name: 'Birthday Party' });
  });

  it('getCustomerDetail calls GET with customerId', async () => {
    api.get.mockResolvedValue({ data: { data: {} } });
    await adminService.getCustomerDetail(42);
    expect(api.get).toHaveBeenCalledWith('/bookings/admin/pricing/customer-detail/42');
  });

  it('updateMemberLabel calls PATCH with customerId and label', async () => {
    api.patch.mockResolvedValue({ data: {} });
    await adminService.updateMemberLabel(7, 'Gold');
    expect(api.patch).toHaveBeenCalledWith('/bookings/admin/pricing/customer/7/member-label', { memberLabel: 'Gold' });
  });

  it('getBingeDashboardExperience calls GET with binge id', async () => {
    api.get.mockResolvedValue({ data: { data: {} } });
    await adminService.getBingeDashboardExperience(8);
    expect(api.get).toHaveBeenCalledWith('/bookings/admin/binges/8/customer-dashboard');
  });

  it('updateBingeDashboardExperience calls PUT with binge id and payload', async () => {
    api.put.mockResolvedValue({ data: {} });
    await adminService.updateBingeDashboardExperience(8, { layout: 'CAROUSEL', slides: [] });
    expect(api.put).toHaveBeenCalledWith('/bookings/admin/binges/8/customer-dashboard', { layout: 'CAROUSEL', slides: [] });
  });
});

describe('authService – bulk operations', () => {
  beforeEach(() => vi.clearAllMocks());

  it('bulkBan calls POST /auth/admin/bulk-ban with user IDs', async () => {
    api.post.mockResolvedValue({ data: { data: 2 } });
    await authService.bulkBan([1, 2]);
    expect(api.post).toHaveBeenCalledWith('/auth/admin/bulk-ban', [1, 2]);
  });

  it('bulkUnban calls POST /auth/admin/bulk-unban with user IDs', async () => {
    api.post.mockResolvedValue({ data: { data: 2 } });
    await authService.bulkUnban([3, 4]);
    expect(api.post).toHaveBeenCalledWith('/auth/admin/bulk-unban', [3, 4]);
  });

  it('bulkDelete calls POST /auth/admin/bulk-delete with user IDs', async () => {
    api.post.mockResolvedValue({ data: { data: 1 } });
    await authService.bulkDelete([5]);
    expect(api.post).toHaveBeenCalledWith('/auth/admin/bulk-delete', [5]);
  });

  it('deleteEventType calls DELETE', async () => {
    api.delete.mockResolvedValue({ data: {} });
    await adminService.deleteEventType(5);
    expect(api.delete).toHaveBeenCalledWith('/bookings/admin/event-types/5');
  });

  it('getReport calls GET with period', async () => {
    api.get.mockResolvedValue({ data: {} });
    await adminService.getReport('MONTHLY');
    expect(api.get).toHaveBeenCalledWith('/bookings/admin/reports', expect.objectContaining({
      params: expect.objectContaining({ period: 'MONTHLY' }),
    }));
  });

  it('runAudit calls POST', async () => {
    api.post.mockResolvedValue({ data: {} });
    await adminService.runAudit();
    expect(api.post).toHaveBeenCalledWith('/bookings/admin/audit', null, expect.anything());
  });

  it('createBinge calls POST', async () => {
    api.post.mockResolvedValue({ data: {} });
    await adminService.createBinge({ name: 'Test Binge' });
    expect(api.post).toHaveBeenCalledWith(
      expect.stringContaining('/bookings/admin/binges'),
      { name: 'Test Binge' }
    );
  });

  it('getRateCodes calls GET', async () => {
    api.get.mockResolvedValue({ data: {} });
    await adminService.getRateCodes();
    expect(api.get).toHaveBeenCalledWith('/bookings/admin/pricing/rate-codes');
  });

  it('saveCustomerPricing calls POST', async () => {
    api.post.mockResolvedValue({ data: {} });
    await adminService.saveCustomerPricing({ customerId: 'abc', rateCodeId: 1 });
    expect(api.post).toHaveBeenCalledWith('/bookings/admin/pricing/customer', { customerId: 'abc', rateCodeId: 1 });
  });
});
