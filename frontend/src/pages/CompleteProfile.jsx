import { useState } from 'react';
import { useAuth } from '../context/AuthContext';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import AddressFields, { EMPTY_ADDRESS, validateAddress } from '../components/form/AddressFields';
import PhoneField, { splitPhone, validatePhone } from '../components/form/PhoneField';
import './Auth.css';

export default function CompleteProfile() {
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState({ ...EMPTY_ADDRESS });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const { user, setUser } = useAuth();

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    const phoneError = validatePhone(phone, { required: true });
    if (phoneError) {
      setError(phoneError);
      toast.error(phoneError);
      return;
    }
    const addressErrors = validateAddress(address);
    if (Object.keys(addressErrors).length) {
      const msg = Object.values(addressErrors)[0];
      setError(msg);
      toast.error(msg);
      return;
    }
    setLoading(true);
    try {
      const phoneSplit = splitPhone(phone);
      const res = await authService.completeProfile({
        phone: phoneSplit.phone,
        phoneCountryCode: phoneSplit.phoneCountryCode,
        addressLine1: address.addressLine1 || '',
        addressLine2: address.addressLine2 || '',
        city: address.city || '',
        state: address.state || '',
        country: address.country || '',
        postalCode: address.postalCode || '',
      });
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
          Welcome{user?.firstName ? `, ${user.firstName}` : ''}! Add a phone number and (optionally) your address to continue.
        </p>

        {error && <div className="error-message">{error}</div>}

        <form onSubmit={handleSubmit}>
          <PhoneField
            label="Phone Number"
            required
            value={phone}
            onChange={setPhone}
            helpText="Pick the country and we'll handle the dial-code automatically."
          />
          <AddressFields
            legend="Address (optional)"
            description="Helps us send swag, vouchers, and reminders to the right place."
            value={address}
            onChange={setAddress}
          />
          <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
            {loading ? 'Saving...' : 'Continue'}
          </button>
        </form>
      </div>
    </div>
  );
}
