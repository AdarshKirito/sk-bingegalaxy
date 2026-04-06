import { forwardRef } from 'react';

const FormField = forwardRef(function FormField(
  { label, id, error, type = 'text', className = '', children, ...props },
  ref
) {
  const inputId = id || (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined);

  return (
    <div className={`input-group ${className}`}>
      {label && <label htmlFor={inputId}>{label}</label>}
      {children || (
        type === 'textarea' ? (
          <textarea ref={ref} id={inputId} aria-invalid={!!error} {...props} />
        ) : type === 'select' ? (
          <select ref={ref} id={inputId} aria-invalid={!!error} {...props} />
        ) : (
          <input ref={ref} id={inputId} type={type} aria-invalid={!!error} {...props} />
        )
      )}
      {error && <span style={{ color: 'var(--danger)', fontSize: '0.8rem', marginTop: '0.25rem' }}>{error}</span>}
    </div>
  );
});

export default FormField;
