import { useState } from 'react';
import { authService } from '../services/endpoints';
import { toast } from 'react-toastify';
import {
  FiShield, FiSmartphone, FiKey, FiCheck, FiAlertTriangle, FiCopy, FiLock,
} from 'react-icons/fi';
import SEO from '../components/SEO';
import './AdminSecurity.css';

/**
 * Two-step MFA enrollment.
 *  1. POST /mfa/enroll → returns { secret, otpauthUri, recoveryCodes }.
 *  2. User scans the otpauth:// URI with their authenticator app and types back the code.
 *     POST /mfa/confirm with { code, recoveryCodes } persists the hashed recovery codes
 *     and marks MFA enabled.
 *
 * Recovery codes are shown exactly once. The UI nags the user to save them.
 */
export default function MfaSetup() {
  const [stage, setStage] = useState('intro'); // intro | enrolled | confirmed
  const [payload, setPayload] = useState(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);

  const beginEnroll = async () => {
    setBusy(true);
    try {
      const res = await authService.enrollMfa();
      setPayload(res.data?.data);
      setStage('enrolled');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to start MFA enrollment');
    } finally { setBusy(false); }
  };

  const confirm = async (e) => {
    e?.preventDefault();
    if (!code.trim()) { toast.error('Enter the 6-digit code'); return; }
    setBusy(true);
    try {
      await authService.confirmMfa({ code: code.trim(), recoveryCodes: payload.recoveryCodes });
      toast.success('Two-factor authentication enabled');
      setStage('confirmed');
    } catch (err) {
      toast.error(err.response?.data?.message || 'Invalid code');
    } finally { setBusy(false); }
  };

  const disable = async () => {
    const entered = window.prompt('Enter a current code from your authenticator app to disable MFA');
    if (!entered) return;
    try {
      await authService.disableMfa({ code: entered.trim() });
      toast.success('MFA disabled');
      setStage('intro');
      setPayload(null);
      setCode('');
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

  const activeStep =
    stage === 'intro' ? 0 : stage === 'confirmed' ? 3 : payload && code.length >= 6 ? 3 : 2;

  return (
    <div className="sec-page">
      <SEO title="Two-factor authentication" description="Protect your account with a TOTP authenticator app." />

      <div className="sec-header">
        <div className="sec-header-copy">
          <span className="sec-kicker"><FiShield /> ACCOUNT SECURITY</span>
          <h1>Two-factor authentication</h1>
          <p>
            Add a time-based one-time password (TOTP) step to every sign-in. We recommend
            Google Authenticator, Authy, 1Password, or Microsoft Authenticator.
          </p>
        </div>
      </div>

      <div className="mfa-wizard">
        <ol className="mfa-steps">
          <li className={stage === 'intro' ? 'active' : 'done'}>
            <span className="mfa-step-num">{stage === 'intro' ? '1' : <FiCheck />}</span>
            Get started
          </li>
          <li className={stage === 'enrolled' ? 'active' : activeStep > 1 ? 'done' : ''}>
            <span className="mfa-step-num">{activeStep > 1 ? <FiCheck /> : '2'}</span>
            Scan secret
          </li>
          <li className={stage === 'enrolled' ? 'active' : activeStep > 2 ? 'done' : ''}>
            <span className="mfa-step-num">{activeStep > 2 ? <FiCheck /> : '3'}</span>
            Save recovery codes
          </li>
          <li className={stage === 'confirmed' ? 'active done' : ''}>
            <span className="mfa-step-num">{stage === 'confirmed' ? <FiCheck /> : '4'}</span>
            Verify &amp; enable
          </li>
        </ol>

        <div className="mfa-body">
          {stage === 'intro' && (
            <div className="mfa-panel">
              <div className="sec-card-head">
                <div>
                  <h2><FiSmartphone style={{ verticalAlign: '-3px' }} /> Enable two-factor auth</h2>
                  <p>
                    Once enabled, sign-in will require a 6-digit code from your authenticator app
                    in addition to your password.
                  </p>
                </div>
              </div>
              <div className="sec-actions" style={{ marginTop: 12 }}>
                <button className="sec-btn sec-btn-primary" onClick={beginEnroll} disabled={busy}>
                  {busy ? 'Starting…' : <><FiShield /> Enable 2FA</>}
                </button>
                <button className="sec-btn" onClick={disable}>
                  <FiLock /> I already have 2FA — disable it
                </button>
              </div>
            </div>
          )}

          {stage === 'enrolled' && payload && (
            <>
              <div className="mfa-panel">
                <h2><FiSmartphone style={{ verticalAlign: '-3px' }} /> Step 1 · Add to your authenticator</h2>
                <p style={{ color: 'var(--text-muted)', fontSize: 14, margin: '4px 0 12px' }}>
                  Copy the <strong>setup key</strong> into your authenticator app, or paste the
                  <code> otpauth://</code> URI if your app accepts it.
                </p>

                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.1em' }}>
                  Setup key
                </label>
                <div style={{ display: 'flex', gap: 8, alignItems: 'stretch' }}>
                  <code className="mfa-code" style={{ flex: 1 }}>{payload.secret}</code>
                  <button className="sec-btn" onClick={() => copy(payload.secret, 'Secret')}>
                    <FiCopy /> Copy
                  </button>
                </div>

                <label style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.1em', marginTop: 12, display: 'block' }}>
                  otpauth:// URI
                </label>
                <div style={{ display: 'flex', gap: 8, alignItems: 'stretch' }}>
                  <code className="mfa-code" style={{ flex: 1 }}>{payload.otpauthUri}</code>
                  <button className="sec-btn" onClick={() => copy(payload.otpauthUri, 'URI')}>
                    <FiCopy /> Copy
                  </button>
                </div>
              </div>

              <div className="mfa-panel">
                <h2><FiKey style={{ verticalAlign: '-3px' }} /> Step 2 · Save your recovery codes</h2>
                <div className="mfa-warning">
                  <FiAlertTriangle size={18} />
                  <span>
                    <strong>Save these codes somewhere safe right now.</strong> They let you regain access if
                    you lose your authenticator device. They will <strong>never be shown again</strong>.
                  </span>
                </div>
                <div className="mfa-recovery">
                  {payload.recoveryCodes.map((rc) => <code key={rc}>{rc}</code>)}
                </div>
                <button
                  className="sec-btn"
                  onClick={() => copy(payload.recoveryCodes.join('\n'), 'Recovery codes')}
                >
                  <FiCopy /> Copy all codes
                </button>
              </div>

              <div className="mfa-panel">
                <h2><FiShield style={{ verticalAlign: '-3px' }} /> Step 3 · Verify &amp; enable</h2>
                <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>
                  Enter the current 6-digit code from your authenticator app to finish turning on 2FA.
                </p>
                <form onSubmit={confirm}>
                  <div className="mfa-input-row">
                    <input
                      type="text"
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      maxLength={8}
                      value={code}
                      onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                      placeholder="123 456"
                      autoFocus
                    />
                    <button
                      type="submit"
                      className="sec-btn sec-btn-primary"
                      disabled={busy || code.length < 6}
                    >
                      {busy ? 'Confirming…' : 'Confirm & enable'}
                    </button>
                  </div>
                </form>
              </div>
            </>
          )}

          {stage === 'confirmed' && (
            <div className="mfa-panel">
              <div className="mfa-success">
                <div className="mfa-success-icon"><FiCheck /></div>
                <h2 style={{ margin: '4px 0' }}>Two-factor auth is on</h2>
                <p style={{ color: 'var(--text-muted)', maxWidth: 420, margin: '6px auto 18px' }}>
                  From now on, every sign-in will ask for a 6-digit code from your authenticator app.
                  Keep your recovery codes somewhere safe — you'll need them if you lose your device.
                </p>
                <button className="sec-btn sec-btn-danger" onClick={disable}>
                  <FiLock /> Disable 2FA
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
