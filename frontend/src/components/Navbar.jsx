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
  FiAward,
} from 'react-icons/fi';
import ThemeToggle from './ThemeToggle';

import NavDropdownGroup from './NavDropdownGroup';
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

  // Close menu on route change
  useEffect(() => { setMenuOpen(false); }, [location.pathname]);

  // Prevent body scroll when menu is open
  useEffect(() => {
    document.body.style.overflow = menuOpen ? 'hidden' : '';
    return () => { document.body.style.overflow = ''; };
  }, [menuOpen]);

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
    { to: '/admin/users-config', icon: <FiUsers />, label: t('nav.users') },
    { to: '/admin/reports', icon: <FiBarChart2 />, label: t('nav.reports', 'Reports') },
  ];

  const accountLink = { to: '/account', icon: <FiUser />, label: t('nav.account') };

  const navLinkClass = ({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`;
  const publicAuthLinkClass = (isActive) => `nav-auth-link${isActive ? ' nav-auth-link-active' : ''}`;

  return (
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
                <div className="nav-admin-entry">
                  <NavLink to="/admin/platform" className={navLinkClass}><FiHome /> {t('nav.dashboard')}</NavLink>
                  <NavLink to="/admin/binges" className={navLinkClass}><FiMapPin /> {t('nav.binges')}</NavLink>
                  <NavLink to="/admin/account" className={navLinkClass}><FiUser /> {t('nav.account')}</NavLink>
                  {effectiveIsSuperAdmin && <NavLink to="/admin/all-users" className={navLinkClass}><FiUsers /> {t('nav.users')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/loyalty-center" className={navLinkClass}><FiAward /> {t('nav.loyalty_center', 'Loyalty Center')}</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/register" className={navLinkClass}><FiShield /> {t('nav.add_admin', 'Add Admin')}</NavLink>}
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

              {!effectiveSelectedBinge && <button onClick={handleLogout} className="nav-link nav-btn nav-mobile-only" aria-label={t('nav.logout')}><FiLogOut /> {t('nav.logout')}</button>}
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
          {effectiveIsAuthenticated && effectiveIsAdmin && !effectiveSelectedBinge && (
            <span className="nav-user nav-admin-user nav-desktop-only"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
          )}

          {effectiveIsAuthenticated && effectiveIsAdmin && !effectiveSelectedBinge && (
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
              ]}
            />

            <NavDropdownGroup
              icon={<FiGrid />}
              label={t('nav.venue', 'Venue')}
              items={[
                { to: '/admin/venue-rooms',  icon: <FiGrid />,     label: t('nav.venue_rooms', 'Rooms') },
                { to: '/admin/event-types',  icon: <FiSettings />, label: t('nav.event_types') },
                { to: '/admin/rate-codes',   icon: <FiSettings />, label: t('nav.rate_codes') },
                { to: '/admin/surge-rules',  icon: <FiZap />,      label: t('nav.surge_rules', 'Surge Rules') },
              ]}
            />

            <NavDropdownGroup
              icon={<FiUsers />}
              label={t('nav.users')}
              items={[
                { to: '/admin/users-config', icon: <FiUsers />,  label: t('nav.users') },
                { to: '/admin/waitlist',     icon: <FiList />,   label: t('nav.waitlist', 'Waitlist') },
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
            <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn" aria-label={t('nav.logout')}>
              <FiLogOut /> <span className="nav-action-label">{t('nav.logout')}</span>
            </button>
          </div>
        </div>
      )}
    </nav>
  );
}
