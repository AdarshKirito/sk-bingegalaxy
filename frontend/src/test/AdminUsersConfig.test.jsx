import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warn: vi.fn() },
}));

vi.mock('dompurify', () => ({
  default: { sanitize: (v) => v },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    user: { id: '1', firstName: 'Admin', role: 'SUPER_ADMIN', active: true },
    isAuthenticated: true,
    isAdmin: true,
    isSuperAdmin: true,
  }),
}));

vi.mock('../context/BingeContext', () => ({
  useBinge: () => ({
    selectedBinge: { id: 1, name: 'Main Branch' },
  }),
}));

const {
  mockGetAllCustomers,
  mockGetAllAdmins,
  mockGetRateCodes,
  mockBulkBan,
  mockBulkUnban,
  mockBulkDelete,
  mockDeleteUser,
  mockGetCustomerById,
  mockAdminUpdateCustomer,
  mockGetCustomerDetail,
  mockBulkAssignRateCode,
} = vi.hoisted(() => ({
  mockGetAllCustomers: vi.fn(),
  mockGetAllAdmins: vi.fn(),
  mockGetRateCodes: vi.fn(),
  mockBulkBan: vi.fn(),
  mockBulkUnban: vi.fn(),
  mockBulkDelete: vi.fn(),
  mockDeleteUser: vi.fn(),
  mockGetCustomerById: vi.fn(),
  mockAdminUpdateCustomer: vi.fn(),
  mockGetCustomerDetail: vi.fn(),
  mockBulkAssignRateCode: vi.fn(),
}));

vi.mock('../services/endpoints', () => ({
  authService: {
    getAllCustomers: mockGetAllCustomers,
    getAllAdmins: mockGetAllAdmins,
    searchCustomers: vi.fn().mockResolvedValue({ data: { data: [] } }),
    bulkBan: mockBulkBan,
    bulkUnban: mockBulkUnban,
    bulkDelete: mockBulkDelete,
    deleteUser: mockDeleteUser,
    getCustomerById: mockGetCustomerById,
    adminUpdateCustomer: mockAdminUpdateCustomer,
    updateAdmin: vi.fn().mockResolvedValue({ data: {} }),
  },
  adminService: {
    getRateCodes: mockGetRateCodes,
    getCustomerDetail: mockGetCustomerDetail,
    bulkAssignRateCode: mockBulkAssignRateCode,
    updateMemberLabel: vi.fn().mockResolvedValue({ data: {} }),
    getCustomerPricing: vi.fn().mockResolvedValue({ data: { data: {} } }),
  },
}));

import AdminUsersConfig from '../pages/AdminUsersConfig';
import { toast } from 'react-toastify';

const MOCK_CUSTOMERS = [
  { id: 1, firstName: 'Alice', lastName: 'Smith', email: 'alice@test.com', phone: '1234567890', role: 'CUSTOMER', active: true },
  { id: 2, firstName: 'Bob', lastName: 'Jones', email: 'bob@test.com', phone: '0987654321', role: 'CUSTOMER', active: true },
  { id: 3, firstName: 'Charlie', lastName: 'Brown', email: 'charlie@test.com', phone: '5555555555', role: 'CUSTOMER', active: false },
];

const MOCK_ADMINS = [
  { id: 10, firstName: 'SuperAd', lastName: 'Min', email: 'super@test.com', role: 'SUPER_ADMIN', active: true },
  { id: 11, firstName: 'Regular', lastName: 'Admin', email: 'admin@test.com', role: 'ADMIN', active: true },
];

const MOCK_RATE_CODES = [
  { id: 100, name: 'VIP', description: 'VIP rate', active: true, eventPricings: [{}], addonPricings: [] },
  { id: 101, name: 'Standard', description: 'Standard rate', active: true, eventPricings: [], addonPricings: [] },
];

const MOCK_CUSTOMER_DETAIL = {
  customerId: 1,
  currentRateCodeId: 100,
  currentRateCodeName: 'VIP',
  memberLabel: 'Gold Member',
  totalReservations: 2,
  rateCodeChanges: [
    { id: 1, previousRateCodeName: null, newRateCodeName: 'VIP', changeType: 'ASSIGN', changedByAdminId: 10, changedAt: '2025-12-01T10:00:00' },
    { id: 2, previousRateCodeName: 'VIP', newRateCodeName: 'Standard', changeType: 'REASSIGN', changedByAdminId: 10, changedAt: '2026-01-15T14:30:00' },
  ],
  reservations: [
    { bookingRef: 'BK-001', eventTypeName: 'Birthday', bookingDate: '2026-01-20', startTime: '14:00', durationMinutes: 120, status: 'CONFIRMED', paymentStatus: 'SUCCESS', totalAmount: 5000, collectedAmount: 5000, pricingSource: 'RATE_CODE', rateCodeName: 'VIP', createdAt: '2026-01-10T08:00:00' },
    { bookingRef: 'BK-002', eventTypeName: 'Anniversary', bookingDate: '2026-02-14', startTime: '18:00', durationMinutes: 180, status: 'PENDING', paymentStatus: 'PENDING', totalAmount: 8000, collectedAmount: 0, pricingSource: 'DEFAULT', rateCodeName: null, createdAt: '2026-02-01T11:00:00' },
  ],
};

function renderPage(initialEntries = ['/admin/users-config']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <AdminUsersConfig />
    </MemoryRouter>
  );
}

describe('AdminUsersConfig Page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.confirm = vi.fn(() => true);

    mockGetAllCustomers.mockResolvedValue({ data: { data: MOCK_CUSTOMERS } });
    mockGetAllAdmins.mockResolvedValue({ data: { data: MOCK_ADMINS } });
    mockGetRateCodes.mockResolvedValue({ data: { data: MOCK_RATE_CODES } });
    mockGetCustomerDetail.mockResolvedValue({ data: { data: MOCK_CUSTOMER_DETAIL } });
    mockGetCustomerById.mockResolvedValue({ data: { data: MOCK_CUSTOMERS[0] } });
    mockBulkBan.mockResolvedValue({ data: { data: 1 } });
    mockBulkUnban.mockResolvedValue({ data: { data: 1 } });
    mockBulkDelete.mockResolvedValue({ data: { data: 1 } });
    mockDeleteUser.mockResolvedValue({ data: {} });
    mockAdminUpdateCustomer.mockResolvedValue({ data: {} });
    mockBulkAssignRateCode.mockResolvedValue({ data: { data: 1 } });
  });

  /* ─── TAB RENDERING ─────────────────────────────────── */

  it('renders the main tabs (Customers, Admins, Rate Codes)', async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText('Customers')).toBeInTheDocument();
      expect(screen.getByText('Admins')).toBeInTheDocument();
      expect(screen.getByText('Rate Codes')).toBeInTheDocument();
    });
  });

  it('loads and displays customers on first render', async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByText(/Alice/)).toBeInTheDocument();
      expect(screen.getByText(/Bob/)).toBeInTheDocument();
    });
    expect(mockGetAllCustomers).toHaveBeenCalledTimes(1);
  });

  it('switches to Admins tab and shows admin list', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getByText('Admins'));
    await waitFor(() => {
      expect(screen.getByText(/SuperAd/)).toBeInTheDocument();
      expect(screen.getByText(/Regular/)).toBeInTheDocument();
    });
  });

  it('switches to Rate Codes tab and shows rate codes', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getByText('Rate Codes'));
    await waitFor(() => {
      expect(screen.getByText('VIP')).toBeInTheDocument();
      expect(screen.getByText('Standard')).toBeInTheDocument();
    });
  });

  /* ─── SEARCH FILTERING ─────────────────────────────── */

  it('filters customers by search input', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    const searchInput = screen.getByPlaceholderText('Search name, email or phone…');
    await user.type(searchInput, 'bob');
    expect(screen.queryByText(/Alice/)).not.toBeInTheDocument();
    expect(screen.getByText(/Bob/)).toBeInTheDocument();
  });

  /* ─── CHECKBOX SELECTION ────────────────────────────── */

  it('shows checkboxes and selects individual customers', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    const checkboxes = screen.getAllByRole('checkbox');
    // First checkbox is select-all, then one per customer
    expect(checkboxes.length).toBe(MOCK_CUSTOMERS.length + 1);
    await user.click(checkboxes[1]); // select Alice
    expect(screen.getByText('1 selected')).toBeInTheDocument();
  });

  it('select-all checkbox selects all visible customers', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    const selectAll = screen.getAllByRole('checkbox')[0];
    await user.click(selectAll);
    expect(screen.getByText(`${MOCK_CUSTOMERS.length} selected`)).toBeInTheDocument();
  });

  /* ─── BULK BAN / UNBAN ─────────────────────────────── */

  it('bulk ban calls API with selected user IDs', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[1]); // select Alice (id=1)
    await user.click(checkboxes[2]); // select Bob (id=2)
    expect(screen.getByText('2 selected')).toBeInTheDocument();

    const banBtn = screen.getAllByRole('button', { name: 'Ban' })[0];
    await user.click(banBtn);
    expect(window.confirm).toHaveBeenCalled();
    await waitFor(() => expect(mockBulkBan).toHaveBeenCalledWith([1, 2]));
    expect(toast.success).toHaveBeenCalled();
  });

  it('bulk unban calls API with selected user IDs', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[3]); // select Charlie (id=3, banned)
    const unbanBtn = screen.getAllByRole('button', { name: 'Unban' })[0];
    await user.click(unbanBtn);
    await waitFor(() => expect(mockBulkUnban).toHaveBeenCalledWith([3]));
  });

  /* ─── BULK DELETE (SUPER ADMIN) ─────────────────────── */

  it('bulk delete calls API for super admin', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());

    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[1]);
    const deleteBtn = screen.getByRole('button', { name: 'Delete Selected' });
    await user.click(deleteBtn);
    expect(window.confirm).toHaveBeenCalled();
    await waitFor(() => expect(mockBulkDelete).toHaveBeenCalledWith([1]));
    expect(toast.success).toHaveBeenCalled();
  });

  /* ─── BULK ASSIGN RATE CODE ─────────────────────────── */

  it('bulk assign rate code calls API', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());

    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[1]); // select Alice

    const rcSelect = screen.getByDisplayValue('Assign Rate Code…');
    await user.selectOptions(rcSelect, '100');

    const applyBtn = screen.getByRole('button', { name: 'Apply Rate Code' });
    await user.click(applyBtn);

    await waitFor(() => expect(mockBulkAssignRateCode).toHaveBeenCalledWith({
      customerIds: [1],
      rateCodeId: 100,
      memberLabel: null,
    }));
    expect(toast.success).toHaveBeenCalled();
  });

  /* ─── CLEAR SELECTION ───────────────────────────────── */

  it('clear button deselects all', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());

    const selectAll = screen.getAllByRole('checkbox')[0];
    await user.click(selectAll);
    expect(screen.getByText(`${MOCK_CUSTOMERS.length} selected`)).toBeInTheDocument();

    const clearBtn = screen.getByRole('button', { name: 'Clear' });
    await user.click(clearBtn);
    expect(screen.queryByText('selected')).not.toBeInTheDocument();
  });

  /* ─── USER DETAIL MODAL ─────────────────────────────── */

  it('opens detail modal on View button click', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());

    const viewBtns = screen.getAllByRole('button', { name: 'View' });
    await user.click(viewBtns[0]); // Alice

    await waitFor(() => {
      // Modal header shows the user name
      const modal = document.querySelector('.auc-detail-modal');
      expect(modal).toBeInTheDocument();
      expect(within(modal).getByRole('heading', { name: /Alice/ })).toBeInTheDocument();
    });
  });

  it('detail modal shows Info tab by default with user data', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => {
      const modal = document.querySelector('.auc-detail-modal');
      expect(within(modal).getByText('alice@test.com')).toBeInTheDocument();
      expect(within(modal).getByText('1234567890')).toBeInTheDocument();
    });
  });

  it('detail modal Pricing tab shows current rate code', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => {
      const modal = document.querySelector('.auc-detail-modal');
      expect(modal).toBeInTheDocument();
    });

    await user.click(screen.getByText('Pricing & Rate Code'));

    await waitFor(() => {
      const modal = document.querySelector('.auc-detail-modal');
      expect(within(modal).getByText('VIP')).toBeInTheDocument();
      expect(within(modal).getByText('2')).toBeInTheDocument(); // totalReservations
    });
  });

  it('detail modal Reservations tab shows booking list', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());
    await user.click(screen.getByText('Reservations'));

    await waitFor(() => {
      expect(screen.getByText('BK-001')).toBeInTheDocument();
      expect(screen.getByText('BK-002')).toBeInTheDocument();
      expect(screen.getByText('Birthday')).toBeInTheDocument();
      expect(screen.getByText('Anniversary')).toBeInTheDocument();
    });
  });

  it('detail modal Rate Code Audit tab shows change history', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());
    await user.click(screen.getByText('Rate Code Audit'));

    await waitFor(() => {
      expect(screen.getByText('ASSIGN')).toBeInTheDocument();
      expect(screen.getByText('REASSIGN')).toBeInTheDocument();
    });
  });

  /* ─── SINGLE USER ACTIONS ───────────────────────────── */

  it('ban/unban single user from detail modal', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());

    const modal = document.querySelector('.auc-detail-modal');
    const banBtn = within(modal).getByRole('button', { name: 'Ban' });
    await user.click(banBtn);
    await waitFor(() => expect(mockBulkBan).toHaveBeenCalledWith([1]));
  });

  it('delete single user from detail modal (super admin)', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());

    const modal = document.querySelector('.auc-detail-modal');
    const deleteBtn = within(modal).getByRole('button', { name: 'Delete' });
    await user.click(deleteBtn);
    await waitFor(() => expect(mockDeleteUser).toHaveBeenCalledWith(1));
  });

  /* ─── INLINE EDIT ───────────────────────────────────── */

  it('inline edit user info in detail modal', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());

    const modal = document.querySelector('.auc-detail-modal');
    const editBtn = within(modal).getByRole('button', { name: 'Edit' });
    await user.click(editBtn);

    // Form fields should be visible
    const firstNameInput = within(modal).getByDisplayValue('Alice');
    expect(firstNameInput).toBeInTheDocument();

    await user.clear(firstNameInput);
    await user.type(firstNameInput, 'AliceUpdated');

    const saveBtn = within(modal).getByRole('button', { name: 'Save' });
    await user.click(saveBtn);

    await waitFor(() => expect(mockAdminUpdateCustomer).toHaveBeenCalledWith(1, expect.objectContaining({
      firstName: 'AliceUpdated',
    })));
    expect(toast.success).toHaveBeenCalled();
  });

  /* ─── CLOSE MODAL ───────────────────────────────────── */

  it('closes detail modal on X button click', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());

    const closeBtn = document.querySelector('.ab-modal-close');
    await user.click(closeBtn);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).not.toBeInTheDocument());
  });

  /* ─── STATUS BADGES ─────────────────────────────────── */

  it('shows Banned badge for inactive customers', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText(/Charlie/)).toBeInTheDocument());
    const badges = screen.getAllByText('Banned');
    expect(badges.length).toBeGreaterThanOrEqual(1);
  });

  /* ─── ERROR HANDLING ────────────────────────────────── */

  it('shows error toast if loading customers fails', async () => {
    mockGetAllCustomers.mockRejectedValueOnce(new Error('Network error'));
    renderPage();
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Failed to load customers'));
  });

  it('shows error toast if bulk ban fails', async () => {
    mockBulkBan.mockRejectedValueOnce({ response: { data: { message: 'Forbidden' } } });
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    const checkboxes = screen.getAllByRole('checkbox');
    await user.click(checkboxes[1]);
    const banBtn = screen.getAllByRole('button', { name: 'Ban' })[0];
    await user.click(banBtn);
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Forbidden'));
  });

  /* ─── EMPTY STATE ───────────────────────────────────── */

  it('shows empty state when no customers exist', async () => {
    mockGetAllCustomers.mockResolvedValue({ data: { data: [] } });
    renderPage();
    await waitFor(() => expect(screen.getByText('No customers found')).toBeInTheDocument());
  });

  /* ─── ADMIN TAB CHECKBOXES (SUPER ADMIN) ────────────── */

  it('shows checkboxes on admin tab for super admin', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getByText('Admins'));

    await waitFor(() => expect(screen.getByText(/SuperAd/)).toBeInTheDocument());
    const checkboxes = screen.getAllByRole('checkbox');
    // select-all + 2 admins
    expect(checkboxes.length).toBe(3);
  });

  /* ─── MEMBER LABEL IN PRICING TAB ──────────────────── */

  it('shows member label in pricing tab', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);

    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());
    await user.click(screen.getByText('Pricing & Rate Code'));

    await waitFor(() => {
      const modal = document.querySelector('.auc-detail-modal');
      expect(within(modal).getByText('Gold Member')).toBeInTheDocument();
      expect(within(modal).getByText('Member Label')).toBeInTheDocument();
    });
  });

  /* ─── SUPER ADMIN SEES EDIT/DELETE, BOTH SEE BAN ───── */

  it('super admin sees Edit and Delete in detail modal', async () => {
    renderPage();
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByText(/Alice/)).toBeInTheDocument());
    await user.click(screen.getAllByRole('button', { name: 'View' })[0]);
    await waitFor(() => expect(document.querySelector('.auc-detail-modal')).toBeInTheDocument());

    const modal = document.querySelector('.auc-detail-modal');
    expect(within(modal).getByRole('button', { name: 'Edit' })).toBeInTheDocument();
    expect(within(modal).getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(within(modal).getByRole('button', { name: /Ban|Unban/ })).toBeInTheDocument();
  });
});
