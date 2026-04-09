import { Component } from 'react';

export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    if (import.meta.env.DEV) {
      console.error('ErrorBoundary caught:', error, errorInfo);
    }
  }

  handleReset = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback({ error: this.state.error, reset: this.handleReset });
      }

      return (
        <div style={{
          display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
          minHeight: '50vh', padding: '2rem', textAlign: 'center',
        }}>
          <h2 style={{ color: 'var(--danger, #ef4444)', marginBottom: '1rem' }}>Something went wrong</h2>
          <p style={{ color: 'var(--text-secondary, #64748b)', marginBottom: '1.5rem', maxWidth: '500px' }}>
            An unexpected error occurred. Please try again or refresh the page.
          </p>
          <div style={{ display: 'flex', gap: '0.75rem' }}>
            <button className="btn btn-primary" onClick={this.handleReset}>Try Again</button>
            <button className="btn btn-secondary" onClick={() => window.location.reload()}>Refresh Page</button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
