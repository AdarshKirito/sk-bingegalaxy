import { useState, useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { toast } from 'react-toastify';
import { FiCheckCircle, FiClock, FiEye, FiEyeOff, FiShield } from 'react-icons/fi';
import SEO from '../components/SEO';
import './Auth.css';

const GOOGLE_CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID;
const GOOGLE_SCRIPT_SRC = 'https://accounts.google.com/gsi/client';
const GOOGLE_LOAD_TIMEOUT_MS = 4000;
if (!GOOGLE_CLIENT_ID) {
  console.warn('VITE_GOOGLE_CLIENT_ID is not configured — Google login will be unavailable');
}
let googleInitialized = false;

function ensureGoogleScript() {
  const existingScript = document.querySelector(`script[src="${GOOGLE_SCRIPT_SRC}"]`);
  if (existingScript) {
    return existingScript;
  }

  const script = document.createElement('script');
  script.src = GOOGLE_SCRIPT_SRC;
  script.async = true;
  script.defer = true;
  document.head.appendChild(script);
  return script;
}

export default function Login() {
  const [form, setForm] = useState({ email: '', password: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [showPw, setShowPw] = useState(false);
  const [googleStatus, setGoogleStatus] = useState(GOOGLE_CLIENT_ID ? 'loading' : 'unavailable');
  const [googleHint, setGoogleHint] = useState(
    GOOGLE_CLIENT_ID
      ? 'Loading Google sign-in...'
      : 'Google sign-in is not configured right now. Please use email sign-in.'
  );
  const { login, googleLogin } = useAuth();
  const googleBtnRef = useRef(null);
  const googleCallbackRef = useRef(null);
  const googlePollRef = useRef(null);

  // Keep callback ref always up-to-date so Google's callback never uses a stale closure
  googleCallbackRef.current = async (response) => {
    setError('');
    setLoading(true);
    try {
      await googleLogin(response.credential);
      toast.success('Welcome!');
      // PublicOnlyRoute redirects to /binges or /complete-profile based on user.phone
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Google login failed.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  const stopGooglePolling = () => {
    if (googlePollRef.current) {
      clearInterval(googlePollRef.current);
      googlePollRef.current = null;
    }
  };

  const initializeGoogleClient = () => {
    if (!GOOGLE_CLIENT_ID || !window.google?.accounts?.id) {
      return false;
    }

    if (!googleInitialized) {
      window.google.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        callback: (response) => googleCallbackRef.current(response),
        ux_mode: 'popup',
      });
      googleInitialized = true;
    }

    return true;
  };

  const renderGoogleButton = () => {
    if (!googleBtnRef.current || !initializeGoogleClient()) {
      return false;
    }

    try {
      googleBtnRef.current.innerHTML = '';
      window.google.accounts.id.renderButton(googleBtnRef.current, {
        theme: 'outline',
        size: 'large',
        width: 392,
        text: 'signin_with',
        shape: 'rectangular',
      });
      setGoogleStatus('ready');
      setGoogleHint('');
      return true;
    } catch (renderError) {
      console.error('Failed to render Google sign-in button', renderError);
      setGoogleStatus('retry');
      setGoogleHint('Google sign-in could not render. Retry below or use email sign-in.');
      return false;
    }
  };

  const beginGoogleLoad = () => {
    if (!GOOGLE_CLIENT_ID) {
      setGoogleStatus('unavailable');
      setGoogleHint('Google sign-in is not configured right now. Please use email sign-in.');
      return;
    }

    stopGooglePolling();
    ensureGoogleScript();
    setGoogleStatus('loading');
    setGoogleHint('Loading Google sign-in...');

    if (renderGoogleButton()) {
      return;
    }

    const startedAt = Date.now();
    googlePollRef.current = setInterval(() => {
      if (renderGoogleButton()) {
        stopGooglePolling();
        return;
      }

      if (Date.now() - startedAt >= GOOGLE_LOAD_TIMEOUT_MS) {
        stopGooglePolling();
        setGoogleStatus('retry');
        setGoogleHint('Google sign-in did not load in time. Retry below or use email sign-in.');
      }
    }, 250);
  };

  useEffect(() => {
    beginGoogleLoad();
    return () => stopGooglePolling();
  }, []);

  const handleGoogleRetry = () => {
    setError('');
    beginGoogleLoad();

    if (!initializeGoogleClient()) {
      return;
    }

    try {
      window.google.accounts.id.prompt();
      setGoogleHint('Opening Google sign-in...');
    } catch (promptError) {
      console.error('Google sign-in prompt failed', promptError);
      setGoogleStatus('retry');
      setGoogleHint('Google sign-in is still unavailable. Please retry or use email sign-in.');
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    const trimmedEmail = form.email.trim();
    if (!trimmedEmail) { setError('Email is required'); toast.error('Email is required'); return; }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmedEmail)) { setError('Please enter a valid email address'); toast.error('Please enter a valid email address'); return; }
    if (!form.password) { setError('Password is required'); toast.error('Password is required'); return; }
    setLoading(true);
    try {
      await login({ email: trimmedEmail, password: form.password });
      toast.success('Welcome back!');
      // PublicOnlyRoute redirects once isAuthenticated becomes true
    } catch (err) {
      const msg = err.userMessage || err.response?.data?.message || 'Login failed. Please check your credentials.';
      setError(msg);
      toast.error(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page auth-page-login">
      <SEO title="Login" description="Sign in to your SK Binge Galaxy account to manage bookings." />

      <div className="auth-shell auth-shell-login">
        <section className="auth-showcase auth-showcase-login">
          <span className="auth-kicker">Member access</span>
          <h1>Welcome back to your private screening journey.</h1>
          <p className="auth-lead">
            Sign in to continue bookings, complete payments, review preferences, and move straight back into the experience without starting over.
          </p>

          <div className="auth-showcase-card">
            <span className="auth-card-label">Inside your account</span>
            <ul className="auth-benefit-list">
              <li><FiCheckCircle /> Active bookings, payment follow-ups, and booking history in one place.</li>
              <li><FiShield /> Protected customer access with your existing SK Binge Galaxy account.</li>
              <li><FiClock /> Faster return to saved plans, dates, and guest details.</li>
            </ul>
          </div>

          <div className="auth-stat-row" aria-hidden="true">
            <article>
              <strong>Private</strong>
              <span>booking flow</span>
            </article>
            <article>
              <strong>Fast</strong>
              <span>return to your next event</span>
            </article>
          </div>
        </section>

        <div className="auth-card-wrap">
          <div className="auth-card card">
            <span className="auth-card-eyebrow">Sign in</span>
            <h2>Welcome Back</h2>
            <p className="auth-subtitle">Use your SK Binge Galaxy account to continue where you left off.</p>

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

            {GOOGLE_CLIENT_ID && googleStatus !== 'ready' && (
              <button
                type="button"
                className="auth-google-fallback"
                onClick={handleGoogleRetry}
                disabled={googleStatus === 'loading'}
              >
                {googleStatus === 'loading' ? (
                  <>
                    <span className="btn-spinner" />
                    Loading Google Sign-In...
                  </>
                ) : (
                  <>
                    <span className="auth-google-badge">G</span>
                    Continue with Google
                  </>
                )}
              </button>
            )}

            <div ref={googleBtnRef} className={`google-btn-container${googleStatus === 'ready' ? ' is-ready' : ''}`}></div>

            {googleHint && (
              <p className={`auth-google-hint${googleStatus === 'retry' || googleStatus === 'unavailable' ? ' is-warning' : ''}`}>
                {googleHint}
              </p>
            )}

            <div className="auth-links">
              <Link to="/forgot-password">Forgot password?</Link>
              <span>Don't have an account? <Link to="/register">Sign up</Link></span>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
