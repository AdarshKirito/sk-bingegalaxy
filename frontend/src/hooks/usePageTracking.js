import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { trackPageView } from '../services/analytics';

/**
 * Tracks page views on every route change.
 * Drop this component inside your <BrowserRouter>.
 */
export default function usePageTracking() {
  const location = useLocation();
  useEffect(() => {
    trackPageView(location.pathname + location.search);
  }, [location]);
}
