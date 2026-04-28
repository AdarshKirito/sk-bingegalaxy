import { useEffect, useMemo, useRef, useState } from 'react';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import {
  FiShield, FiSmartphone, FiKey, FiCheck, FiAlertTriangle, FiCopy,
  FiLock, FiDownload, FiExternalLink, FiX, FiArrowRight,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import './MfaSetup.css';

/**
 * Two-step MFA enrollment — redesigned to match top-tier patterns
 * (GitHub / Stripe / Google security UX):
 *  1. Intro card → benefits + single primary CTA.
 *  2. Setup card → grouped secret + deep-link to authenticator app.
 *  3. Recovery codes → download / copy with explicit acknowledgement.
 *  4. Verify → segmented 6-box OTP input with auto-advance.
 *  5. Confirmation state with proper "Disable" modal (no native prompt()).
 */
export default function MfaSetup() {
  const [stage, setStage] = useState('intro'); // intro | enrolled | confirmed
  const [payload, setPayload] = useState(null);
  const [digits, setDigits] = useState(['', '', '', '', '', '']);
  const [busy, setBusy] = useState(false);
  const [savedAck, setSavedAck] = useState(false);
  const [showDisable, setShowDisable] = useState(false);
  const [disableCode, setDisableCode] = useState('');
  const inputsRef = useRef([]);

  const code = digits.join('');
  const codeFilled = code.length === 6;

  // Format the TOTP secret in groups of 4 for readability (Stripe / GitHub style).
  const formattedSecret = useMemo(() => {
    if (!payload?.secret) return '';
    return payload.secret.replace(/(.{4})/g, '$1 ').trim();
  }, [payload?.secret]);

  const beginEnroll = async () => {
    setBusy(true);
    try {
      const res = await authService.enrollMfa();
      setPayload(res.data?.data);
      setStage('enrolled');
      setSavedAck(false);
      setDigits(['', '', '', '', '', '']);
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to start MFA enrollment');
    } finally { setBusy(false); }
  };

  const confirm = async (e) => {
    e?.preventDefault();
    if (!codeFilled) { toast.error('Enter the 6-digit code'); return; }
    setBusy(true);
    try {
      await authService.confirmMfa({ code, recoveryCodes: payload.recoveryCodes });
      toast.success('Two-factor authentication enabled');
      setStage('confirmed');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Invalid code');
      setDigits(['', '', '', '', '', '']);
      inputsRef.current[0]?.focus();
    } finally { setBusy(false); }
  };

  const disable = async () => {
    if (!disableCode.trim()) { toast.error('Enter your current 6-digit code'); return; }
    try {
      await authService.disableMfa({ code: disableCode.trim() });
      toast.success('MFA disabled');
      setStage('intro');
      setPayload(null);
      setDigits(['', '', '', '', '', '']);
      setShowDisable(false);
      setDisableCode('');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to disable MFA');
    }
  };

  const copy = async (text, label) => {
    try {
      await navigator.clipboard.writeText(text);
      toast.success(`${label} copied`);
    } catch {
      toast.error('Could not copy to clipboard');
    }
  };

  const downloadRecovery = () => {
    if (!payload?.recoveryCodes) return;
    const header = 'SK Binge Galaxy — Two-factor recovery codes\n' +
      `Generated: ${new Date().toISOString()}\n` +
      'Each code can be used once if you lose access to your authenticator.\n\n';
    const blob = new Blob([header + payload.recoveryCodes.join('\n') + '\n'], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'sk-binge-galaxy-recovery-codes.txt';
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(url);
  };

  // Segmented OTP input handlers
  const setDigit = (i, val) => {
    const v = val.replace(/\D/g, '').slice(-1);
    setDigits((d) => {
      const n = [...d]; n[i] = v; return n;
    });
    if (v && i < 5) inputsRef.current[i + 1]?.focus();
  };
  const onKeyDown = (i, e) => {
    if (e.key === 'Backspace' && !digits[i] && i > 0) {
      inputsRef.current[i - 1]?.focus();
    } else if (e.key === 'ArrowLeft' && i > 0) {
      inputsRef.current[i - 1]?.focus();
    } else if (e.key === 'ArrowRight' && i < 5) {
      inputsRef.current[i + 1]?.focus();
    }
  };
  const onPaste = (e) => {
    const txt = (e.clipboardData?.getData('text') || '').replace(/\D/g, '').slice(0, 6);
    if (!txt) return;
    e.preventDefault();
    const arr = ['', '', '', '', '', ''];
    for (let i = 0; i < txt.length; i++) arr[i] = txt[i];
    setDigits(arr);
    const last = Math.min(txt.length, 5);
    inputsRef.current[last]?.focus();
  };

  useEffect(() => {
    if (stage === 'enrolled' && savedAck) {
      inputsRef.current[0]?.focus();
    }
  }, [stage, savedAck]);

  const stepIndex =
    stage === 'intro' ? 0 :
    stage === 'confirmed' ? 3 :
    !savedAck ? 1 : 2;

  return (
    <div className="mfa2-page">
      <SEO title="Two-factor authentication" description="Protect your account with a TOTP authenticator app." />

      <div className="mfa2-shell">
        {/* Hero */}
        <div className="mfa2-hero">
          <div className="mfa2-hero-icon" aria-hidden><FiShield /></div>
          <div>
            <span className="mfa2-kicker">Account security</span>
            <h1>Two-factor authentication</h1>
            <p>
              Protect your account with a second sign-in step from an authenticator app
              like Google Authenticator, Authy, 1Password, or Microsoft Authenticator.
            </p>
          </div>
        </div>

        {/* Stepper */}
        {stage !== 'confirmed' && (
          <ol className="mfa2-stepper" aria-label="Setup progress">
            {['Get started', 'Add to app', 'Save codes', 'Verify'].map((label, i) => (
              <li
                key={label}
                className={
                  i < stepIndex ? 'is-done' :
                  i === stepIndex ? 'is-active' : ''
                }
              >
                <span className="mfa2-step-dot">
                  {i < stepIndex ? <FiCheck /> : i + 1}
                </span>
                <span className="mfa2-step-label">{label}</span>
              </li>
            ))}
          </ol>
        )}

        {/* ───── INTRO ───── */}
        {stage === 'intro' && (
          <section className="mfa2-card">
            <div className="mfa2-card-head">
              <h2>Why turn on 2FA?</h2>
              <p>It only takes a minute and dramatically reduces account-takeover risk.</p>
            </div>

            <ul className="mfa2-benefits">
              <li>
                <span className="mfa2-bullet"><FiLock /></span>
                <div>
                  <strong>Stops password leaks cold.</strong>
                  <span>Even if your password is stolen, attackers can't sign in without your phone.</span>
                </div>
              </li>
              <li>
                <span className="mfa2-bullet"><FiSmartphone /></span>
                <div>
                  <strong>Works with any authenticator app.</strong>
                  <span>Google Authenticator, Authy, 1Password, Microsoft Authenticator and more.</span>
                </div>
              </li>
              <li>
                <span className="mfa2-bullet"><FiKey /></span>
                <div>
                  <strong>Recovery codes included.</strong>
                  <span>10 single-use backup codes in case you lose your device.</span>
                </div>
              </li>
            </ul>

            <div className="mfa2-actions">
              <button className="mfa2-btn mfa2-btn-primary" onClick={beginEnroll} disabled={busy}>
                {busy ? 'Starting…' : <>Enable 2FA <FiArrowRight /></>}
              </button>
              <button className="mfa2-btn mfa2-btn-ghost" onClick={() => setShowDisable(true)}>
                I already have 2FA — disable it
              </button>
            </div>
          </section>
        )}

        {/* ───── ENROLLED ───── */}
        {stage === 'enrolled' && payload && (
          <>
            <section className="mfa2-card">
              <div className="mfa2-card-head">
                <h2>1. Add the key to your authenticator</h2>
                <p>Open your authenticator app and scan or paste the setup key below.</p>
              </div>

              <div className="mfa2-secret-box">
                <span className="mfa2-secret-label">Setup key</span>
                <code className="mfa2-secret">{formattedSecret}</code>
                <button
                  className="mfa2-icon-btn"
                  onClick={() => copy(payload.secret, 'Setup key')}
                  aria-label="Copy setup key"
                  type="button"
                >
                  <FiCopy />
                </button>
              </div>

              <div className="mfa2-deeplink">
                <a
                  className="mfa2-btn mfa2-btn-ghost"
                  href={payload.otpauthUri}
                  target="_blank"
                  rel="noreferrer"
                >
                  <FiExternalLink /> Open in authenticator app
                </a>
                <button
                  className="mfa2-btn mfa2-btn-ghost"
                  onClick={() => copy(payload.otpauthUri, 'otpauth URI')}
                  type="button"
                >
                  <FiCopy /> Copy URI
                </button>
              </div>
            </section>

            <section className="mfa2-card">
              <div className="mfa2-card-head">
                <h2>2. Save your recovery codes</h2>
                <p>You'll need these if you lose access to your authenticator. They are shown once.</p>
              </div>

              <div className="mfa2-warning">
                <FiAlertTriangle />
                <span>
                  Store these somewhere safe — a password manager or printed copy.
                  Each code can be used <strong>only once</strong>.
                </span>
              </div>

              <div className="mfa2-recovery">
                {payload.recoveryCodes.map((rc) => <code key={rc}>{rc}</code>)}
              </div>

              <div className="mfa2-actions">
                <button className="mfa2-btn mfa2-btn-ghost" onClick={downloadRecovery} type="button">
                  <FiDownload /> Download .txt
                </button>
                <button
                  className="mfa2-btn mfa2-btn-ghost"
                  onClick={() => copy(payload.recoveryCodes.join('\n'), 'Recovery codes')}
                  type="button"
                >
                  <FiCopy /> Copy all
                </button>
              </div>

              <label className="mfa2-ack">
                <input
                  type="checkbox"
                  checked={savedAck}
                  onChange={(e) => setSavedAck(e.target.checked)}
                />
                <span>I've saved my recovery codes in a safe place</span>
              </label>
            </section>

            <section className={`mfa2-card ${!savedAck ? 'is-disabled' : ''}`}>
              <div className="mfa2-card-head">
                <h2>3. Verify the 6-digit code</h2>
                <p>Enter the current code shown in your authenticator app.</p>
              </div>

              <form onSubmit={confirm} onPaste={onPaste}>
                <div className="mfa2-otp">
                  {digits.map((d, i) => (
                    <input
                      key={i}
                      ref={(el) => { inputsRef.current[i] = el; }}
                      type="text"
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      maxLength={1}
                      value={d}
                      onChange={(e) => setDigit(i, e.target.value)}
                      onKeyDown={(e) => onKeyDown(i, e)}
                      onFocus={(e) => e.target.select()}
                      disabled={!savedAck}
                      aria-label={`Digit ${i + 1}`}
                    />
                  ))}
                </div>

                <div className="mfa2-actions mfa2-actions-end">
                  <button
                    type="submit"
                    className="mfa2-btn mfa2-btn-primary"
                    disabled={busy || !codeFilled || !savedAck}
                  >
                    {busy ? 'Verifying…' : <>Confirm &amp; enable <FiArrowRight /></>}
                  </button>
                </div>
              </form>
            </section>
          </>
        )}

        {/* ───── CONFIRMED ───── */}
        {stage === 'confirmed' && (
          <section className="mfa2-card mfa2-card-success">
            <div className="mfa2-success-icon"><FiCheck /></div>
            <h2>Two-factor authentication is on</h2>
            <p>
              From now on every sign-in will ask for a 6-digit code from your authenticator.
              Keep your recovery codes somewhere safe — you'll need them if you lose your device.
            </p>
            <div className="mfa2-actions mfa2-actions-center">
              <button className="mfa2-btn mfa2-btn-danger" onClick={() => setShowDisable(true)}>
                <FiLock /> Disable 2FA
              </button>
            </div>
          </section>
        )}
      </div>

      {/* ───── Disable modal ───── */}
      {showDisable && (
        <div className="mfa2-modal-overlay" role="dialog" aria-modal="true" aria-label="Disable 2FA">
          <div className="mfa2-modal">
            <button
              className="mfa2-modal-close"
              onClick={() => { setShowDisable(false); setDisableCode(''); }}
              aria-label="Close"
              type="button"
            >
              <FiX />
            </button>
            <div className="mfa2-modal-icon"><FiAlertTriangle /></div>
            <h3>Disable two-factor authentication?</h3>
            <p>
              Your account will be less secure. Enter a current 6-digit code from your
              authenticator app to confirm.
            </p>
            <input
              type="text"
              inputMode="numeric"
              maxLength={8}
              value={disableCode}
              onChange={(e) => setDisableCode(e.target.value.replace(/\D/g, ''))}
              placeholder="123456"
              className="mfa2-modal-input"
              autoFocus
            />
            <div className="mfa2-actions mfa2-actions-end" style={{ marginTop: 14 }}>
              <button
                className="mfa2-btn mfa2-btn-ghost"
                onClick={() => { setShowDisable(false); setDisableCode(''); }}
                type="button"
              >
                Cancel
              </button>
              <button
                className="mfa2-btn mfa2-btn-danger"
                onClick={disable}
                disabled={disableCode.length < 6}
                type="button"
              >
                Disable 2FA
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
