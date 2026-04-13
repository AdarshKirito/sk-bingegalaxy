import { useState, useEffect } from 'react';
import { FiSun, FiMoon } from 'react-icons/fi';

function getInitialTheme() {
  const stored = localStorage.getItem('theme');
  if (stored === 'dark' || stored === 'light') return stored;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export default function ThemeToggle() {
  const [theme, setTheme] = useState(getInitialTheme);

  const toggleTheme = () => {
    document.documentElement.classList.add('theme-switching');
    setTheme(t => t === 'dark' ? 'light' : 'dark');
    window.setTimeout(() => {
      document.documentElement.classList.remove('theme-switching');
    }, 260);
  };

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('theme', theme);
  }, [theme]);

  return (
    <button
      className="theme-toggle"
      onClick={toggleTheme}
      aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
      title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
    >
      {theme === 'dark' ? <FiSun /> : <FiMoon />}
    </button>
  );
}
