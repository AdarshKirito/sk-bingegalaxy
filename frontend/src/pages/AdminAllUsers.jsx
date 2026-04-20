import { useState, useEffect, useMemo, lazy, Suspense } from 'react';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import SEO from '../components/SEO';
import { toast } from 'react-toastify';
import { FiUsers, FiUser, FiShield, FiCheck, FiX, FiMessageSquare, FiStar } from 'react-icons/fi';
import './AdminPages.css';
import './AdminBookings.css';

const CustomerReviewsDrawer = lazy(() => import('../components/CustomerReviewsDrawer'));

const PAGE_SIZE = 20;

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

export default function AdminAllUsers() {
  const { isSuperAdmin } = useAuth();
  const [customers, setCustomers] = useState([]);
  const [admins, setAdmins] = useState([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('customers');
  const [search, setSearch] = useState('');

  // Review summary cache: customerId → { avgAdminRating, adminReviewCount }
  const [summaryCache, setSummaryCache] = useState({});

  // Drawer state
  const [activeCustomer, setActiveCustomer] = useState(null);

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

  // Pre-fetch review summaries for current page customers (fire-and-forget)
  useEffect(() => {
    if (tab !== 'customers') return;
    pageItems.forEach(u => {
      if (summaryCache[u.id] !== undefined) return;
      // Mark as pending so we don't fire again
      setSummaryCache(prev => ({ ...prev, [u.id]: null }));
      import('../services/endpoints').then(({ adminService }) =>
        adminService.getCustomerReviewSummary(u.id)
          .then(res => {
            const d = res.data?.data ?? res.data;
            setSummaryCache(prev => ({ ...prev, [u.id]: d }));
          })
          .catch(() => {
            // leave as null — no rating available yet
          })
      );
    });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, page, pageItems.length]);

  const openDrawer = (customer) => setActiveCustomer(customer);
  const closeDrawer = () => setActiveCustomer(null);

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

      {tab === 'customers' && (
        <p className="aau-table-hint">
          <FiMessageSquare aria-hidden="true" />
          Click any customer row to view their admin review history and assessment.
        </p>
      )}

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
                {tab === 'customers' && (
                  <th className="aau-th-rating">
                    <FiStar aria-hidden="true" /> Admin Rating
                  </th>
                )}
              </tr>
            </thead>
            <tbody>
              {pageItems.map((u) => {
                const summary = summaryCache[u.id];
                const hasReviews = summary && summary.adminReviewCount > 0;
                return (
                  <tr
                    key={u.id}
                    className={tab === 'customers' ? 'aau-row-customer' : undefined}
                    onClick={tab === 'customers' ? () => openDrawer(u) : undefined}
                    role={tab === 'customers' ? 'button' : undefined}
                    tabIndex={tab === 'customers' ? 0 : undefined}
                    onKeyDown={tab === 'customers' ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openDrawer(u); } } : undefined}
                    aria-label={tab === 'customers' ? `View reviews for ${[u.firstName, u.lastName].filter(Boolean).join(' ')}` : undefined}
                  >
                    <td className="highlight">
                      {[u.firstName, u.lastName].filter(Boolean).join(' ') || '—'}
                    </td>
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
                    {tab === 'customers' && (
                      <td className="aau-td-rating">
                        {summary === undefined ? (
                          <span className="aau-rating-loading" aria-label="Loading rating" />
                        ) : hasReviews ? (
                          <button
                            type="button"
                            className="aau-rating-pill"
                            onClick={(e) => { e.stopPropagation(); openDrawer(u); }}
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

      {/* Customer Reviews Drawer — lazy-loaded */}
      {activeCustomer && (
        <Suspense fallback={null}>
          <CustomerReviewsDrawer customer={activeCustomer} onClose={closeDrawer} />
        </Suspense>
      )}
    </div>
  );
}

