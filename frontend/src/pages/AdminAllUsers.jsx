import { useState, useEffect, useMemo } from 'react';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
import { FiUsers, FiUser, FiShield, FiCheck, FiX } from 'react-icons/fi';
import './AdminPages.css';
import './AdminBookings.css';

const PAGE_SIZE = 20;

export default function AdminAllUsers() {
  const { isSuperAdmin } = useAuth();
  const [customers, setCustomers] = useState([]);
  const [admins, setAdmins] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('customers');
  const [search, setSearch] = useState('');

  useEffect(() => {
    setLoading(true);
    Promise.allSettled([
      authService.getAllCustomers(),
      isSuperAdmin ? authService.getAllAdmins() : Promise.resolve({ data: { data: [] } }),
    ]).then(([custRes, adminRes]) => {
      const custData = custRes.status === 'fulfilled' ? (custRes.value.data.data?.content || custRes.value.data.data || []) : [];
      setCustomers(Array.isArray(custData) ? custData : []);
      const adminData = adminRes.status === 'fulfilled' ? (adminRes.value.data.data || []) : [];
      setAdmins(Array.isArray(adminData) ? adminData : []);
    }).finally(() => setLoading(false));
  }, [isSuperAdmin]);

  const lowerSearch = search.toLowerCase();
  const activeList = tab === 'customers' ? customers : admins;
  const filtered = useMemo(() => {
    if (!lowerSearch) return activeList;
    return activeList.filter((u) => {
      const name = `${u.firstName || ''} ${u.lastName || ''}`.toLowerCase();
      return name.includes(lowerSearch) || (u.email || '').toLowerCase().includes(lowerSearch) || (u.phone || '').includes(lowerSearch);
    });
  }, [activeList, lowerSearch]);

  const [page, setPage] = useState(0);
  useEffect(() => setPage(0), [tab, search]);
  const totalPages = Math.ceil(filtered.length / PAGE_SIZE);
  const pageItems = filtered.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);

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

  return (
    <div className="container adm-shell">
      <SEO title="All Users" description="View all platform users across all venues." />

      <div className="adm-header">
        <div className="adm-header-copy">
          <span className="adm-kicker"><FiUsers /> Users</span>
          <h1>Platform Users</h1>
          <p className="adm-form-intro">All users across every venue. {customers.length} customer{customers.length !== 1 ? 's' : ''}, {admins.length} admin{admins.length !== 1 ? 's' : ''}.</p>
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

      {pageItems.length === 0 ? (
        <div className="adm-empty">
          <span className="adm-empty-icon"><FiUsers /></span>
          <h3>No {tab} found{search ? ' matching your search' : ''}</h3>
        </div>
      ) : (
        <div className="adm-table-wrap">
          <table className="adm-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Phone</th>
                {tab === 'admins' && <th>Role</th>}
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {pageItems.map((u) => (
                <tr key={u.id}>
                  <td className="highlight">{[u.firstName, u.lastName].filter(Boolean).join(' ') || '—'}</td>
                  <td>{u.email || '—'}</td>
                  <td>{u.phone || '—'}</td>
                  {tab === 'admins' && (
                    <td><span className="adm-badge adm-badge-info">{u.role === 'SUPER_ADMIN' ? 'Super Admin' : 'Admin'}</span></td>
                  )}
                  <td>
                    {u.active !== false
                      ? <span className="adm-badge adm-badge-active"><FiCheck /> Active</span>
                      : <span className="adm-badge adm-badge-inactive"><FiX /> Inactive</span>}
                  </td>
                </tr>
              ))}
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
    </div>
  );
}
