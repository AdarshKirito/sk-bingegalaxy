export default function Card({ children, className = '', selected, onClick, style, ...props }) {
  const classes = ['card', selected && 'selected', className].filter(Boolean).join(' ');
  return (
    <div className={classes} onClick={onClick} style={style} {...props}>
      {children}
    </div>
  );
}
