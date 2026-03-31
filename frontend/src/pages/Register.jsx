import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import './Auth.css';

export default function Register() {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const { register } = useAuth();
  const navigate = useNavigate();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    if (!form.firstName.trim()) { toast.error('First name is required'); return; }
    if (!form.email.trim()) { toast.error('Email is required'); return; }
    if (!form.phone.trim()) { toast.error('Phone number is required'); return; }
    if (form.password.length < 6) { toast.error('Password must be at least 6 characters'); return; }
    if (form.password !== form.confirmPassword) {
      const msg = 'Passwords do not match';
      setError(msg);
      toast.error(msg);
      return;
    }
    setLoading(true);
    try {
      const { confirmPassword, ...data } = form;
      await register(data);
      toast.success('Account created!');
      navigate('/dashboard');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Registration failed. Please try again.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  const update = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  return (
    <div className="auth-page">
      <div className="auth-card card">
        <h1>Create Account</h1>
        <p className="auth-subtitle">Join SK Binge Galaxy today</p>

        {error && <div className="error-message">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="grid-2">
            <div className="input-group">
              <label>First Name</label>
              <input required value={form.firstName} onChange={update('firstName')} placeholder="John" />
            </div>
            <div className="input-group">
              <label>Last Name</label>
              <input required value={form.lastName} onChange={update('lastName')} placeholder="Doe" />
            </div>
          </div>
          <div className="input-group">
            <label>Email</label>
            <input type="email" required value={form.email} onChange={update('email')} placeholder="you@example.com" />
          </div>
          <div className="input-group">
            <label>Phone</label>
            <input type="tel" required value={form.phone} onChange={update('phone')} placeholder="+91 9876543210" />
          </div>
          <div className="input-group">
            <label>Password</label>
            <input type="password" required minLength={6} value={form.password} onChange={update('password')} placeholder="Min 6 characters" />
          </div>
          <div className="input-group">
            <label>Confirm Password</label>
            <input type="password" required value={form.confirmPassword} onChange={update('confirmPassword')} placeholder="••••••••" />
          </div>
          <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
            {loading ? 'Creating...' : 'Create Account'}
          </button>
        </form>

        <div className="auth-links">
          <span>Already have an account? <Link to="/login">Sign in</Link></span>
        </div>
      </div>
    </div>
  );
}
