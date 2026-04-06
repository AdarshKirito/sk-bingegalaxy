import { useState, useEffect, useRef, useCallback } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import { FiEye, FiEyeOff } from 'react-icons/fi';
import SEO from '../components/SEO';
import './Auth.css';

const GOOGLE_CLIENT_ID = '437058573375-97f1e70umbnqqn6anlp4pn7c2h2h3v1o.apps.googleusercontent.com';
let googleInitialized = false;

export default function Login() {
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [showPw, setShowPw] = useState(false);
  const { login, googleLogin, isAuthenticated, user } = useAuth();
  const navigate = useNavigate();
  const googleBtnRef = useRef(null);
  const googleCallbackRef = useRef(null);

  // Redirect if already logged in
  useEffect(() => {
    if (isAuthenticated) {
      navigate(user?.phone ? '/binges' : '/complete-profile', { replace: true });
    }
  }, [isAuthenticated]);

  // Keep callback ref always up-to-date so Google's callback never uses a stale closure
  googleCallbackRef.current = async (response) => {
    setError('');
    setLoading(true);
    try {
      const userData = await googleLogin(response.credential);
      toast.success('Welcome!');
      const dest = userData.phone ? '/binges' : '/complete-profile';
      navigate(dest, { replace: true });
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Google login failed.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    let interval;
    let cancelled = false;
    const initGoogle = () => {
      if (cancelled) return true;
      if (window.google?.accounts?.id && googleBtnRef.current) {
        if (!googleInitialized) {
          window.google.accounts.id.initialize({
            client_id: GOOGLE_CLIENT_ID,
            callback: (resp) => googleCallbackRef.current(resp),
            ux_mode: 'popup',
          });
          googleInitialized = true;
        }
        window.google.accounts.id.renderButton(googleBtnRef.current, {
          theme: 'outline',
          size: 'large',
          width: 392,
          text: 'signin_with',
          shape: 'rectangular',
        });
        return true;
      }
      return false;
    };
    if (!initGoogle()) {
      interval = setInterval(() => {
        if (initGoogle()) clearInterval(interval);
      }, 300);
    }
    return () => { cancelled = true; if (interval) clearInterval(interval); };
  }, []);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await login(form);
      toast.success('Welcome back!');
      navigate('/binges');
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Login failed. Please check your credentials.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <SEO title="Login" description="Sign in to your SK Binge Galaxy account to manage bookings." />
      <div className="auth-card card">
        <h1>Welcome Back</h1>
        <p className="auth-subtitle">Sign in to your account</p>

        {error && <div className="error-message">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="input-group">
            <label>Email</label>
            <input type="email" required value={form.email}
              onChange={(e) => setForm({ ...form, email: e.target.value })}
              placeholder="you@example.com" autoFocus />
          </div>
          <div className="input-group">
            <label>Password</label>
            <div className="input-password-wrap">
              <input type={showPw ? 'text' : 'password'} required value={form.password}
                onChange={(e) => setForm({ ...form, password: e.target.value })}
                placeholder="••••••••" />
              <button type="button" className="pw-toggle" onClick={() => setShowPw(v => !v)} aria-label={showPw ? 'Hide password' : 'Show password'} tabIndex={-1}>
                {showPw ? <FiEyeOff /> : <FiEye />}
              </button>
            </div>
          </div>
          <button type="submit" className="btn btn-primary auth-btn" disabled={loading}>
            {loading ? <><span className="btn-spinner" /> Signing in...</> : 'Sign In'}
          </button>
        </form>

        <div className="auth-divider">
          <span>or continue with</span>
        </div>

        <div ref={googleBtnRef} className="google-btn-container"></div>

        <div className="auth-links">
          <Link to="/forgot-password">Forgot password?</Link>
          <span>Don't have an account? <Link to="/register">Sign up</Link></span>
        </div>
      </div>
    </div>
  );
}
