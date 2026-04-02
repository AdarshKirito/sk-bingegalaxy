import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';

export default function AdminRegister() {
  const navigate = useNavigate();
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
  const [loading, setLoading] = useState(false);

  const handleChange = (e) => setForm({ ...form, [e.target.name]: e.target.value });

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (form.password !== form.confirmPassword) {
      toast.error('Passwords do not match');
      return;
    }
    setLoading(true);
    try {
      const { confirmPassword, ...data } = form;
      await authService.adminRegister(data);
      toast.success('New admin account created successfully!');
      setForm({ firstName: '', lastName: '', email: '', phone: '', password: '', confirmPassword: '' });
    } catch (err) {
      toast.error(err.userMessage || 'Registration failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="container" style={{ maxWidth: 480, margin: '3rem auto' }}>
      <div className="card">
        <h2 style={{ textAlign: 'center', marginBottom: '0.5rem' }}>Add New Admin</h2>
        <p style={{ textAlign: 'center', color: 'var(--text-secondary)', marginBottom: '1.5rem', fontSize: '0.9rem' }}>Create a new administrator account</p>
        <form onSubmit={handleSubmit}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
            <div className="form-group">
              <label>First Name</label>
              <input name="firstName" value={form.firstName} onChange={handleChange} required className="form-control" />
            </div>
            <div className="form-group">
              <label>Last Name</label>
              <input name="lastName" value={form.lastName} onChange={handleChange} required className="form-control" />
            </div>
          </div>
          <div className="form-group">
            <label>Email</label>
            <input type="email" name="email" value={form.email} onChange={handleChange} required className="form-control" />
          </div>
          <div className="form-group">
            <label>Phone</label>
            <input name="phone" value={form.phone} onChange={handleChange} required className="form-control" />
          </div>
          <div className="form-group">
            <label>Password</label>
            <input type="password" name="password" value={form.password} onChange={handleChange} required className="form-control" minLength={6} />
          </div>
          <div className="form-group">
            <label>Confirm Password</label>
            <input type="password" name="confirmPassword" value={form.confirmPassword} onChange={handleChange} required className="form-control" />
          </div>
          <button type="submit" className="btn btn-primary" style={{ width: '100%', marginTop: '1rem' }} disabled={loading}>
            {loading ? 'Creating...' : 'Create Admin Account'}
          </button>
        </form>
      </div>
    </div>
  );
}
