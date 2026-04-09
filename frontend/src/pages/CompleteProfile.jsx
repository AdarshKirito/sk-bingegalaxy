import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import './Auth.css';

export default function CompleteProfile() {
  const [phone, setPhone] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const { user, setUser } = useAuth();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    if (!/^[6-9]\d{9}$/.test(phone)) {
      const msg = 'Please enter a valid 10-digit Indian phone number';
      setError(msg);
      toast.error(msg);
      return;
    }
    setLoading(true);
    try {
      const res = await authService.completeProfile({ phone });
      const updatedUser = res.data.data.user;
      // setUser writes the minimal shape to localStorage and updates store;
      // CompleteProfileRoute then redirects to /binges once user.phone is set
      setUser(updatedUser);
      toast.success('Profile completed!');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Failed to update profile';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-card card" style={{ margin: '0 auto' }}>
        <h1>Complete Your Profile</h1>
        <p className="auth-subtitle">
          Welcome{user?.firstName ? `, ${user.firstName}` : ''}! Please add your phone number to continue.
        </p>

        {error && <div className="error-message">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="input-group">
            <label>Phone Number</label>
            <input
              type="tel"
              required
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              placeholder="9876543210"
              pattern="[6-9]\d{9}"
              maxLength={10}
            />
          </div>
          <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
            {loading ? 'Saving...' : 'Continue'}
          </button>
        </form>
      </div>
    </div>
  );
}
