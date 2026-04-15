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
} from 'react-icons/fi';
import ThemeToggle from './ThemeToggle';
import './Navbar.css';

export default function Navbar() {
  const { user, isAuthenticated, isAdmin, isSuperAdmin, loading, logout } = useAuth();
  const { selectedBinge, clearBinge } = useBinge();
  const { i18n } = useTranslation();
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
    await logout();
    navigate('/');
  };

  const handleChangeBinge = () => {
    clearBinge();
    navigate(effectiveIsAdmin ? '/admin/platform' : '/binges');
  };

  const customerLinks = [
    { to: '/dashboard', icon: <FiHome />, label: 'Dashboard' },
    { to: '/book', icon: <FiCalendar />, label: 'Book' },
    { to: '/my-bookings', icon: <FiCompass />, label: 'Bookings' },
    { to: '/payments', icon: <FiCreditCard />, label: 'Payments' },
    { to: '/about', icon: <FiInfo />, label: 'About' },
  ];

  const adminConsoleLinks = [
    { to: '/admin/dashboard', icon: <FiHome />, label: 'Dashboard' },
    { to: '/admin/bookings', icon: <FiCalendar />, label: 'Bookings' },
    { to: '/admin/book', icon: <FiPlusCircle />, label: 'Create' },
    { to: '/admin/blocked-dates', icon: <FiClock />, label: 'Availability' },
    { to: '/admin/event-types', icon: <FiSettings />, label: 'Catalog' },
    { to: '/admin/rate-codes', icon: <FiSettings />, label: 'Rate Codes' },
    { to: '/admin/venue-rooms', icon: <FiGrid />, label: 'Rooms' },
    { to: '/admin/surge-rules', icon: <FiZap />, label: 'Surge Rules' },
    { to: '/admin/users-config', icon: <FiUsers />, label: 'Users' },
    { to: '/admin/reports', icon: <FiBarChart2 />, label: 'Reports' },
  ];

  const accountLink = { to: '/account', icon: <FiUser />, label: 'Account' };

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
              <NavLink to="/login" className={publicAuthLinkClass(isLoginRoute)}>Login</NavLink>
              <NavLink to="/register" className={publicAuthLinkClass(isRegisterRoute)}>Sign Up</NavLink>
            </>
          ) : effectiveIsAdmin ? (
            <>
              {!effectiveSelectedBinge && (
                <div className="nav-admin-entry">
                  <NavLink to="/admin/platform" className={navLinkClass}><FiHome /> Dashboard</NavLink>
                  <NavLink to="/admin/binges" className={navLinkClass}><FiMapPin /> Binges</NavLink>
                  <NavLink to="/admin/account" className={navLinkClass}><FiUser /> Account</NavLink>
                  {effectiveIsSuperAdmin && <NavLink to="/admin/all-users" className={navLinkClass}><FiUsers /> Users</NavLink>}
                  {effectiveIsSuperAdmin && <NavLink to="/admin/register" className={navLinkClass}><FiShield /> Add Admin</NavLink>}
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
                      <NavLink to="/admin/register" className={navLinkClass}><FiShield /> <span>Add Admin</span></NavLink>
                    )}
                  </div>
                  <div className="nav-customer-meta nav-mobile-only nav-admin-meta">
                    <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn" title="Change venue">
                      <FiMapPin />
                      <span className="venue-details">
                        <span className="venue-pill-label">Venue</span>
                        <span className="venue-name">{effectiveSelectedBinge.name}</span>
                      </span>
                    </button>
                    <span className="nav-user nav-admin-user"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
                    <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn"><FiLogOut /> <span className="nav-action-label">Logout</span></button>
                  </div>
                </div>
              )}

              {!effectiveSelectedBinge && <button onClick={handleLogout} className="nav-link nav-btn nav-mobile-only"><FiLogOut /> Logout</button>}
            </>
          ) : (
            <>
              {effectiveSelectedBinge ? (
                <div className="nav-customer-links">
                  {customerLinks.map(link => (
                    <NavLink key={link.to} to={link.to} className={navLinkClass}>
                      {link.icon}
                      <span>{link.label}</span>
                    </NavLink>
                  ))}
                  <NavLink to={accountLink.to} className={navLinkClass}>
                    {accountLink.icon}
                    <span>{accountLink.label}</span>
                  </NavLink>
                </div>
              ) : null}
              {!effectiveSelectedBinge && (
                <>
                  <NavLink to="/platform" className={navLinkClass}><FiHome /> Dashboard</NavLink>
                  <NavLink to="/binges" className={navLinkClass}><FiMapPin /> Venues</NavLink>
                  <NavLink to={accountLink.to} className={navLinkClass}><FiUser /> Account</NavLink>
                </>
              )}
              <div className="nav-customer-meta nav-mobile-only">
                {effectiveSelectedBinge && (
                  <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn" title="Change venue">
                    <FiMapPin />
                    <span className="venue-details">
                      <span className="venue-pill-label">Venue</span>
                      <span className="venue-name">{effectiveSelectedBinge.name}</span>
                    </span>
                  </button>
                )}
                <span className="nav-user"><FiUser /> <span>{effectiveUser?.firstName}</span><small>Customer</small></span>
                <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn"><FiLogOut /> <span className="nav-action-label">Logout</span></button>
              </div>
            </>
          )}
        </div>

        <div className="navbar-right">
          {effectiveIsAuthenticated && effectiveIsAdmin && !effectiveSelectedBinge && (
            <span className="nav-user nav-admin-user nav-desktop-only"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
          )}

          {effectiveIsAuthenticated && effectiveIsAdmin && !effectiveSelectedBinge && (
            <button onClick={handleLogout} className="nav-link nav-btn nav-desktop-only nav-logout-btn" aria-label="Logout">
              <FiLogOut /> <span className="nav-action-label">Logout</span>
            </button>
          )}

          {effectiveIsAuthenticated && !effectiveIsAdmin && effectiveSelectedBinge && (
            <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn nav-desktop-only" title="Change venue">
              <FiMapPin />
              <span className="venue-details">
                <span className="venue-pill-label">Venue</span>
                <span className="venue-name">{effectiveSelectedBinge.name}</span>
              </span>
            </button>
          )}

          {effectiveIsAuthenticated && !effectiveIsAdmin && (
            <span className="nav-user nav-desktop-only"><FiUser /> <span>{effectiveUser?.firstName}</span><small>Customer</small></span>
          )}

          {effectiveIsAuthenticated && !effectiveIsAdmin && (
            <button onClick={handleLogout} className="nav-link nav-btn nav-desktop-only nav-logout-btn" aria-label="Logout">
              <FiLogOut /> <span className="nav-action-label">Logout</span>
            </button>
          )}

          <ThemeToggle />
          <button
            className="lang-toggle-btn"
            onClick={() => { const next = i18n.language === 'en' ? 'hi' : 'en'; i18n.changeLanguage(next); localStorage.setItem('lang', next); }}
            aria-label="Switch language"
            title={i18n.language === 'en' ? 'हिन्दी' : 'English'}
          >
            {i18n.language === 'en' ? 'हि' : 'EN'}
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

      {effectiveIsAuthenticated && effectiveIsAdmin && effectiveSelectedBinge && (
        <div className="container nav-admin-console">
          <div className="nav-admin-links">
            {adminConsoleLinks.map(link => (
              <NavLink key={link.to} to={link.to} className={navLinkClass}>
                {link.icon}
                <span>{link.label}</span>
              </NavLink>
            ))}
            {effectiveIsSuperAdmin && (
              <NavLink to="/admin/register" className={navLinkClass}><FiShield /> <span>Add Admin</span></NavLink>
            )}
          </div>

          <div className="nav-admin-status">
            <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn" title="Change venue">
              <FiMapPin />
              <span className="venue-details">
                <span className="venue-pill-label">Venue</span>
                <span className="venue-name">{effectiveSelectedBinge.name}</span>
              </span>
            </button>
            <span className="nav-user nav-admin-user"><FiShield /> <span>{effectiveUser?.firstName}</span><small>{adminRoleLabel}</small></span>
            <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn" aria-label="Logout">
              <FiLogOut /> <span className="nav-action-label">Logout</span>
            </button>
          </div>
        </div>
      )}
    </nav>
  );
}
