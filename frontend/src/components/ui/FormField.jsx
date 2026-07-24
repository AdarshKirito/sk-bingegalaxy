import { forwardRef } from 'react';

const FormField = forwardRef(function FormField(
  { label, id, error, type = 'text', className = '', children, ...props },
  ref
) {
  const inputId = id || (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined);
  // A11Y-002: programmatically associate the error text with its control so
  // screen readers announce it. `aria-describedby` points at the error span's id;
  // `role="alert"` makes assistive tech read the message when it appears. Any
  // caller-supplied aria-describedby (e.g. a help hint) still wins via {...props}.
  const errorId = error && inputId ? `${inputId}-error` : undefined;

  return (
    <div className={`input-group ${className}`}>
      {label && <label htmlFor={inputId}>{label}</label>}
      {children || (
        type === 'textarea' ? (
          <textarea ref={ref} id={inputId} aria-invalid={!!error} aria-describedby={errorId} {...props} />
        ) : type === 'select' ? (
          <select ref={ref} id={inputId} aria-invalid={!!error} aria-describedby={errorId} {...props} />
        ) : (
          <input ref={ref} id={inputId} type={type} aria-invalid={!!error} aria-describedby={errorId} {...props} />
        )
      )}
      {error && (
        <span id={errorId} role="alert" style={{ color: 'var(--danger-text)', fontSize: '0.8rem', marginTop: '0.25rem' }}>
          {error}
        </span>
      )}
    </div>
  );
});

export default FormField;
