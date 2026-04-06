import { forwardRef } from 'react';

const Button = forwardRef(function Button(
  { variant = 'primary', size, disabled, children, className = '', ...props },
  ref
) {
  const classes = [
    'btn',
    `btn-${variant}`,
    size === 'sm' && 'btn-sm',
    className,
  ].filter(Boolean).join(' ');

  return (
    <button ref={ref} className={classes} disabled={disabled} {...props}>
      {children}
    </button>
  );
});

export default Button;
