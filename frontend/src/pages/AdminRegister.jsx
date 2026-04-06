import { useState } from 'react';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import './Auth.css';

export default function AdminRegister() {
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    if (form.password !== form.confirmPassword) {
      const message = 'Passwords do not match';
      setError(message);
      toast.error(message);
      return;
    }
    if (form.password.length < 8) {
      const message = 'Password must be at least 8 characters long';
      setError(message);
      toast.error(message);
      return;
    }
    if (!/^[6-9]\d{9}$/.test(form.phone)) {
      const message = 'Enter a valid 10-digit Indian phone number';
      setError(message);
      toast.error(message);
      return;
    }
    setLoading(true);
    try {
      const { confirmPassword, ...data } = form;
      await authService.adminRegister(data);
      toast.success('New admin account created successfully!');
      setForm({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
      setError('');
    } catch (err) {
      const message = err.userMessage || 'Registration failed';
      setError(message);
      toast.error(message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card card">
        <h1>Add New Admin</h1>
        <p className="auth-subtitle">Create a new administrator account</p>
        {error && <div className="error-message">{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="grid-2">
            <div className="input-group">
              <label>First Name</label>
              <input name="firstName" value={form.firstName} onChange={handleChange} required minLength={2} placeholder="John" />
            </div>
            <div className="input-group">
              <label>Last Name</label>
              <input name="lastName" value={form.lastName} onChange={handleChange} required placeholder="Doe" />
            </div>
          </div>
          <div className="input-group">
            <label>Email</label>
            <input type="email" name="email" value={form.email} onChange={handleChange} required placeholder="admin@example.com" />
          </div>
          <div className="input-group">
            <label>Phone</label>
            <input name="phone" value={form.phone} onChange={handleChange} required pattern="[6-9][0-9]{9}" title="Enter a valid 10-digit Indian phone number" placeholder="9876543210" />
          </div>
          <div className="input-group">
            <label>Password</label>
            <input type="password" name="password" value={form.password} onChange={handleChange} required placeholder="••••••••" minLength={8} />
          </div>
          <div className="input-group">
            <label>Confirm Password</label>
            <input type="password" name="confirmPassword" value={form.confirmPassword} onChange={handleChange} required placeholder="••••••••" />
          </div>
          <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
            {loading ? 'Creating...' : 'Create Admin Account'}
          </button>
        </form>
        <p style={{ textAlign: 'center', marginTop: '1rem', color: 'var(--text-secondary)' }}>
          Super admin access is required to create additional admin accounts.
        </p>
      </div>
    </div>
  );
}
