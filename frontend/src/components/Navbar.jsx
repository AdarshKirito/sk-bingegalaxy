import { useState, useEffect } from 'react';
import { Link, NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { FiLogOut, FiUser, FiCalendar, FiHome, FiBarChart2, FiPlusCircle, FiMapPin, FiMenu, FiX, FiCreditCard, FiCompass } from 'react-icons/fi';
import ThemeToggle from './ThemeToggle';
import './Navbar.css';

export default function Navbar() {
  const { user, isAuthenticated, isAdmin, isSuperAdmin, logout } = useAuth();
  const { selectedBinge, clearBinge } = useBinge();
  const navigate = useNavigate();
  const location = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);

  // Close menu on route change
  useEffect(() => { setMenuOpen(false); }, [location.pathname]);

  // Prevent body scroll when menu is open
  useEffect(() => {
    document.body.style.overflow = menuOpen ? 'hidden' : '';
    return () => { document.body.style.overflow = ''; };
  }, [menuOpen]);

  const handleLogout = () => {
    logout();
    navigate('/');
  };

  const handleChangeBinge = () => {
    clearBinge();
    navigate(isAdmin ? '/admin/binges' : '/binges');
  };

  const customerLinks = [
    { to: '/dashboard', icon: <FiHome />, label: 'Dashboard' },
    { to: '/book', icon: <FiCalendar />, label: 'Book' },
    { to: '/my-bookings', icon: <FiCompass />, label: 'Bookings' },
    { to: '/payments', icon: <FiCreditCard />, label: 'Payments' },
  ];

  const accountLink = { to: '/account', icon: <FiUser />, label: 'Account' };

  const navLinkClass = ({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`;

  return (
    <nav className="navbar" aria-label="Main navigation">
      <div className="container navbar-inner">
        <Link to="/" className="navbar-brand" aria-label="SK Binge Galaxy home">
          <span className="brand-icon" aria-hidden="true">🎬</span>
          <span className="brand-copy">
            <strong>SK Binge Galaxy</strong>
            <span className="brand-meta">{isAdmin ? 'Admin Console' : 'Customer Hub'}</span>
          </span>
        </Link>

        {menuOpen && <div className="menu-overlay" onClick={() => setMenuOpen(false)} />}

        <div className={`navbar-links ${menuOpen ? 'open' : ''}`}>
          {!isAuthenticated ? (
            <>
              <Link to="/login" className="nav-link">Login</Link>
              <Link to="/register" className="btn btn-primary btn-sm">Sign Up</Link>
            </>
          ) : isAdmin ? (
            <>
              <NavLink to="/admin/binges" className={navLinkClass}><FiMapPin /> Binges</NavLink>
              {selectedBinge && (
                <>
                  <NavLink to="/admin/dashboard" className={navLinkClass}><FiHome /> Dashboard</NavLink>
                  <NavLink to="/admin/bookings" className={navLinkClass}><FiCalendar /> Bookings</NavLink>
                  <NavLink to="/admin/book" className={navLinkClass}><FiPlusCircle /> Book Now</NavLink>
                  <NavLink to="/admin/blocked-dates" className={navLinkClass}>Block Dates</NavLink>
                  <NavLink to="/admin/event-types" className={navLinkClass}>Events</NavLink>
                  <NavLink to="/admin/users-config" className={navLinkClass}>Users & Config</NavLink>
                  <NavLink to="/admin/reports" className={navLinkClass}><FiBarChart2 /> Reports</NavLink>
                  {isSuperAdmin && <NavLink to="/admin/register" className={navLinkClass}><FiUser /> Add Admin</NavLink>}
                </>
              )}
              <button onClick={handleLogout} className="nav-link nav-btn"><FiLogOut /> Logout</button>
            </>
          ) : (
            <>
              {selectedBinge ? (
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
              {!selectedBinge && (
                <>
                  <NavLink to="/binges" className={navLinkClass}><FiMapPin /> Venues</NavLink>
                  <NavLink to={accountLink.to} className={navLinkClass}><FiUser /> Account</NavLink>
                </>
              )}
              <div className="nav-customer-meta nav-mobile-only">
                {selectedBinge && (
                  <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn" title="Change venue">
                    <FiMapPin />
                    <span className="venue-details">
                      <span className="venue-pill-label">Venue</span>
                      <span className="venue-name">{selectedBinge.name}</span>
                    </span>
                  </button>
                )}
                <span className="nav-user"><FiUser /> <span>{user?.firstName}</span><small>Customer</small></span>
                <button onClick={handleLogout} className="nav-link nav-btn nav-logout-btn"><FiLogOut /> <span className="nav-action-label">Logout</span></button>
              </div>
            </>
          )}
        </div>

        <div className="navbar-right">
          {isAuthenticated && !isAdmin && selectedBinge && (
            <button onClick={handleChangeBinge} className="nav-link nav-btn venue-btn nav-desktop-only" title="Change venue">
              <FiMapPin />
              <span className="venue-details">
                <span className="venue-pill-label">Venue</span>
                <span className="venue-name">{selectedBinge.name}</span>
              </span>
            </button>
          )}

          {isAuthenticated && !isAdmin && (
            <span className="nav-user nav-desktop-only"><FiUser /> <span>{user?.firstName}</span><small>Customer</small></span>
          )}

          {isAuthenticated && !isAdmin && (
            <button onClick={handleLogout} className="nav-link nav-btn nav-desktop-only nav-logout-btn" aria-label="Logout">
              <FiLogOut /> <span className="nav-action-label">Logout</span>
            </button>
          )}

          <ThemeToggle />
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
    </nav>
  );
}
