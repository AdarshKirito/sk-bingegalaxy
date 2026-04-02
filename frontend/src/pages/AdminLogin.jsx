import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import './Auth.css';

export default function AdminLogin() {
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const { adminLogin } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await adminLogin(form);
      toast.success('Welcome, Admin!');
      navigate('/admin/binges');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Admin login failed. Please check your credentials.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card card">
        <h1>Admin Login</h1>
        <p className="auth-subtitle">SK Binge Galaxy Administration</p>

        {error && <div className="error-message">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="input-group">
            <label>Email</label>
            <input type="email" required value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
              placeholder="admin@skbingegalaxy.com" />
          </div>
          <div className="input-group">
            <label>Password</label>
            <input type="password" required value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              placeholder="••••••••" />
          </div>
          <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
            {loading ? 'Signing in...' : 'Sign In as Admin'}
          </button>
        </form>
        <p style={{ textAlign: 'center', marginTop: '1rem' }}>
          Don't have an admin account? <Link to="/admin/register">Sign Up</Link>
        </p>
      </div>
    </div>
  );
}
