import { useState, useEffect, useCallback, useMemo } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { authService, adminService } from '../services/endpoints';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import DOMPurify from 'dompurify';
import './AdminPages.css';
import './AdminBookings.css';
import './AdminUsersConfig.css';

/* ─── Constants ────────────────────────────────────────── */
const MAIN_TABS = [
  { key: 'users', label: 'Customers' },
  { key: 'admins', label: 'Admins' },
  { key: 'config', label: 'Rate Codes' },
];

const USER_DETAIL_TABS = [
  { key: 'info', label: 'Info' },
  { key: 'pricing', label: 'Pricing & Rate Code' },
  { key: 'reservations', label: 'Reservations' },
  { key: 'audit', label: 'Rate Code Audit' },
];

const STATUS_BADGE = {
  PENDING: 'adm-badge adm-badge-info',
  CONFIRMED: 'adm-badge adm-badge-active',
  CHECKED_IN: 'adm-badge adm-badge-active',
  COMPLETED: 'adm-badge adm-badge-active',
  CANCELLED: 'adm-badge adm-badge-inactive',
  NO_SHOW: 'adm-badge adm-badge-inactive',
};

/* ─── Helpers ──────────────────────────────────────────── */
const sanitize = (v) => (typeof v === 'string' ? DOMPurify.sanitize(v) : v);
const fmtDate = (d) => (d ? new Date(d).toLocaleDateString() : '—');
const fmtDateTime = (d) => (d ? new Date(d).toLocaleString() : '—');
const fmtMoney = (n) => (n != null ? `₹${Number(n).toLocaleString('en-IN', { minimumFractionDigits: 2 })}` : '—');

/* ═══════════════════════════════════════════════════════════
   AdminUsersConfig – Users, Admins & Config management
   ═══════════════════════════════════════════════════════════ */
export default function AdminUsersConfig() {
  const { userId: routeUserId } = useParams();
  const navigate = useNavigate();
  const { user: me } = useAuth();
  const isSuperAdmin = me?.role === 'SUPER_ADMIN';

  /* ── Main tab ───────────────────────────────────────── */
  const [mainTab, setMainTab] = useState('users');

  /* ── Customers state ────────────────────────────────── */
  const [customers, setCustomers] = useState([]);
  const [custLoading, setCustLoading] = useState(false);
  const [custSearch, setCustSearch] = useState('');
  const [selectedCustIds, setSelectedCustIds] = useState(new Set());

  /* ── Admins state ───────────────────────────────────── */
  const [admins, setAdmins] = useState([]);
  const [adminLoading, setAdminLoading] = useState(false);
  const [adminSearch, setAdminSearch] = useState('');
  const [selectedAdminIds, setSelectedAdminIds] = useState(new Set());

  /* ── Rate codes state (config tab) ──────────────────── */
  const [rateCodes, setRateCodes] = useState([]);
  const [rcLoading, setRcLoading] = useState(false);

  /* ── User detail modal ──────────────────────────────── */
  const [detailUser, setDetailUser] = useState(null);
  const [detailTab, setDetailTab] = useState('info');
  const [customerDetail, setCustomerDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);

  /* ── Bulk assign rate code ──────────────────────────── */
  const [bulkRateCodeId, setBulkRateCodeId] = useState('');
  const [bulkMemberLabel, setBulkMemberLabel] = useState('');
  const [bulkBusy, setBulkBusy] = useState(false);

  /* ── Edit user inline ───────────────────────────────── */
  const [editingUser, setEditingUser] = useState(null);
  const [editForm, setEditForm] = useState({});
  const [editSaving, setEditSaving] = useState(false);
  const [editingMemberLabel, setEditingMemberLabel] = useState(false);
  const [memberLabelValue, setMemberLabelValue] = useState('');

  /* ═══ Data Loading ══════════════════════════════════════ */
  const loadCustomers = useCallback(async () => {
    setCustLoading(true);
    try {
      const res = await authService.getAllCustomers();
      const payload = res.data?.data;
      setCustomers(Array.isArray(payload) ? payload : (payload?.content ?? []));
    } catch { toast.error('Failed to load customers'); }
    finally { setCustLoading(false); }
  }, []);

  const loadAdmins = useCallback(async () => {
    setAdminLoading(true);
    try {
      const res = await authService.getAllAdmins();
      setAdmins(res.data?.data || []);
    } catch { toast.error('Failed to load admins'); }
    finally { setAdminLoading(false); }
  }, []);

  const loadRateCodes = useCallback(async () => {
    setRcLoading(true);
    try {
      const res = await adminService.getRateCodes();
      setRateCodes(res.data?.data || []);
    } catch { toast.error('Failed to load rate codes'); }
    finally { setRcLoading(false); }
  }, []);

  useEffect(() => {
    loadCustomers();
    if (isSuperAdmin) loadAdmins();
    loadRateCodes();
  }, [loadCustomers, loadAdmins, loadRateCodes, isSuperAdmin]);

  /* Deep-link to a user */
  useEffect(() => {
    if (routeUserId && customers.length) {
      const cust = customers.find(c => String(c.id) === String(routeUserId));
      if (cust) openDetail(cust);
    }
  }, [routeUserId, customers]); // eslint-disable-line react-hooks/exhaustive-deps

  /* ═══ Customer detail loader ════════════════════════════ */
  const loadCustomerDetail = useCallback(async (customerId) => {
    setDetailLoading(true);
    setCustomerDetail(null);
    try {
      const res = await adminService.getCustomerDetail(customerId);
      setCustomerDetail(res.data?.data || res.data);
    } catch { toast.error('Failed to load customer details'); }
    finally { setDetailLoading(false); }
  }, []);

  const openDetail = useCallback((user) => {
    setDetailUser(user);
    setDetailTab('info');
    setEditingUser(null);
    const isAdmin = user.role === 'ADMIN' || user.role === 'SUPER_ADMIN';
    if (!isAdmin) loadCustomerDetail(user.id);
    else setCustomerDetail(null);
  }, [loadCustomerDetail]);

  const closeDetail = useCallback(() => {
    setDetailUser(null);
    setCustomerDetail(null);
    setEditingUser(null);
    if (routeUserId) navigate('/admin/users-config', { replace: true });
  }, [routeUserId, navigate]);

  /* ═══ Filtered lists ════════════════════════════════════ */
  const filteredCustomers = useMemo(() => {
    if (!custSearch.trim()) return customers;
    const q = custSearch.toLowerCase();
    return customers.filter(c =>
      (c.firstName || '').toLowerCase().includes(q) ||
      (c.lastName || '').toLowerCase().includes(q) ||
      (c.email || '').toLowerCase().includes(q) ||
      (c.phone || '').includes(q)
    );
  }, [customers, custSearch]);

  const filteredAdmins = useMemo(() => {
    if (!adminSearch.trim()) return admins;
    const q = adminSearch.toLowerCase();
    return admins.filter(a =>
      (a.firstName || '').toLowerCase().includes(q) ||
      (a.lastName || '').toLowerCase().includes(q) ||
      (a.email || '').toLowerCase().includes(q)
    );
  }, [admins, adminSearch]);

  /* ═══ Selection helpers ═════════════════════════════════ */
  const toggleCust = (id) => setSelectedCustIds(prev => {
    const n = new Set(prev);
    n.has(id) ? n.delete(id) : n.add(id);
    return n;
  });

  const toggleAllCust = () => {
    if (selectedCustIds.size === filteredCustomers.length) {
      setSelectedCustIds(new Set());
    } else {
      setSelectedCustIds(new Set(filteredCustomers.map(c => c.id)));
    }
  };

  const toggleAdmin = (id) => setSelectedAdminIds(prev => {
    const n = new Set(prev);
    n.has(id) ? n.delete(id) : n.add(id);
    return n;
  });

  const toggleAllAdmin = () => {
    if (selectedAdminIds.size === filteredAdmins.length) {
      setSelectedAdminIds(new Set());
    } else {
      setSelectedAdminIds(new Set(filteredAdmins.map(a => a.id)));
    }
  };

  /* ═══ Bulk Actions ══════════════════════════════════════ */
  const handleBulkBan = async (ids, type) => {
    if (!ids.size) return;
    const action = type === 'ban' ? 'ban' : 'unban';
    if (!window.confirm(`${action === 'ban' ? 'Ban' : 'Unban'} ${ids.size} user(s)?`)) return;
    setBulkBusy(true);
    try {
      const arr = [...ids];
      if (action === 'ban') await authService.bulkBan(arr);
      else await authService.bulkUnban(arr);
      toast.success(`${ids.size} user(s) ${action === 'ban' ? 'banned' : 'unbanned'}`);
      setSelectedCustIds(new Set());
      setSelectedAdminIds(new Set());
      loadCustomers();
      if (isSuperAdmin) loadAdmins();
    } catch (e) { toast.error(e.response?.data?.message || `Failed to ${action}`); }
    finally { setBulkBusy(false); }
  };

  const handleBulkDelete = async (ids) => {
    if (!ids.size) return;
    if (!isSuperAdmin) { toast.error('Only super admins can bulk delete'); return; }
    if (!window.confirm(`Permanently delete ${ids.size} user(s)? This cannot be undone.`)) return;
    setBulkBusy(true);
    try {
      await authService.bulkDelete([...ids]);
      toast.success(`${ids.size} user(s) deleted`);
      setSelectedCustIds(new Set());
      setSelectedAdminIds(new Set());
      loadCustomers();
      if (isSuperAdmin) loadAdmins();
    } catch (e) { toast.error(e.response?.data?.message || 'Delete failed'); }
    finally { setBulkBusy(false); }
  };

  const handleBulkAssignRateCode = async () => {
    if (!selectedCustIds.size || !bulkRateCodeId) {
      toast.warn('Select customers and a rate code');
      return;
    }
    setBulkBusy(true);
    try {
      await adminService.bulkAssignRateCode({
        customerIds: [...selectedCustIds],
        rateCodeId: Number(bulkRateCodeId),
        memberLabel: bulkMemberLabel.trim() || null,
      });
      toast.success(`Rate code assigned to ${selectedCustIds.size} customer(s)`);
      setSelectedCustIds(new Set());
      setBulkRateCodeId('');
      setBulkMemberLabel('');
    } catch (e) { toast.error(e.response?.data?.message || 'Bulk assign failed'); }
    finally { setBulkBusy(false); }
  };

  /* ═══ Single user actions ═══════════════════════════════ */
  const handleToggleBan = async (user) => {
    const action = user.active ? 'ban' : 'unban';
    if (!window.confirm(`${action === 'ban' ? 'Ban' : 'Unban'} ${user.firstName} ${user.lastName || ''}?`)) return;
    try {
      if (action === 'ban') await authService.bulkBan([user.id]);
      else await authService.bulkUnban([user.id]);
      toast.success(`User ${action === 'ban' ? 'banned' : 'unbanned'}`);
      loadCustomers();
      if (isSuperAdmin) loadAdmins();
      if (detailUser?.id === user.id) {
        setDetailUser(prev => prev ? { ...prev, active: !prev.active } : prev);
      }
    } catch (e) { toast.error(e.response?.data?.message || 'Failed'); }
  };

  const handleDeleteUser = async (user) => {
    if (!window.confirm(`Delete ${user.firstName} ${user.lastName || ''}? This cannot be undone.`)) return;
    try {
      await authService.deleteUser(user.id);
      toast.success('User deleted');
      closeDetail();
      loadCustomers();
      if (isSuperAdmin) loadAdmins();
    } catch (e) { toast.error(e.response?.data?.message || 'Delete failed'); }
  };

  /* ── Inline edit ────────────────────────────────────── */
  const startEdit = (user) => {
    setEditingUser(user.id);
    setEditForm({
      firstName: user.firstName || '',
      lastName: user.lastName || '',
      email: user.email || '',
      phone: user.phone || '',
    });
  };

  const saveEdit = async () => {
    if (editSaving) return;
    setEditSaving(true);
    try {
      if (detailUser?.role === 'ADMIN' || detailUser?.role === 'SUPER_ADMIN') {
        await authService.updateAdmin(editingUser, editForm);
      } else {
        await authService.adminUpdateCustomer(editingUser, editForm);
      }
      toast.success('User updated');
      setEditingUser(null);
      loadCustomers();
      if (isSuperAdmin) loadAdmins();
      // refresh detail
      const res = await authService.getCustomerById(editingUser);
      setDetailUser(res.data?.data || res.data);
    } catch (e) { toast.error(e.response?.data?.message || 'Update failed'); } finally { setEditSaving(false); }
  };

  const saveMemberLabel = async (customerId) => {
    try {
      await adminService.updateMemberLabel(customerId, memberLabelValue.trim() || null);
      toast.success('Member label updated');
      setEditingMemberLabel(false);
      loadCustomerDetail(customerId);
    } catch (e) { toast.error(e.response?.data?.message || 'Failed to update label'); }
  };

  /* ═══════════════════════════════════════════════════════
     RENDER
     ═══════════════════════════════════════════════════════ */

  /* ── Bulk action toolbar ────────────────────────────── */
  const renderBulkToolbar = (selectedIds, isAdmin = false) => {
    if (!selectedIds.size) return null;
    return (
      <div className="auc-bulk-toolbar">
        <span className="auc-bulk-count">{selectedIds.size} selected</span>

        <button className="btn btn-sm btn-secondary" disabled={bulkBusy}
          onClick={() => handleBulkBan(selectedIds, 'ban')}>
          Ban
        </button>
        <button className="btn btn-sm btn-secondary" disabled={bulkBusy}
          onClick={() => handleBulkBan(selectedIds, 'unban')}>
          Unban
        </button>

        {!isAdmin && (
          <>
            <select className="auc-bulk-rc-select" value={bulkRateCodeId}
              onChange={e => setBulkRateCodeId(e.target.value)}>
              <option value="">Assign Rate Code…</option>
              {rateCodes.filter(rc => rc.active !== false).map(rc => (
                <option key={rc.id} value={rc.id}>{rc.name}</option>
              ))}
            </select>
            <input className="ab-input auc-bulk-label-input" placeholder="Member label…"
              value={bulkMemberLabel} onChange={e => setBulkMemberLabel(e.target.value)} />
            <button className="btn btn-sm btn-primary" disabled={bulkBusy || !bulkRateCodeId}
              onClick={handleBulkAssignRateCode}>
              Apply Rate Code
            </button>
          </>
        )}

        {isSuperAdmin && (
          <button className="btn btn-sm btn-danger" disabled={bulkBusy}
            onClick={() => handleBulkDelete(selectedIds)}>
            Delete Selected
          </button>
        )}

        <button className="btn btn-sm btn-secondary auc-bulk-clear"
          onClick={() => isAdmin ? setSelectedAdminIds(new Set()) : setSelectedCustIds(new Set())}>
          Clear
        </button>
      </div>
    );
  };

  /* ── Customers Tab ──────────────────────────────────── */
  const renderCustomersTab = () => (
    <>
      <div className="ab-filter-row">
        <input className="ab-input ab-input-search" placeholder="Search name, email or phone…"
          value={custSearch} onChange={e => setCustSearch(e.target.value)} />
      </div>

      {renderBulkToolbar(selectedCustIds)}

      {custLoading ? <p className="auc-loading">Loading customers…</p> : (
        <div className="ab-table-wrap">
          <table className="ab-table auc-table">
            <thead>
              <tr>
                <th className="auc-th-check">
                  <input type="checkbox"
                    checked={filteredCustomers.length > 0 && selectedCustIds.size === filteredCustomers.length}
                    onChange={toggleAllCust} />
                </th>
                <th>Name</th>
                <th>Email</th>
                <th>Phone</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredCustomers.length === 0 ? (
                <tr><td colSpan={6} className="ab-empty"><p>No customers found</p></td></tr>
              ) : filteredCustomers.map(c => (
                <tr key={c.id} className={selectedCustIds.has(c.id) ? 'auc-row-selected' : ''}>
                  <td className="auc-td-check" onClick={e => e.stopPropagation()}>
                    <input type="checkbox" checked={selectedCustIds.has(c.id)}
                      onChange={() => toggleCust(c.id)} />
                  </td>
                  <td onClick={() => openDetail(c)}>
                    <span className="ab-customer-name">{sanitize(c.firstName)} {sanitize(c.lastName || '')}</span>
                  </td>
                  <td onClick={() => openDetail(c)}>{sanitize(c.email)}</td>
                  <td onClick={() => openDetail(c)}>{sanitize(c.phone || '—')}</td>
                  <td onClick={() => openDetail(c)}>
                    <span className={c.active ? 'adm-badge adm-badge-active' : 'adm-badge adm-badge-inactive'}>
                      {c.active ? 'Active' : 'Banned'}
                    </span>
                  </td>
                  <td onClick={e => e.stopPropagation()}>
                    <button className="btn btn-sm btn-secondary" onClick={() => openDetail(c)}>View</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );

  /* ── Admins Tab ─────────────────────────────────────── */
  const renderAdminsTab = () => (
    <>
      <div className="ab-filter-row">
        <input className="ab-input ab-input-search" placeholder="Search name or email…"
          value={adminSearch} onChange={e => setAdminSearch(e.target.value)} />
      </div>

      {isSuperAdmin && renderBulkToolbar(selectedAdminIds, true)}

      {adminLoading ? <p className="auc-loading">Loading admins…</p> : (
        <div className="ab-table-wrap">
          <table className="ab-table auc-table">
            <thead>
              <tr>
                {isSuperAdmin && (
                  <th className="auc-th-check">
                    <input type="checkbox"
                      checked={filteredAdmins.length > 0 && selectedAdminIds.size === filteredAdmins.length}
                      onChange={toggleAllAdmin} />
                  </th>
                )}
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
                {isSuperAdmin && <th>Actions</th>}
              </tr>
            </thead>
            <tbody>
              {filteredAdmins.length === 0 ? (
                <tr><td colSpan={isSuperAdmin ? 6 : 4} className="ab-empty"><p>No admins found</p></td></tr>
              ) : filteredAdmins.map(a => (
                <tr key={a.id} className={selectedAdminIds.has(a.id) ? 'auc-row-selected' : ''}>
                  {isSuperAdmin && (
                    <td className="auc-td-check" onClick={e => e.stopPropagation()}>
                      <input type="checkbox" checked={selectedAdminIds.has(a.id)}
                        onChange={() => toggleAdmin(a.id)} />
                    </td>
                  )}
                  <td onClick={() => openDetail(a)}>
                    <span className="ab-customer-name">{sanitize(a.firstName)} {sanitize(a.lastName || '')}</span>
                  </td>
                  <td onClick={() => openDetail(a)}>{sanitize(a.email)}</td>
                  <td onClick={() => openDetail(a)}>
                    <span className="adm-badge adm-badge-info">{a.role}</span>
                  </td>
                  <td onClick={() => openDetail(a)}>
                    <span className={a.active ? 'adm-badge adm-badge-active' : 'adm-badge adm-badge-inactive'}>
                      {a.active ? 'Active' : 'Banned'}
                    </span>
                  </td>
                  {isSuperAdmin && (
                    <td onClick={e => e.stopPropagation()}>
                      <button className="btn btn-sm btn-secondary" onClick={() => openDetail(a)}>View</button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );

  /* ── Config (Rate Codes) quick-list ─────────────────── */
  const renderConfigTab = () => (
    <>
      <p className="auc-config-hint">
        Manage rate code details on the dedicated <Link to="/admin/rate-codes">Rate Codes page</Link>.
        Below is a quick overview.
      </p>
      {rcLoading ? <p className="auc-loading">Loading…</p> : (
        <div className="ab-table-wrap">
          <table className="ab-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Description</th>
                <th>Status</th>
                <th>Event Pricings</th>
                <th>Add-on Pricings</th>
              </tr>
            </thead>
            <tbody>
              {rateCodes.length === 0 ? (
                <tr><td colSpan={5} className="ab-empty"><p>No rate codes</p></td></tr>
              ) : rateCodes.map(rc => (
                <tr key={rc.id} onClick={() => navigate(`/admin/rate-codes?expand=${rc.id}`)} style={{ cursor: 'pointer' }} title="Click to view this rate code">
                  <td><strong style={{ color: 'var(--primary)' }}>{sanitize(rc.name)}</strong></td>
                  <td>{sanitize(rc.description || '—')}</td>
                  <td>
                    <span className={rc.active !== false ? 'adm-badge adm-badge-active' : 'adm-badge adm-badge-inactive'}>
                      {rc.active !== false ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                  <td>{rc.eventPricings?.length || 0}</td>
                  <td>{rc.addonPricings?.length || 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );

  /* ── User Detail Modal ──────────────────────────────── */
  const renderDetailModal = () => {
    if (!detailUser) return null;
    const u = detailUser;
    const isAdminUser = u.role === 'ADMIN' || u.role === 'SUPER_ADMIN';
    const cd = customerDetail;

    return (
      <div className="ab-modal-overlay" onClick={closeDetail}>
        <div className="ab-modal-card adm-flow-card auc-detail-modal" onClick={e => e.stopPropagation()}>
          {/* Header */}
          <div className="ab-modal-header">
            <h3>{sanitize(u.firstName)} {sanitize(u.lastName || '')}</h3>
            <button className="ab-modal-close" onClick={closeDetail}>×</button>
          </div>

          {/* Detail tabs */}
          <div className="ab-detail-tabs">
            {USER_DETAIL_TABS.filter(t => !isAdminUser || t.key === 'info').map(t => (
              <button key={t.key} className={`ab-detail-tab ${detailTab === t.key ? 'active' : ''}`}
                onClick={() => setDetailTab(t.key)}>
                {t.label}
              </button>
            ))}
          </div>

          {/* Body */}
          <div className="ab-modal-body">
            {/* ── Info tab ──────────────────────── */}
            {detailTab === 'info' && (
              <>
                {isSuperAdmin && editingUser === u.id ? (
                  <div className="auc-edit-form">
                    <label>First Name
                      <input className="ab-input" value={editForm.firstName}
                        onChange={e => setEditForm(p => ({ ...p, firstName: e.target.value }))} />
                    </label>
                    <label>Last Name
                      <input className="ab-input" value={editForm.lastName}
                        onChange={e => setEditForm(p => ({ ...p, lastName: e.target.value }))} />
                    </label>
                    <label>Email
                      <input className="ab-input" type="email" value={editForm.email}
                        onChange={e => setEditForm(p => ({ ...p, email: e.target.value }))} />
                    </label>
                    <label>Phone
                      <input className="ab-input" value={editForm.phone}
                        onChange={e => setEditForm(p => ({ ...p, phone: e.target.value }))} />
                    </label>
                    <div className="ab-action-row">
                      <button className="btn btn-sm btn-primary" onClick={saveEdit} disabled={editSaving}>{editSaving ? 'Saving...' : 'Save'}</button>
                      <button className="btn btn-sm btn-secondary" onClick={() => setEditingUser(null)}>Cancel</button>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="ab-detail-row"><span className="ab-detail-label">ID</span><span>{u.id}</span></div>
                    <div className="ab-detail-row"><span className="ab-detail-label">Name</span><span>{sanitize(u.firstName)} {sanitize(u.lastName || '')}</span></div>
                    <div className="ab-detail-row"><span className="ab-detail-label">Email</span><span>{sanitize(u.email)}</span></div>
                    <div className="ab-detail-row"><span className="ab-detail-label">Phone</span><span>{sanitize(u.phone || '—')}</span></div>
                    <div className="ab-detail-row"><span className="ab-detail-label">Role</span><span className="adm-badge adm-badge-info">{u.role}</span></div>
                    <div className="ab-detail-row">
                      <span className="ab-detail-label">Status</span>
                      <span className={u.active ? 'adm-badge adm-badge-active' : 'adm-badge adm-badge-inactive'}>
                        {u.active ? 'Active' : 'Banned'}
                      </span>
                    </div>
                    <div className="ab-action-row">
                      {isSuperAdmin && (
                        <button className="btn btn-sm btn-secondary" onClick={() => startEdit(u)}>Edit</button>
                      )}
                      <button className="btn btn-sm btn-secondary" onClick={() => handleToggleBan(u)}>
                        {u.active ? 'Ban' : 'Unban'}
                      </button>
                      {isSuperAdmin && (
                        <button className="btn btn-sm btn-danger" onClick={() => handleDeleteUser(u)}>Delete</button>
                      )}
                    </div>
                  </>
                )}
              </>
            )}

            {/* ── Pricing & Rate Code tab ───────── */}
            {detailTab === 'pricing' && (
              <>
                {detailLoading ? <p className="auc-loading">Loading pricing…</p> : cd ? (
                  <>
                    <div className="ab-detail-row">
                      <span className="ab-detail-label">Current Rate Code</span>
                      {cd.currentRateCodeName ? (
                        <span className="adm-badge adm-badge-info" style={{ cursor: 'pointer', textDecoration: 'underline' }}
                          onClick={() => navigate(`/admin/customer-pricing?customerId=${u.id}`)}>
                          {cd.currentRateCodeName}
                        </span>
                      ) : (
                        <span>None (base pricing)</span>
                      )}
                    </div>
                    <div className="ab-detail-row">
                      <span className="ab-detail-label">Rate Code ID</span>
                      <span>{cd.currentRateCodeId || '—'}</span>
                    </div>
                    <div className="ab-detail-row">
                      <span className="ab-detail-label">Member Label</span>
                      {editingMemberLabel ? (
                        <span className="auc-label-edit-row">
                          <input className="ab-input ab-detail-edit-input" value={memberLabelValue}
                            onChange={e => setMemberLabelValue(e.target.value)}
                            placeholder="Display name for member snapshot" />
                          <button className="btn btn-sm btn-primary" onClick={() => saveMemberLabel(u.id)}>Save</button>
                          <button className="btn btn-sm btn-secondary" onClick={() => setEditingMemberLabel(false)}>Cancel</button>
                        </span>
                      ) : (
                        <span>
                          {cd.memberLabel || <em style={{ color: 'var(--text-muted)' }}>Not set</em>}
                          {' '}
                          <button className="btn btn-sm btn-secondary" onClick={() => {
                            setEditingMemberLabel(true);
                            setMemberLabelValue(cd.memberLabel || '');
                          }}>Edit</button>
                        </span>
                      )}
                    </div>
                    <div className="ab-detail-row">
                      <span className="ab-detail-label">Total Reservations</span>
                      <span><strong>{cd.totalReservations}</strong></span>
                    </div>
                    <div className="ab-action-row">
                      <button className="btn btn-sm btn-secondary"
                        onClick={() => navigate(`/admin/customer-pricing?customerId=${u.id}`)}>
                        Edit Full Pricing →
                      </button>
                    </div>
                  </>
                ) : (
                  <p className="auc-empty-hint">No pricing data available for this binge.</p>
                )}
              </>
            )}

            {/* ── Reservations tab ──────────────── */}
            {detailTab === 'reservations' && (
              <>
                {detailLoading ? <p className="auc-loading">Loading…</p> : cd?.reservations?.length ? (
                  <div className="ab-table-wrap">
                    <table className="ab-table auc-res-table">
                      <thead>
                        <tr>
                          <th>Ref</th>
                          <th>Event</th>
                          <th>Date</th>
                          <th>Status</th>
                          <th>Total</th>
                          <th>Rate Code</th>
                        </tr>
                      </thead>
                      <tbody>
                        {cd.reservations.map(r => (
                          <tr key={r.bookingRef} onClick={() => navigate(`/admin/bookings?search=${r.bookingRef}`)}>
                            <td><span className="ab-ref">{r.bookingRef}</span></td>
                            <td>{sanitize(r.eventTypeName)}</td>
                            <td>{fmtDate(r.bookingDate)}</td>
                            <td><span className={STATUS_BADGE[r.status] || 'adm-badge'}>{r.status}</span></td>
                            <td className="ab-amount">{fmtMoney(r.totalAmount)}</td>
                            <td>{sanitize(r.rateCodeName || 'Base')}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <p className="auc-empty-hint">No reservations found for this customer in the current binge.</p>
                )}
              </>
            )}

            {/* ── Rate Code Audit tab ───────────── */}
            {detailTab === 'audit' && (
              <>
                {detailLoading ? <p className="auc-loading">Loading…</p> : cd?.rateCodeChanges?.length ? (
                  <div className="auc-audit-list">
                    {cd.rateCodeChanges.map((ch, i) => (
                      <div key={ch.id || i} className="auc-audit-entry">
                        <div className="auc-audit-header">
                          <span className="auc-audit-type">{ch.changeType}</span>
                          <span className="auc-audit-time">{fmtDateTime(ch.changedAt)}</span>
                        </div>
                        <div className="auc-audit-detail">
                          {ch.previousRateCodeName && (
                            <span className="auc-audit-from">From: <strong>{sanitize(ch.previousRateCodeName)}</strong></span>
                          )}
                          <span className="auc-audit-arrow">→</span>
                          <span className="auc-audit-to">To: <strong>{sanitize(ch.newRateCodeName) || 'None'}</strong></span>
                        </div>
                        {ch.changedByAdminId && (
                          <div className="auc-audit-meta">By Admin ID: {ch.changedByAdminId}</div>
                        )}
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="auc-empty-hint">No rate code changes recorded.</p>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    );
  };

  /* ── Main render ────────────────────────────────────── */
  return (
    <div className="adm-shell adm-flow-shell">
      <div className="adm-flow-card" style={{ padding: 0 }}>
        {/* Main Tabs */}
        <div className="ab-tabs ab-tabs--no-margin">
          {MAIN_TABS.filter(t => t.key !== 'admins' || isSuperAdmin).map(t => (
            <button key={t.key} className={`ab-tab ${mainTab === t.key ? 'active' : ''}`}
              onClick={() => setMainTab(t.key)}>
              {t.label}
            </button>
          ))}
        </div>

        <div style={{ padding: '1.25rem' }}>
          {mainTab === 'users' && renderCustomersTab()}
          {mainTab === 'admins' && isSuperAdmin && renderAdminsTab()}
          {mainTab === 'config' && renderConfigTab()}
        </div>
      </div>

      {renderDetailModal()}
    </div>
  );
}
