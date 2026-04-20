/**
 * NavDropdownGroup
 *
 * A single nav item that is either:
 *   - a direct NavLink  (when `to` is provided)
 *   - a dropdown group  (when `items` is provided)
 *
 * Used in the admin console sub-bar to create grouped navigation like
 * GitHub, Vercel, Stripe, and Linear use in their top navbars.
 *
 * Example groups:
 *   Bookings ▾  →  All Bookings / New Booking / Blocked Dates
 *   Venue ▾     →  Rooms / Event Types / Rate Codes / Surge Rules
 *   People ▾    →  Users / Waitlist / Add Admin
 */
import { useState, useRef, useEffect } from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { FiChevronDown } from 'react-icons/fi';

export default function NavDropdownGroup({ icon, label, to, items = [] }) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef(null);
  const location = useLocation();

  // Close on any navigation
  useEffect(() => { setOpen(false); }, [location.pathname]);

  // Close when clicking outside
  useEffect(() => {
    if (!open) return;
    const handler = (e) => {
      if (!wrapRef.current?.contains(e.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', handler, true);
    return () => document.removeEventListener('pointerdown', handler, true);
  }, [open]);

  // Direct link — no dropdown
  if (to) {
    return (
      <NavLink
        to={to}
        className={({ isActive }) => `nav-link${isActive ? ' nav-link-active' : ''}`}
      >
        {icon}
        <span>{label}</span>
      </NavLink>
    );
  }

  // Dropdown group — check if any child route is currently active
  const isAnyChildActive = items.some(
    (item) => location.pathname === item.to || location.pathname.startsWith(item.to + '/')
  );

  return (
    <div ref={wrapRef} className="nav-group">
      <button
        className={`nav-link nav-group-trigger${isAnyChildActive ? ' nav-link-active' : ''}${open ? ' nav-group-trigger-open' : ''}`}
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="true"
        aria-expanded={open}
      >
        {icon}
        <span>{label}</span>
        <FiChevronDown
          className={`nav-group-chevron${open ? ' nav-group-chevron-open' : ''}`}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div className="nav-group-dropdown" role="menu">
          {items.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `nav-group-item${isActive ? ' nav-group-item-active' : ''}`
              }
              role="menuitem"
              onClick={() => setOpen(false)}
            >
              <span className="nav-group-item-icon" aria-hidden="true">{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}
