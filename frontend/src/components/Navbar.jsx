import { useState, useEffect } from 'react';
import { Link, NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { useTranslation } from 'react-i18next';
import {
  FiBarChart2,
  FiCalendar,
  FiClock,
  FiCompass,
  FiCreditCard,
  FiInfo,
  FiHome,
  FiLogOut,
  FiMapPin,
  FiMenu,
  FiPlusCircle,
  FiSettings,
  FiShield,
  FiUser,
  FiUsers,
  FiX,
  FiGrid,
  FiZap,
  FiList,
  FiLock,
  FiAward,
  FiBell,
  FiSend,
  FiTool,
  FiAlertTriangle,
  FiAlertOctagon,
  FiMessageSquare,
  FiDollarSign,
} from 'react-icons/fi';
import ThemeToggle from './ThemeToggle';
import CurrencySwitcher from './CurrencySwitcher';

import NavDropdownGroup from './NavDropdownGroup';
import NotificationsBell from './NotificationsBell';
import './Navbar.css';

export default function Navbar() {
  const { user, isAuthenticated, isAdmin, isSuperAdmin, loading, logout } = useAuth();
  const { selectedBinge, clearBinge } = useBinge();
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const authReady = !loading;
  const effectiveIsAuthenticated = authReady && isAuthenticated;
  const effectiveIsAdmin = authReady && isAdmin;
  const effectiveIsSuperAdmin = authReady && isSuperAdmin;
  const effectiveUser = authReady ? user : null;
  const effectiveSelectedBinge = effectiveIsAuthenticated ? selectedBinge : null;
  const isPublicHome = location.pathname === '/' && !effectiveIsAuthenticated;
  const isCustomerSide = effectiveIsAuthenticated && !effectiveIsAdmin;
  const isLoginRoute = ['/login', '/forgot-password', '/reset-password'].includes(location.pathname);
  const isRegisterRoute = location.pathname === '/register';
  const adminRoleLabel = effectiveIsSuperAdmin ? 'Super Admin' : 'Admin';
  // Admin shell — uses a fixed left sidebar instead of a horizontal pill rail.
  // Active for ALL admin states (entrance level + in-binge level).
  const isAdminShell = effectiveIsAuthenticated && effectiveIsAdmin;
  const isAdminPlatformShell = isAdminShell && !effectiveSelectedBinge;
  const isAdminBingeShell = isAdminShell && !!effectiveSelectedBinge;

  // Close menu on route change
  useEffect(() => { setMenuOpen(false); }, [location.pathname]);

  // Prevent body scroll when menu is open
  useEffect(() => {
    document.body.style.overflow = menuOpen ? 'hidden' : '';
    return () => { document.body.style.overflow = ''; };
  }, [menuOpen]);

  // Apply / remove a global body class so layout pages can reserve space for the fixed admin sidebar.
  // Prefixed with `app-` to avoid colliding with pre-existing component-scoped class names
  // (an orphan `.admin-binge-shell { max-width: 980px }` rule in AdminExperience.css was clobbering body width).
  useEffect(() => {
    document.body.classList.toggle('app-admin-shell', isAdminShell);
    document.body.classList.toggle('app-admin-platform-shell', isAdminPlatformShell);
    document.body.classList.toggle('app-admin-binge-shell', isAdminBingeShell);
    return () => {
      document.body.classList.remove('app-admin-shell', 'app-admin-platform-shell', 'app-admin-binge-shell');
    };
  }, [isAdminShell, isAdminPlatformShell, isAdminBingeShell]);

  const handleLogout = async () => {
    try {
      await logout();
    } catch {
      // Server-side session cleanup failed — local state was already cleared by logout()
    }
    navigate('/');
  };

  const handleChangeBinge = () => {
    clearBinge();
    navigate(effectiveIsAdmin ? '/admin/platform' : '/binges');
  };

  const customerLinks = [
    { to: '/dashboard', icon: <FiHome />, label: t('nav.dashboard') },
    { to: '/book', icon: <FiCalendar />, label: t('nav.book') },
    { to: '/my-bookings', icon: <FiCompass />, label: t('nav.my_bookings') },
    { to: '/payments', icon: <FiCreditCard />, label: t('nav.payments') },
    { to: '/membership', icon: <FiAward />, label: t('nav.membership', 'Membership') },
    { to: '/about', icon: <FiInfo />, label: t('nav.about') },
    // Settings intentionally lives at the customer entrance level (§ below) —
    // it is the same regardless of which binge is currently selected.
  ];

  const adminConsoleLinks = [
    { to: '/admin/dashboard', icon: <FiHome />, label: t('nav.dashboard') },
    { to: '/admin/bookings', icon: <FiCalendar />, label: t('nav.bookings') },
    { to: '/admin/book', icon: <FiPlusCircle />, label: t('common.create') },
    { to: '/admin/blocked-dates', icon: <FiClock />, label: t('nav.blocked_dates') },
    { to: '/admin/event-types', icon: <FiSettings />, label: t('nav.event_types') },
    { to: '/admin/rate-codes', icon: <FiSettings />, label: t('nav.rate_codes') },
    { to: '/admin/venue-rooms', icon: <FiGrid />, label: t('nav.venue_rooms', 'Rooms') },
    { to: '/admin/surge-rules', icon: <FiZap />, label: t('nav.surge_rules', 'Surge Rules') },
    { to: '/admin/waitlist', icon: <FiList />, label: t('nav.waitlist', 'Waitlist') },
    { to: '/admin/customer-freezes', icon: <FiLock />, label: t('nav.customer_freezes', 'Customer Freezes') },
    { to: '/admin/risk-flags', icon: <FiAlertTriangle />, label: t('nav.risk_flags', 'Risk Flags') },
    { to: '/admin/support', icon: <FiMessageSquare />, label: t('nav.support_console', 'Support Console') },
    { to: '/admin/slot-holds', icon: <FiClock />, label: t('nav.slot_holds', 'Slot Holds') },
    { to: '/admin/taxes', icon: <FiCreditCard />, label: t('nav.taxes', 'Taxes') },
    { to: '/admin/recovery', icon: <FiShield />, label: t('nav.recovery', 'Recovery') },
    { to: '/admin/approvals', icon: <FiShield />, label: t('nav.approvals', 'Approvals') },
    { to: '/admin/disputes', icon: <FiAlertOctagon />, label: t('nav.disputes', 'Disputes') },
    { to: '/admin/failed-refunds', icon: <FiDollarSign />, label: t('nav.failed_refunds', 'Failed Refunds') },
    { to: '/admin/users-config', icon: <FiUsers />, label: t('nav.users') },
    { to: '/admin/reports', icon: <FiBarChart2 />, label: t('nav.reports', 'Reports') },
  ];

  const accountLink = { to: '/account', icon: <FiUser />, label: t('nav.account') };

  const navLinkClass = ({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`;
  const publicAuthLinkClass = (isActive) => `nav-auth-link${isActive ? ' nav-auth-link-active' : ''}`;

  return (
    <>
    <nav className={`navbar${isPublicHome ? ' navbar-home' : ''}${isCustomerSide ? ' navbar-customer' : ''}${isAdmin ? ' navbar-admin' : ''}`} aria-label="Main navigation">
      <div className="container navbar-inner">
        <Link to="/" className="navbar-brand" aria-label="SK Binge Galaxy home">
          <span className="brand-icon" aria-hidden="true">🎬</span>
          <span className="brand-copy">
            <strong>SK Binge Galaxy</strong>
            <span className="brand-meta">{!isAuthenticated ? 'Private Screenings' : isAdmin ? (effectiveSelectedBinge ? 'Admin Console' : 'Admin Platform') : (effectiveSelectedBinge ? 'Customer Hub' : 'Platform')}</span>
          </span>
        </Link>

        {menuOpen && <div className="menu-overlay" onClick={() => setMenuOpen(false)} />}

        <div className={`navbar-links ${menuOpen ? 'open' : ''}`}>
          {!effectiveIsAuthenticated ? (
            <>
              <NavLink to="/login" className={publicAuthLinkClass(isLoginRoute)}>{t('nav.login')}</NavLink>
              <NavLink to="/register" className={publicAuthLinkClass(isRegisterRoute)}>{t('nav.register')}</NavLink>
            </>
          ) : effectiveIsAdmin ? (
            <>
              {!effectiveSelectedBinge && (
                <div className="nav-admin-entry nav-mobile-only">
                  <NavLink to="/admin/platform" className={navLinkClass}><FiHome /> {t('nav.dashboard')}</NavLink>
                  <NavLink to="/admin/binges" className={navLinkClass}><FiMapPin /> {t('nav.binges')}</NavLink>
                  <NavLink to="/admin/account" className={navLinkClass}><FiUser /> {t('nav.account')}</NavLink>
                  {effectiveIsSuperAdmin && <NavLink to="/admin/all-users" className={navLinkClass}><FiUsers /> {t('nav.users')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/loyalty-center" className={navLinkClass}><FiAward /> {t('nav.loyalty_center', 'Loyalty Center')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/currencies" className={navLinkClass}><FiCreditCard /> {t('nav.currencies', 'Currencies')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/account-page-editor" className={navLinkClass}><FiSettings /> {t('nav.account_page_cms', 'Account Page CMS')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/notification-templates" className={navLinkClass}><FiSend /> {t('nav.notification_templates', 'Notification Templates')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/ops" className={navLinkClass}><FiTool /> {t('nav.ops', 'Ops Console')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/register" className={navLinkClass}><FiShield /> {t('nav.add_admin', 'Add Admin')}</NavLink>}
                  <div className="nav-customer-meta nav-admin-meta">
                    <span className="nav-user nav-admin-user"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
                    <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn" aria-label={t('nav.logout')}><FiLogOut /> <span className="nav-action-label">{t('nav.logout')}</span></button>
                  </div>
                </div>
              )}

              {effectiveSelectedBinge && (
                <div className="nav-admin-menu">
                  <div className="nav-admin-links">
                    {adminConsoleLinks.map(link => (
                      <NavLink key={link.to} to={link.to} className={navLinkClass}>
                        {link.icon}
                        <span>{link.label}</span>
                      </NavLink>
                    ))}
                    {effectiveIsSuperAdmin && (
                      <NavLink to="/admin/register" className={navLinkClass}><FiShield /> <span>{t('nav.add_admin', 'Add Admin')}</span></NavLink>
                    )}
                  </div>
                  <div className="nav-customer-meta nav-mobile-only nav-admin-meta">
                    <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn" title={t('nav.change_binge')}>
                      <FiMapPin />
                      <span className="venue-details">
                        <span className="venue-pill-label">{t('nav.venue', 'Venue')}</span>
                        <span className="venue-name">{effectiveSelectedBinge.name}</span>
                      </span>
                    </button>
                    <span className="nav-user nav-admin-user"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
                    <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn" aria-label={t('nav.logout')}><FiLogOut /> <span className="nav-action-label">{t('nav.logout')}</span></button>
                  </div>
                </div>
              )}
            </>
          ) : (
            <>
              {effectiveSelectedBinge ? (
                <div className="nav-customer-menu">
                  <div className="nav-customer-links">
                    {[...customerLinks, accountLink].map(link => (
                      <NavLink key={link.to} to={link.to} className={navLinkClass}>
                        {link.icon}
                        <span>{link.label}</span>
                      </NavLink>
                    ))}
                  </div>
                </div>
              ) : null}
              {!effectiveSelectedBinge && (
                <>
                  <NavLink to="/platform" className={navLinkClass}><FiHome /> {t('nav.dashboard')}</NavLink>
                  <NavLink to="/binges" className={navLinkClass}><FiMapPin /> {t('nav.venues', 'Venues')}</NavLink>
                  <NavLink to="/membership" className={navLinkClass}><FiAward /> {t('nav.membership', 'Membership')}</NavLink>
                  <NavLink to={accountLink.to} className={navLinkClass}><FiUser /> {t('nav.account')}</NavLink>
                  <NavLink to="/settings" className={navLinkClass}><FiSettings /> {t('nav.settings', 'Settings')}</NavLink>
                </>
              )}
              <div className="nav-customer-meta nav-mobile-only">
                {effectiveSelectedBinge && (
                  <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn" title={t('nav.change_binge')}>
                    <FiMapPin />
                    <span className="venue-details">
                      <span className="venue-pill-label">{t('nav.venue', 'Venue')}</span>
                      <span className="venue-name">{effectiveSelectedBinge.name}</span>
                    </span>
                  </button>
                )}
                <span className="nav-user"><FiUser /> <span>{effectiveUser?.firstName}</span><small>{t('nav.customer', 'Customer')}</small></span>
                <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn" aria-label={t('nav.logout')}><FiLogOut /> <span className="nav-action-label">{t('nav.logout')}</span></button>
              </div>
            </>
          )}
        </div>

        <div className="navbar-right">
          {effectiveIsAuthenticated && effectiveIsAdmin && (
            <NotificationsBell />
          )}
          {effectiveIsAuthenticated && effectiveIsAdmin && !effectiveSelectedBinge && (
            <span className="nav-user nav-admin-user nav-desktop-only"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
          )}

          {effectiveIsAuthenticated && effectiveIsAdmin && (
            <button onClick={handleLogout} className="nav-link nav-btn nav-desktop-only nav-logout-btn" aria-label={t('nav.logout')}>
              <FiLogOut /> <span className="nav-action-label">{t('nav.logout')}</span>
            </button>
          )}

          {effectiveIsAuthenticated && !effectiveIsAdmin && effectiveSelectedBinge && (
            <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn nav-desktop-only" title={t('nav.change_binge')}>
              <FiMapPin />
              <span className="venue-details">
                <span className="venue-pill-label">{t('nav.venue', 'Venue')}</span>
                <span className="venue-name">{effectiveSelectedBinge.name}</span>
              </span>
            </button>
          )}

          {effectiveIsAuthenticated && !effectiveIsAdmin && (
            <span className="nav-user nav-desktop-only"><FiUser /> <span>{effectiveUser?.firstName}</span><small>{t('nav.customer', 'Customer')}</small></span>
          )}

          {effectiveIsAuthenticated && !effectiveIsAdmin && (
            <button onClick={handleLogout} className="nav-link nav-btn nav-desktop-only nav-logout-btn" aria-label={t('nav.logout')}>
              <FiLogOut /> <span className="nav-action-label">{t('nav.logout')}</span>
            </button>
          )}

          {!effectiveIsAdmin && <CurrencySwitcher compact ariaLabel="Display currency" />}
          <ThemeToggle />
          <button
            className="lang-toggle-btn"
            onClick={() => { const order = ['en', 'hi', 'te', 'ta']; const idx = order.indexOf(i18n.language); const next = order[(idx + 1) % order.length]; i18n.changeLanguage(next); localStorage.setItem('lang', next); }}
            aria-label="Switch language"
            title={{ en: 'हिन्दी', hi: 'తెలుగు', te: 'தமிழ்', ta: 'English' }[i18n.language] || 'English'}
          >
            {{ en: 'हि', hi: 'తె', te: 'த', ta: 'EN' }[i18n.language] || 'EN'}
          </button>
          <button
            className="hamburger-btn"
            onClick={() => setMenuOpen(o => !o)}
            aria-label={menuOpen ? 'Close menu' : 'Open menu'}
            aria-expanded={menuOpen}
          >
            {menuOpen ? <FiX /> : <FiMenu />}
          </button>
        </div>
      </div>

      {effectiveIsAuthenticated && !effectiveIsAdmin && effectiveSelectedBinge && (
        <div className="container nav-customer-console">
          <nav className="nav-customer-bar" aria-label="Customer navigation">
            <NavDropdownGroup to="/dashboard" icon={<FiHome />} label={t('nav.dashboard')} />

            <NavDropdownGroup
              icon={<FiCalendar />}
              label={t('nav.bookings', 'Bookings')}
              items={[
                { to: '/book',        icon: <FiCalendar />, label: t('nav.book') },
                { to: '/my-bookings', icon: <FiCompass />,  label: t('nav.my_bookings') },
              ]}
            />

            <NavDropdownGroup to="/payments" icon={<FiCreditCard />} label={t('nav.payments')} />
            <NavDropdownGroup to="/about"    icon={<FiInfo />}       label={t('nav.about')} />
            <NavDropdownGroup to="/account"  icon={<FiUser />}       label={t('nav.account')} />
          </nav>
        </div>
      )}

      {effectiveIsAuthenticated && effectiveIsAdmin && effectiveSelectedBinge && (
        <div className="container nav-admin-console">
          {/* ── Grouped desktop nav — GitHub/Vercel pattern ── */}
          <nav className="nav-admin-bar" aria-label="Admin console navigation">
            <NavDropdownGroup to="/admin/dashboard" icon={<FiHome />} label={t('nav.dashboard')} />

            <NavDropdownGroup
              icon={<FiCalendar />}
              label={t('nav.bookings')}
              items={[
                { to: '/admin/bookings',      icon: <FiCalendar />,   label: t('nav.bookings') },
                { to: '/admin/book',          icon: <FiPlusCircle />, label: t('common.create') },
                { to: '/admin/blocked-dates', icon: <FiClock />,      label: t('nav.blocked_dates') },
                { to: '/admin/slot-holds',    icon: <FiClock />,      label: t('nav.slot_holds', 'Slot Holds') },
              ]}
            />

            <NavDropdownGroup
              icon={<FiGrid />}
              label={t('nav.venue', 'Venue')}
              items={[
                { to: '/admin/venue-rooms',  icon: <FiGrid />,        label: t('nav.venue_rooms', 'Rooms') },
                { to: '/admin/event-types',  icon: <FiSettings />,    label: t('nav.event_types') },
                { to: '/admin/rate-codes',   icon: <FiSettings />,    label: t('nav.rate_codes') },
                { to: '/admin/surge-rules',  icon: <FiZap />,         label: t('nav.surge_rules', 'Surge Rules') },
                { to: '/admin/taxes',        icon: <FiCreditCard />,  label: t('nav.taxes', 'Taxes') },
              ]}
            />

            <NavDropdownGroup
              icon={<FiUsers />}
              label={t('nav.users')}
              items={[
                { to: '/admin/users-config', icon: <FiUsers />,  label: t('nav.users') },
                { to: '/admin/waitlist',     icon: <FiList />,   label: t('nav.waitlist', 'Waitlist') },
                { to: '/admin/customer-freezes', icon: <FiLock />, label: t('nav.customer_freezes', 'Customer Freezes') },
                { to: '/admin/risk-flags',   icon: <FiAlertTriangle />, label: t('nav.risk_flags', 'Risk Flags') },
                { to: '/admin/support',      icon: <FiMessageSquare />, label: t('nav.support_console', 'Support Console') },
                { to: '/admin/disputes',     icon: <FiAlertOctagon />, label: t('nav.disputes', 'Disputes') },
                { to: '/admin/failed-refunds', icon: <FiDollarSign />, label: t('nav.failed_refunds', 'Failed Refunds') },
                ...(effectiveIsSuperAdmin
                  ? [{ to: '/admin/register', icon: <FiShield />, label: t('nav.add_admin', 'Add Admin') }]
                  : []),
              ]}
            />

            <NavDropdownGroup to="/admin/reports" icon={<FiBarChart2 />} label={t('nav.reports', 'Reports')} />
          </nav>

          <div className="nav-admin-status">
            <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn" title={t('nav.change_binge')}>
              <FiMapPin />
              <span className="venue-details">
                <span className="venue-pill-label">{t('nav.venue', 'Venue')}</span>
                <span className="venue-name">{effectiveSelectedBinge.name}</span>
              </span>
            </button>
            <span className="nav-user nav-admin-user"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
          </div>
        </div>
      )}

    </nav>
    {isAdminPlatformShell && (
        <aside className="admin-platform-sidebar admin-sidebar" aria-label="Admin platform navigation">
          <div className="admin-sidebar-inner">
            <div className="admin-sidebar-section">
              <div className="admin-sidebar-heading">{t('nav.section_operate', 'Operate')}</div>
              <NavLink to="/admin/platform" className={navLinkClass} end><FiHome /> <span>{t('nav.dashboard')}</span></NavLink>
              <NavLink to="/admin/binges" className={navLinkClass}><FiMapPin /> <span>{t('nav.binges')}</span></NavLink>
              {effectiveIsSuperAdmin && <NavLink to="/admin/ops" className={navLinkClass}><FiTool /> <span>{t('nav.ops', 'Ops Console')}</span></NavLink>}
            </div>

            {effectiveIsSuperAdmin && (
              <div className="admin-sidebar-section">
                <div className="admin-sidebar-heading">{t('nav.section_configure', 'Configure')}</div>
                <NavLink to="/admin/loyalty-center" className={navLinkClass}><FiAward /> <span>{t('nav.loyalty_center', 'Loyalty Center')}</span></NavLink>
                <NavLink to="/admin/currencies" className={navLinkClass}><FiCreditCard /> <span>{t('nav.currencies', 'Currencies')}</span></NavLink>
                <NavLink to="/admin/account-page-editor" className={navLinkClass}><FiSettings /> <span>{t('nav.account_page_cms', 'Account Page CMS')}</span></NavLink>
                <NavLink to="/admin/notification-templates" className={navLinkClass}><FiSend /> <span>{t('nav.notification_templates', 'Notification Templates')}</span></NavLink>
              </div>
            )}

            <div className="admin-sidebar-section">
              <div className="admin-sidebar-heading">{t('nav.section_govern', 'Govern')}</div>
              {effectiveIsSuperAdmin && <NavLink to="/admin/all-users" className={navLinkClass}><FiUsers /> <span>{t('nav.users')}</span></NavLink>}
              {effectiveIsSuperAdmin && <NavLink to="/admin/register" className={navLinkClass}><FiShield /> <span>{t('nav.add_admin', 'Add Admin')}</span></NavLink>}
              <NavLink to="/admin/account" className={navLinkClass}><FiUser /> <span>{t('nav.account')}</span></NavLink>
            </div>
          </div>

          <div className="admin-sidebar-footer">
            <span className="nav-user nav-admin-user"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
          </div>
        </aside>
      )}

      {isAdminBingeShell && (
        <aside className="admin-binge-sidebar admin-sidebar" aria-label="Admin venue navigation">
          <div className="admin-sidebar-inner">
            <div className="admin-sidebar-section">
              <div className="admin-sidebar-heading">{t('nav.section_operate', 'Operate')}</div>
              <NavLink to="/admin/dashboard" className={navLinkClass} end><FiHome /> <span>{t('nav.dashboard')}</span></NavLink>
              <NavLink to="/admin/bookings" className={navLinkClass}><FiCalendar /> <span>{t('nav.bookings')}</span></NavLink>
              <NavLink to="/admin/book" className={navLinkClass}><FiPlusCircle /> <span>{t('common.create')}</span></NavLink>
              <NavLink to="/admin/reports" className={navLinkClass}><FiBarChart2 /> <span>{t('nav.reports', 'Reports')}</span></NavLink>
            </div>

            <div className="admin-sidebar-section">
              <div className="admin-sidebar-heading">{t('nav.section_venue', 'Venue')}</div>
              <NavLink to="/admin/venue-rooms" className={navLinkClass}><FiGrid /> <span>{t('nav.venue_rooms', 'Rooms')}</span></NavLink>
              <NavLink to="/admin/event-types" className={navLinkClass}><FiSettings /> <span>{t('nav.event_types')}</span></NavLink>
              <NavLink to="/admin/rate-codes" className={navLinkClass}><FiSettings /> <span>{t('nav.rate_codes')}</span></NavLink>
              <NavLink to="/admin/surge-rules" className={navLinkClass}><FiZap /> <span>{t('nav.surge_rules', 'Surge Rules')}</span></NavLink>
              <NavLink to="/admin/taxes" className={navLinkClass}><FiCreditCard /> <span>{t('nav.taxes', 'Taxes')}</span></NavLink>
              <NavLink to="/admin/blocked-dates" className={navLinkClass}><FiClock /> <span>{t('nav.blocked_dates')}</span></NavLink>
              <NavLink to="/admin/slot-holds" className={navLinkClass}><FiClock /> <span>{t('nav.slot_holds', 'Slot Holds')}</span></NavLink>
            </div>

            <div className="admin-sidebar-section">
              <div className="admin-sidebar-heading">{t('nav.section_people', 'People')}</div>
              <NavLink to="/admin/users-config" className={navLinkClass}><FiUsers /> <span>{t('nav.users')}</span></NavLink>
              <NavLink to="/admin/waitlist" className={navLinkClass}><FiList /> <span>{t('nav.waitlist', 'Waitlist')}</span></NavLink>
              <NavLink to="/admin/customer-freezes" className={navLinkClass}><FiLock /> <span>{t('nav.customer_freezes', 'Customer Freezes')}</span></NavLink>
              <NavLink to="/admin/risk-flags" className={navLinkClass}><FiAlertTriangle /> <span>{t('nav.risk_flags', 'Risk Flags')}</span></NavLink>
              <NavLink to="/admin/support" className={navLinkClass}><FiMessageSquare /> <span>{t('nav.support_console', 'Support Console')}</span></NavLink>
              <NavLink to="/admin/disputes" className={navLinkClass}><FiAlertOctagon /> <span>{t('nav.disputes', 'Disputes')}</span></NavLink>
              <NavLink to="/admin/failed-refunds" className={navLinkClass}><FiDollarSign /> <span>{t('nav.failed_refunds', 'Failed Refunds')}</span></NavLink>
              {effectiveIsSuperAdmin && <NavLink to="/admin/register" className={navLinkClass}><FiShield /> <span>{t('nav.add_admin', 'Add Admin')}</span></NavLink>}
            </div>
          </div>

          <div className="admin-sidebar-footer">
            <button
              onClick={handleChangeBinge}
              className="nav-link nav-btn venue-btn admin-sidebar-venue-btn"
              title={t('nav.change_binge', 'Change venue')}
            >
              <FiMapPin />
              <span className="venue-details">
                <span className="venue-pill-label">{t('nav.venue', 'Venue')}</span>
                <span className="venue-name">{effectiveSelectedBinge?.name}</span>
              </span>
            </button>
            <span className="nav-user nav-admin-user"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
          </div>
        </aside>
      )}
    </>
  );
}
