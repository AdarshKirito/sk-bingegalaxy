import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useBinge } from '../context/BingeContext';
import { FiLogOut, FiUser, FiCalendar, FiHome, FiBarChart2, FiPlusCircle, FiMapPin } from 'react-icons/fi';
import './Navbar.css';

export default function Navbar() {
  const { user, isAuthenticated, isAdmin, isSuperAdmin, logout } = useAuth();
  const { selectedBinge, clearBinge } = useBinge();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/');
  };

  const handleChangeBinge = () => {
    clearBinge();
    navigate(isAdmin ? '/admin/binges' : '/binges');
  };

  return (
    <nav className="navbar">
      <div className="container navbar-inner">
        <Link to="/" className="navbar-brand">
          <span className="brand-icon">🎬</span>
          <span>SK Binge Galaxy</span>
        </Link>

        {isAuthenticated && selectedBinge && (
          <button onClick={handleChangeBinge} className="nav-link nav-btn" style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.85rem', color: '#f0c040' }} title="Change venue">
            <FiMapPin /> {selectedBinge.name}
          </button>
        )}

        <div className="navbar-links">
          {!isAuthenticated ? (
            <>
              <Link to="/login" className="nav-link">Login</Link>
              <Link to="/register" className="btn btn-primary btn-sm">Sign Up</Link>
            </>
          ) : isAdmin ? (
            <>
              <Link to="/admin/binges" className="nav-link"><FiMapPin /> Binges</Link>
              {selectedBinge && (
                <>
                  <Link to="/admin/dashboard" className="nav-link"><FiHome /> Dashboard</Link>
                  <Link to="/admin/bookings" className="nav-link"><FiCalendar /> Bookings</Link>
                  <Link to="/admin/book" className="nav-link"><FiPlusCircle /> Book Now</Link>
                  <Link to="/admin/blocked-dates" className="nav-link">Block Dates</Link>
                  <Link to="/admin/event-types" className="nav-link">Events</Link>
                  <Link to="/admin/users-config" className="nav-link">Users & Config</Link>
                  <Link to="/admin/reports" className="nav-link"><FiBarChart2 /> Reports</Link>
                  {isSuperAdmin && <Link to="/admin/register" className="nav-link"><FiUser /> Add Admin</Link>}
                </>
              )}
              <button onClick={handleLogout} className="nav-link nav-btn"><FiLogOut /> Logout</button>
            </>
          ) : (
            <>
              {selectedBinge ? (
                <>
                  <Link to="/dashboard" className="nav-link"><FiHome /> Home</Link>
                  <Link to="/book" className="nav-link"><FiCalendar /> Book Now</Link>
                  <Link to="/my-bookings" className="nav-link">My Bookings</Link>
                </>
              ) : null}
              <Link to="/binges" className="nav-link"><FiMapPin /> Venues</Link>
              <span className="nav-user"><FiUser /> {user.firstName}</span>
              <button onClick={handleLogout} className="nav-link nav-btn"><FiLogOut /> Logout</button>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
