import { useState, useEffect, useMemo, useCallback } from 'react';
import { useAuth } from '../context/AuthContext';
import { authService, adminService } from '../services/endpoints';
import { parseServerDate } from '../services/timeFormat';
import SEO from '../components/SEO';
import { useConfirm } from '../components/ui/ConfirmProvider';
import { toast } from 'react-toastify';
import DOMPurify from 'dompurify';
import {
  FiUsers, FiUser, FiShield, FiCheck, FiX,
  FiMessageSquare, FiStar, FiCalendar, FiHash,
} from 'react-icons/fi';
import PhoneField, { splitPhone, joinPhone } from '../components/form/PhoneField';
import AddressFields, { EMPTY_ADDRESS } from '../components/form/AddressFields';
import './AdminPages.css';
import './AdminBookings.css';
import './AdminUsersConfig.css';

const PAGE_SIZE = 20;
const REVIEWS_PAGE_SIZE = 8;

const sanitize = (v) => (typeof v === 'string' ? DOMPurify.sanitize(v) : v);

// Tiny inline star display for the table column
function MiniStars({ value }) {
  if (!value || value === 0) return <span className="aau-no-rating">—</span>;
  return (
    <span className="aau-mini-stars" aria-label={`${value} out of 5`}>
      {'★'.repeat(Math.round(value))}{'☆'.repeat(5 - Math.round(value))}
      <span className="aau-mini-val">{value.toFixed(1)}</span>
    </span>
  );
}

// Star rating block for the Reviews tab (filled / half / empty)
function StarRow({ value, max = 5 }) {
  const stars = [];
  for (let i = 1; i <= max; i++) {
    const diff = value - (i - 1);
    let color = 'var(--text-muted)';
    if (diff >= 1) color = '#f5a623';
    else if (diff >= 0.5) color = '#f5a62399';
    stars.push(<span key={i} style={{ color, fontSize: '1.05rem' }}>★</span>);
  }
  return <span aria-label={`${value} out of ${max}`}>{stars}</span>;
}

export default function AdminAllUsers() {
  const { isSuperAdmin } = useAuth();
  const confirm = useConfirm();

  /* ── Lists ────────────────────────────────────────── */
  const [customers, setCustomers] = useState([]);
  const [admins, setAdmins] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('customers');
  const [search, setSearch] = useState('');

  /* ── Selection (bulk) ─────────────────────────────── */
  const [selectedIds, setSelectedIds] = useState(new Set());
  const [bulkBusy, setBulkBusy] = useState(false);

  /* ── Review summary cache ─────────────────────────── */
  const [summaryCache, setSummaryCache] = useState({});

  /* ── Detail modal state ───────────────────────────── */
  const [detailUser, setDetailUser] = useState(null);
  const [detailTab, setDetailTab] = useState('info');

  /* ── Inline edit ──────────────────────────────────── */
  const [editingUser, setEditingUser] = useState(null);
  const [editForm, setEditForm] = useState({});
  const [editSaving, setEditSaving] = useState(false);

  /* ── Reviews tab data ─────────────────────────────── */
  const [reviewSummary, setReviewSummary] = useState(null);
  const [reviewSummaryLoading, setReviewSummaryLoading] = useState(false);
  const [reviews, setReviews] = useState([]);
  const [reviewsLoading, setReviewsLoading] = useState(false);
  const [reviewsPage, setReviewsPage] = useState(0);
  const [reviewsTotalPages, setReviewsTotalPages] = useState(0);

  /* ─── Load lists ────────────────────────────────────── */
  const loadAll = useCallback(() => {
    setLoading(true);
    Promise.allSettled([
      authService.getAllCustomers(),
      isSuperAdmin ? authService.getAllAdmins() : Promise.resolve({ data: { data: [] } }),
    ]).then(([custRes, adminRes]) => {
      const custData = custRes.status === 'fulfilled'
        ? (custRes.value.data.data?.content || custRes.value.data.data || [])
        : [];
      setCustomers(Array.isArray(custData) ? custData : []);
      const adminPayload = adminRes.status === 'fulfilled' ? adminRes.value.data?.data : null;
      const adminData = Array.isArray(adminPayload)
        ? adminPayload
        : (Array.isArray(adminPayload?.content) ? adminPayload.content : []);
      setAdmins(adminData);
    }).finally(() => setLoading(false));
  }, [isSuperAdmin]);

  useEffect(() => { loadAll(); }, [loadAll]);

  /* ─── Filtering / pagination ────────────────────────── */
  const lowerSearch = search.toLowerCase();
  const activeList = tab === 'customers' ? customers : admins;
  const filtered = useMemo(() => {
    if (!lowerSearch) return activeList;
    return activeList.filter((u) => {
      const name = `${u.firstName || ''} ${u.lastName || ''}`.toLowerCase();
      return name.includes(lowerSearch)
        || (u.email || '').toLowerCase().includes(lowerSearch)
        || (u.phone || '').includes(lowerSearch);
    });
  }, [activeList, lowerSearch]);

  const [page, setPage] = useState(0);
  useEffect(() => { setPage(0); setSelectedIds(new Set()); }, [tab, search]);
  const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
  const pageItems = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

  /* ─── Pre-fetch review summaries for visible customers ── */
  useEffect(() => {
    if (tab !== 'customers') return;
    pageItems.forEach((u) => {
      if (summaryCache[u.id] !== undefined) return;
      setSummaryCache((prev) => ({ ...prev, [u.id]: null }));
      adminService.getCustomerReviewSummary(u.id)
        .then((res) => {
          const d = res.data?.data ?? res.data;
          setSummaryCache((prev) => ({ ...prev, [u.id]: d }));
        })
        .catch(() => { /* leave null */ });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, page, pageItems.length]);

  /* ─── Selection helpers ─────────────────────────────── */
  const toggleSelect = (id) => setSelectedIds((prev) => {
    const n = new Set(prev);
    n.has(id) ? n.delete(id) : n.add(id);
    return n;
  });
  const toggleSelectAll = () => {
    if (selectedIds.size === filtered.length) setSelectedIds(new Set());
    else setSelectedIds(new Set(filtered.map((u) => u.id)));
  };

  /* ─── Bulk actions ──────────────────────────────────── */
  const handleBulk = async (kind) => {
    if (!selectedIds.size) return;
    const verb = kind === 'ban' ? 'Ban' : kind === 'unban' ? 'Unban' : 'Delete';
    const isDelete = kind === 'delete';
    const ok = await confirm({
      title: `${verb} ${selectedIds.size} user${selectedIds.size === 1 ? '' : 's'}?`,
      message: isDelete
        ? 'This permanently removes the selected accounts. The action is irreversible and audited.'
        : kind === 'ban'
          ? 'Banned users cannot log in. They will be signed out from any active sessions.'
          : 'Unbanning restores login access. The users will be able to sign in again immediately.',
      confirmLabel: verb,
      variant: isDelete || kind === 'ban' ? 'danger' : 'primary',
    });
    if (!ok) return;
    setBulkBusy(true);
    try {
      const ids = [...selectedIds];
      if (kind === 'ban') await authService.bulkBan(ids);
      else if (kind === 'unban') await authService.bulkUnban(ids);
      else await authService.bulkDelete(ids);
      toast.success(`${ids.length} user(s) ${kind === 'ban' ? 'banned' : kind === 'unban' ? 'unbanned' : 'deleted'}`);
      setSelectedIds(new Set());
      loadAll();
    } catch (e) {
      toast.error(e.response?.data?.message || `${verb} failed`);
    } finally {
      setBulkBusy(false);
    }
  };

  /* ─── Single-user actions ───────────────────────────── */
  const handleToggleBan = async (u) => {
    const action = u.active ? 'ban' : 'unban';
    const name = `${u.firstName || ''} ${u.lastName || ''}`.trim() || `user #${u.id}`;
    const ok = await confirm({
      title: `${action === 'ban' ? 'Ban' : 'Unban'} ${name}?`,
      message: action === 'ban'
        ? 'Banned users cannot log in. Active sessions will be revoked.'
        : 'Unbanning restores login access immediately.',
      confirmLabel: action === 'ban' ? 'Ban user' : 'Unban user',
      variant: action === 'ban' ? 'danger' : 'primary',
    });
    if (!ok) return;
    try {
      if (action === 'ban') await authService.bulkBan([u.id]);
      else await authService.bulkUnban([u.id]);
      toast.success(`User ${action === 'ban' ? 'banned' : 'unbanned'}`);
      loadAll();
      setDetailUser((prev) => prev && prev.id === u.id ? { ...prev, active: !prev.active } : prev);
    } catch (e) {
      toast.error(e.response?.data?.message || 'Action failed');
    }
  };

  const handleDelete = async (u) => {
    const name = `${u.firstName || ''} ${u.lastName || ''}`.trim() || `user #${u.id}`;
    const ok = await confirm({
      title: `Delete ${name}?`,
      message: 'This permanently removes the account. The action is irreversible and audited.',
      confirmLabel: 'Delete user',
      variant: 'danger',
    });
    if (!ok) return;
    try {
      await authService.deleteUser(u.id);
      toast.success('User deleted');
      closeDetail();
      loadAll();
    } catch (e) {
      toast.error(e.response?.data?.message || 'Delete failed');
    }
  };

  /* ─── Inline edit ───────────────────────────────────── */
  const startEdit = (u) => {
    setEditingUser(u.id);
    setEditForm({
      firstName: u.firstName || '',
      lastName: u.lastName || '',
      email: u.email || '',
      phone: joinPhone(u.phone, u.phoneCountryCode),
      address: {
        street: u.address?.street || '',
        city: u.address?.city || '',
        state: u.address?.state || '',
        country: u.address?.country || '',
        postalCode: u.address?.postalCode || '',
      },
    });
  };

  const saveEdit = async () => {
    if (editSaving || !detailUser) return;
    setEditSaving(true);
    try {
      const phoneParts = splitPhone(editForm.phone);
      const payload = {
        firstName: editForm.firstName,
        lastName: editForm.lastName,
        email: editForm.email,
        phone: phoneParts.phone,
        phoneCountryCode: phoneParts.phoneCountryCode,
        address: editForm.address,
      };
      const isAdminUser = detailUser.role === 'ADMIN' || detailUser.role === 'SUPER_ADMIN';
      if (isAdminUser) await authService.updateAdmin(editingUser, payload);
      else await authService.adminUpdateCustomer(editingUser, payload);
      toast.success('User updated');
      setEditingUser(null);
      loadAll();
      // Refresh the detail user from server
      try {
        const res = await authService.getCustomerById(editingUser);
        setDetailUser(res.data?.data || res.data);
      } catch { /* ignore */ }
    } catch (e) {
      toast.error(e.response?.data?.message || 'Update failed');
    } finally {
      setEditSaving(false);
    }
  };

  /* ─── Detail modal open / close ─────────────────────── */
  const openDetail = useCallback((u, initialTab = 'info') => {
    setDetailUser(u);
    setDetailTab(initialTab);
    setEditingUser(null);
    setReviewSummary(null);
    setReviews([]);
    setReviewsPage(0);
    setReviewsTotalPages(0);
  }, []);

  const closeDetail = () => {
    setDetailUser(null);
    setEditingUser(null);
  };

  /* ─── Reviews tab loaders ───────────────────────────── */
  useEffect(() => {
    if (!detailUser || detailTab !== 'reviews') return;
    const isAdminUser = detailUser.role === 'ADMIN' || detailUser.role === 'SUPER_ADMIN';
    if (isAdminUser) return;

    setReviewSummaryLoading(true);
    adminService.getCustomerReviewSummary(detailUser.id)
      .then((res) => setReviewSummary(res.data?.data ?? res.data))
      .catch(() => setReviewSummary(null))
      .finally(() => setReviewSummaryLoading(false));
  }, [detailUser, detailTab]);

  useEffect(() => {
    if (!detailUser || detailTab !== 'reviews') return;
    const isAdminUser = detailUser.role === 'ADMIN' || detailUser.role === 'SUPER_ADMIN';
    if (isAdminUser) return;

    setReviewsLoading(true);
    adminService.getCustomerAdminReviews(detailUser.id, reviewsPage, REVIEWS_PAGE_SIZE)
      .then((res) => {
        const d = res.data?.data ?? res.data;
        setReviews(d?.content ?? []);
        setReviewsTotalPages(d?.totalPages ?? 0);
      })
      .catch(() => setReviews([]))
      .finally(() => setReviewsLoading(false));
  }, [detailUser, detailTab, reviewsPage]);

  /* ─── ESC key closes modal ──────────────────────────── */
  useEffect(() => {
    if (!detailUser) return;
    const onKey = (e) => { if (e.key === 'Escape') closeDetail(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [detailUser]);

  /* ═════════════════════════════════════════════════════
     Render
     ═════════════════════════════════════════════════════ */

  if (loading) {
    return (
      <div className="container adm-shell">
        <SEO title="All Users" description="View all platform users." />
        <div className="adm-header">
          <div className="adm-header-copy">
            <span className="adm-kicker"><FiUsers /> Users</span>
            <h1>Platform Users</h1>
          </div>
        </div>
        <div className="loading"><div className="spinner"></div></div>
      </div>
    );
  }

  const renderBulkToolbar = () => {
    if (!selectedIds.size) return null;
    return (
      <div className="auc-bulk-toolbar">
        <span className="auc-bulk-count">{selectedIds.size} selected</span>
        <button className="btn btn-sm btn-secondary" disabled={bulkBusy} onClick={() => handleBulk('ban')}>Ban</button>
        <button className="btn btn-sm btn-secondary" disabled={bulkBusy} onClick={() => handleBulk('unban')}>Unban</button>
        {isSuperAdmin && (
          <button className="btn btn-sm btn-danger" disabled={bulkBusy} onClick={() => handleBulk('delete')}>Delete Selected</button>
        )}
        <button className="btn btn-sm btn-secondary auc-bulk-clear" onClick={() => setSelectedIds(new Set())}>Clear</button>
      </div>
    );
  };

  return (
    <div className="container adm-shell">
      <SEO title="All Users" description="View all platform users across all venues." />

      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiUsers /> Users</span>
          <h1>Platform Users</h1>
          <p className="adm-form-intro">
            All users across every venue. {customers.length} customer{customers.length !== 1 ? 's' : ''}, {admins.length} admin{admins.length !== 1 ? 's' : ''}.
          </p>
        </div>
      </div>

      <div className="adm-toolbar">
        <div className="adm-tabs">
          <button className={`adm-tab${tab === 'customers' ? ' active' : ''}`} onClick={() => setTab('customers')}>
            <FiUser /> Customers ({customers.length})
          </button>
          {isSuperAdmin && (
            <button className={`adm-tab${tab === 'admins' ? ' active' : ''}`} onClick={() => setTab('admins')}>
              <FiShield /> Admins ({admins.length})
            </button>
          )}
        </div>
        <div className="adm-toolbar-right">
          <input
            type="text"
            className="ab-input ab-input-search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={`Search ${tab} by name, email, or phone...`}
          />
        </div>
      </div>

      {tab === 'customers' && (
        <p className="aau-table-hint">
          <FiMessageSquare aria-hidden="true" />
          Click any row to view full profile, edit, ban, or review history.
        </p>
      )}

      {(isSuperAdmin || tab === 'customers') && renderBulkToolbar()}

      {pageItems.length === 0 ? (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiUsers /></span>
          <h3>No {tab} found{search ? ' matching your search' : ''}</h3>
        </div>
      ) : (
        <div className="adm-table-wrap">
          <table className="adm-table auc-table">
            <thead>
              <tr>
                {(isSuperAdmin || tab === 'customers') && (
                  <th className="auc-th-check">
                    <input
                      type="checkbox"
                      checked={filtered.length > 0 && selectedIds.size === filtered.length}
                      onChange={toggleSelectAll}
                      aria-label="Select all"
                    />
                  </th>
                )}
                <th>Name</th>
                <th>Email</th>
                <th>Phone</th>
                {tab === 'admins' && <th>Role</th>}
                <th>Status</th>
                {tab === 'customers' && (
                  <th className="aau-th-rating">
                    <FiStar aria-hidden="true" /> Admin Rating
                  </th>
                )}
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map((u) => {
                const summary = summaryCache[u.id];
                const hasReviews = summary && summary.adminReviewCount > 0;
                const isSelected = selectedIds.has(u.id);
                const showCheckbox = isSuperAdmin || tab === 'customers';
                return (
                  <tr
                    key={u.id}
                    className={isSelected ? 'auc-row-selected aau-row-customer' : 'aau-row-customer'}
                  >
                    {showCheckbox && (
                      <td className="auc-td-check" onClick={(e) => e.stopPropagation()}>
                        <input
                          type="checkbox"
                          checked={isSelected}
                          onChange={() => toggleSelect(u.id)}
                          aria-label={`Select ${u.firstName || ''} ${u.lastName || ''}`}
                        />
                      </td>
                    )}
                    <td className="highlight" onClick={() => openDetail(u, 'info')} style={{ cursor: 'pointer' }}>
                      {sanitize([u.firstName, u.lastName].filter(Boolean).join(' ') || '—')}
                    </td>
                    <td onClick={() => openDetail(u, 'info')} style={{ cursor: 'pointer' }}>{sanitize(u.email || '—')}</td>
                    <td onClick={() => openDetail(u, 'info')} style={{ cursor: 'pointer' }}>{sanitize(u.phone || '—')}</td>
                    {tab === 'admins' && (
                      <td onClick={() => openDetail(u, 'info')} style={{ cursor: 'pointer' }}>
                        <span className="adm-badge adm-badge-info">{u.role === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin'}</span>
                      </td>
                    )}
                    <td onClick={() => openDetail(u, 'info')} style={{ cursor: 'pointer' }}>
                      {u.active !== false
                        ? <span className="adm-badge adm-badge-active"><FiCheck /> Active</span>
                        : <span className="adm-badge adm-badge-inactive"><FiX /> Inactive</span>}
                    </td>
                    {tab === 'customers' && (
                      <td className="aau-td-rating">
                        {summary === undefined || summary === null ? (
                          summary === undefined
                            ? <span className="aau-rating-loading" aria-label="Loading rating" />
                            : <span className="aau-no-rating">No reviews</span>
                        ) : hasReviews ? (
                          <button
                            type="button"
                            className="aau-rating-pill"
                            onClick={(e) => { e.stopPropagation(); openDetail(u, 'reviews'); }}
                            aria-label={`${summary.avgAdminRating} stars — view reviews`}
                          >
                            <MiniStars value={summary.avgAdminRating} />
                            <span className="aau-rating-count">({summary.adminReviewCount})</span>
                          </button>
                        ) : (
                          <span className="aau-no-rating">No reviews</span>
                        )}
                      </td>
                    )}
                    <td onClick={(e) => e.stopPropagation()}>
                      <button
                        type="button"
                        className="btn btn-sm btn-secondary"
                        onClick={() => openDetail(u, 'info')}
                      >
                        View
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'center', marginTop: '1rem' }}>
          <button className="btn btn-sm btn-secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</button>
          <span style={{ alignSelf: 'center', fontSize: '0.875rem', color: 'var(--text-secondary)' }}>Page {page + 1} of {totalPages}</span>
          <button className="btn btn-sm btn-secondary" disabled={page >= totalPages - 1} onClick={() => setPage((p) => p + 1)}>Next</button>
        </div>
      )}

      {/* ── User Detail Modal ────────────────────────────────── */}
      {detailUser && (() => {
        const u = detailUser;
        const isAdminUser = u.role === 'ADMIN' || u.role === 'SUPER_ADMIN';
        const TABS = isAdminUser
          ? [{ key: 'info', label: 'Info' }]
          : [{ key: 'info', label: 'Info' }, { key: 'reviews', label: 'Reviews' }];

        return (
          <div className="ab-modal-overlay" onClick={closeDetail}>
            <div className="ab-modal-card adm-flow-card auc-detail-modal" onClick={(e) => e.stopPropagation()}>
              {/* Header */}
              <div className="ab-modal-header">
                <h3>{sanitize(u.firstName)} {sanitize(u.lastName || '')}</h3>
                <button className="ab-modal-close" onClick={closeDetail} aria-label="Close">×</button>
              </div>

              {/* Tabs */}
              {TABS.length > 1 && (
                <div className="ab-detail-tabs">
                  {TABS.map((t) => (
                    <button
                      key={t.key}
                      className={`ab-detail-tab ${detailTab === t.key ? 'active' : ''}`}
                      onClick={() => setDetailTab(t.key)}
                    >
                      {t.label}
                    </button>
                  ))}
                </div>
              )}

              {/* Body */}
              <div className="ab-modal-body">
                {/* Info tab */}
                {detailTab === 'info' && (
                  <>
                    {isSuperAdmin && editingUser === u.id ? (
                      <div className="auc-edit-form">
                        <label>First Name
                          <input className="ab-input" value={editForm.firstName}
                            onChange={(e) => setEditForm((p) => ({ ...p, firstName: e.target.value }))} />
                        </label>
                        <label>Last Name
                          <input className="ab-input" value={editForm.lastName}
                            onChange={(e) => setEditForm((p) => ({ ...p, lastName: e.target.value }))} />
                        </label>
                        <label>Email
                          <input className="ab-input" type="email" value={editForm.email}
                            onChange={(e) => setEditForm((p) => ({ ...p, email: e.target.value }))} />
                        </label>
                        <label>Phone
                          <PhoneField
                            value={editForm.phone}
                            onChange={(val) => setEditForm((p) => ({ ...p, phone: val || '' }))}
                          />
                        </label>
                        <AddressFields
                          value={editForm.address || EMPTY_ADDRESS}
                          onChange={(addr) => setEditForm((p) => ({ ...p, address: addr }))}
                        />
                        <div className="ab-action-row">
                          <button className="btn btn-sm btn-primary" onClick={saveEdit} disabled={editSaving}>
                            {editSaving ? 'Saving…' : 'Save'}
                          </button>
                          <button className="btn btn-sm btn-secondary" onClick={() => setEditingUser(null)}>Cancel</button>
                        </div>
                      </div>
                    ) : (
                      <>
                        <div className="ab-detail-row"><span className="ab-detail-label">ID</span><span>{u.id}</span></div>
                        <div className="ab-detail-row"><span className="ab-detail-label">Name</span><span>{sanitize(u.firstName)} {sanitize(u.lastName || '')}</span></div>
                        <div className="ab-detail-row"><span className="ab-detail-label">Email</span><span>{sanitize(u.email)}</span></div>
                        <div className="ab-detail-row">
                          <span className="ab-detail-label">Phone</span>
                          <span>{u.phone ? `${u.phoneCountryCode || ''} ${sanitize(u.phone)}`.trim() : '—'}</span>
                        </div>
                        {u.role && (
                          <div className="ab-detail-row">
                            <span className="ab-detail-label">Role</span>
                            <span className="adm-badge adm-badge-info">{u.role}</span>
                          </div>
                        )}
                        <div className="ab-detail-row">
                          <span className="ab-detail-label">Status</span>
                          <span className={u.active ? 'adm-badge adm-badge-active' : 'adm-badge adm-badge-inactive'}>
                            {u.active ? 'Active' : 'Banned'}
                          </span>
                        </div>
                        {u.address && (u.address.city || u.address.country) && (
                          <div className="ab-detail-row">
                            <span className="ab-detail-label">Address</span>
                            <span>
                              {[u.address.street, u.address.city, u.address.state, u.address.country, u.address.postalCode]
                                .filter(Boolean).map(sanitize).join(', ')}
                            </span>
                          </div>
                        )}

                        <div className="ab-action-row">
                          {isSuperAdmin && (
                            <button className="btn btn-sm btn-secondary" onClick={() => startEdit(u)}>Edit</button>
                          )}
                          <button className="btn btn-sm btn-secondary" onClick={() => handleToggleBan(u)}>
                            {u.active ? 'Ban' : 'Unban'}
                          </button>
                          {isSuperAdmin && (
                            <button className="btn btn-sm btn-danger" onClick={() => handleDelete(u)}>Delete</button>
                          )}
                        </div>
                      </>
                    )}
                  </>
                )}

                {/* Reviews tab (customers only) */}
                {detailTab === 'reviews' && !isAdminUser && (
                  <>
                    {/* Summary */}
                    {reviewSummaryLoading ? (
                      <p className="auc-loading">Loading review summary…</p>
                    ) : reviewSummary ? (
                      <div style={{
                        display: 'flex', gap: '1rem', alignItems: 'center',
                        padding: '0.85rem 1rem', marginBottom: '1rem',
                        border: '1px solid var(--border)', borderRadius: 'var(--radius-sm)',
                        background: 'linear-gradient(180deg, rgba(var(--primary-rgb),0.04), transparent 65%)',
                      }}>
                        <div style={{ fontSize: '2rem', fontWeight: 700 }}>
                          {reviewSummary.avgAdminRating ? reviewSummary.avgAdminRating.toFixed(1) : '—'}
                        </div>
                        <div>
                          <StarRow value={reviewSummary.avgAdminRating || 0} />
                          <div style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
                            {reviewSummary.adminReviewCount || 0} admin review{reviewSummary.adminReviewCount === 1 ? '' : 's'}
                            {reviewSummary.customerReviewCount > 0 && (
                              <> · {reviewSummary.customerReviewCount} written by customer</>
                            )}
                          </div>
                        </div>
                      </div>
                    ) : (
                      <p className="auc-empty-hint">No review data available.</p>
                    )}

                    {/* Reviews list */}
                    {reviewsLoading ? (
                      <p className="auc-loading">Loading reviews…</p>
                    ) : reviews.length === 0 ? (
                      <p className="auc-empty-hint">
                        <FiMessageSquare aria-hidden="true" /> No admin reviews submitted yet.
                      </p>
                    ) : (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                        {reviews.map((r, idx) => {
                          const dateStr = r.createdAt
                            ? (parseServerDate(r.createdAt)?.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) || '—')
                            : '—';
                          return (
                            <article key={r.id || idx} style={{
                              border: '1px solid var(--border)',
                              borderRadius: 'var(--radius-sm)',
                              padding: '0.75rem 0.9rem',
                            }}>
                              <header style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.4rem', gap: '0.5rem', flexWrap: 'wrap' }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                  <StarRow value={r.rating || 0} />
                                  <span className="adm-badge adm-badge-info">
                                    {r.reviewerRole === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin'}
                                  </span>
                                </div>
                                <time style={{ fontSize: '0.78rem', color: 'var(--text-muted)', display: 'inline-flex', alignItems: 'center', gap: '0.3rem' }}>
                                  <FiCalendar aria-hidden="true" /> {dateStr}
                                </time>
                              </header>
                              {r.comment ? (
                                <p style={{ margin: '0.25rem 0', whiteSpace: 'pre-wrap' }}>{sanitize(r.comment)}</p>
                              ) : (
                                <p style={{ margin: '0.25rem 0', color: 'var(--text-muted)', fontStyle: 'italic' }}>No comment left.</p>
                              )}
                              <footer style={{ fontSize: '0.78rem', color: 'var(--text-muted)', display: 'flex', gap: '0.75rem', flexWrap: 'wrap' }}>
                                {r.bookingRef && (
                                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.25rem' }}>
                                    <FiHash aria-hidden="true" /> {sanitize(r.bookingRef)}
                                  </span>
                                )}
                                {r.eventTypeName && <span>{sanitize(r.eventTypeName)}</span>}
                              </footer>
                            </article>
                          );
                        })}
                      </div>
                    )}

                    {/* Reviews pagination */}
                    {reviewsTotalPages > 1 && (
                      <div style={{ display: 'flex', gap: '0.5rem', justifyContent: 'center', marginTop: '0.75rem' }}>
                        <button className="btn btn-sm btn-secondary"
                          disabled={reviewsPage === 0}
                          onClick={() => setReviewsPage((p) => p - 1)}>Previous</button>
                        <span style={{ alignSelf: 'center', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                          Page {reviewsPage + 1} of {reviewsTotalPages}
                        </span>
                        <button className="btn btn-sm btn-secondary"
                          disabled={reviewsPage >= reviewsTotalPages - 1}
                          onClick={() => setReviewsPage((p) => p + 1)}>Next</button>
                      </div>
                    )}
                  </>
                )}
              </div>
            </div>
          </div>
        );
      })()}
    </div>
  );
}
