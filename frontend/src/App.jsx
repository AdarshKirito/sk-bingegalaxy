import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { AuthProvider, useAuth } from './context/AuthContext';
import { BingeProvider, useBinge } from './context/BingeContext';
import ErrorBoundary from './components/ErrorBoundary';

import Navbar from './components/Navbar';
import './pages/AdminExperience.css';

// Lazy-loaded pages — each becomes a separate chunk
const Home = lazy(() => import('./pages/Home'));
const Login = lazy(() => import('./pages/Login'));
const Register = lazy(() => import('./pages/Register'));
const ForgotPassword = lazy(() => import('./pages/ForgotPassword'));
const ResetPassword = lazy(() => import('./pages/ResetPassword'));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const BookingPage = lazy(() => import('./pages/BookingPage'));
const BookingConfirmation = lazy(() => import('./pages/BookingConfirmation'));
const MyBookings = lazy(() => import('./pages/MyBookings'));
const CustomerPayments = lazy(() => import('./pages/CustomerPayments'));
const AccountCenter = lazy(() => import('./pages/AccountCenter'));
const PaymentPage = lazy(() => import('./pages/PaymentPage'));
const AdminLogin = lazy(() => import('./pages/AdminLogin'));
const AdminRegister = lazy(() => import('./pages/AdminRegister'));
const AdminDashboard = lazy(() => import('./pages/AdminDashboard'));
const AdminBookings = lazy(() => import('./pages/AdminBookings'));
const AdminBlockedDates = lazy(() => import('./pages/AdminBlockedDates'));
const AdminEventTypes = lazy(() => import('./pages/AdminEventTypes'));
const AdminReports = lazy(() => import('./pages/AdminReports'));
const AdminBookingCreate = lazy(() => import('./pages/AdminBookingCreate'));
const AdminUsersConfig = lazy(() => import('./pages/AdminUsersConfig'));
const AdminRateCodes = lazy(() => import('./pages/AdminRateCodes'));
const AdminCustomerPricing = lazy(() => import('./pages/AdminCustomerPricing'));
const BingeManagement = lazy(() => import('./pages/BingeManagement'));
const BingeSelector = lazy(() => import('./pages/BingeSelector'));
const PlatformDashboard = lazy(() => import('./pages/PlatformDashboard'));
const AdminEntranceDashboard = lazy(() => import('./pages/AdminEntranceDashboard'));
const AdminAccount = lazy(() => import('./pages/AdminAccount'));
const AdminAllUsers = lazy(() => import('./pages/AdminAllUsers'));
const CompleteProfile = lazy(() => import('./pages/CompleteProfile'));
const NotFound = lazy(() => import('./pages/NotFound'));

function PageLoader() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '40vh' }}>
      <div style={{ textAlign: 'center', color: 'var(--text-secondary)' }}>
        <div style={{ width: '36px', height: '36px', border: '3px solid var(--border)', borderTopColor: 'var(--primary)', borderRadius: '50%', animation: 'spin 0.8s linear infinite', margin: '0 auto 0.75rem' }} />
        Loading...
      </div>
    </div>
  );
}

function resolveAuthenticatedLanding(isAdmin, user) {
  if (isAdmin) return '/admin/platform';
  return user?.phone ? '/platform' : '/complete-profile';
}

function PublicOnlyRoute({ children }) {
  const { isAuthenticated, isAdmin, user, loading } = useAuth();
  if (loading) return <PageLoader />;
  if (isAuthenticated && user?.active !== false) {
    return <Navigate to={resolveAuthenticatedLanding(isAdmin, user)} replace />;
  }
  return children;
}

function CompleteProfileRoute({ children }) {
  const { isAuthenticated, isAdmin, user, loading } = useAuth();
  if (loading) return <PageLoader />;
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (isAdmin) return <Navigate to="/admin/platform" replace />;
  if (user?.phone) return <Navigate to="/platform" replace />;
  return children;
}

function ProtectedRoute({ children }) {
  const { isAuthenticated, isAdmin, user, loading } = useAuth();
  if (loading) return <PageLoader />;
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (user && !user.active) return <Navigate to="/login" replace />;
  if (isAdmin) return <Navigate to="/admin/platform" replace />;
  if (!user?.phone) return <Navigate to="/complete-profile" replace />;
  return children;
}

function AdminRoute({ children }) {
  const { isAuthenticated, isAdmin, user, loading } = useAuth();
  if (loading) return <PageLoader />;
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  if (user && !user.active) return <Navigate to="/admin/login" replace />;
  return children;
}

function SuperAdminRoute({ children }) {
  const { isAuthenticated, isAdmin, isSuperAdmin, loading } = useAuth();
  if (loading) return <PageLoader />;
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  if (!isSuperAdmin) return <Navigate to="/admin/platform" replace />;
  return children;
}

/* Requires binge selected — redirects to selector if not */
function BingeRequired({ children }) {
  const { isAuthenticated } = useAuth();
  const { selectedBinge } = useBinge();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (!selectedBinge) return <Navigate to="/platform" replace />;
  return children;
}

function AdminBingeRequired({ children }) {
  const { isAuthenticated, isAdmin } = useAuth();
  const { selectedBinge } = useBinge();
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  if (!selectedBinge) return <Navigate to="/admin/platform" replace />;
  return children;
}

function AppFrame() {
  const location = useLocation();
  const isAdminRoute = location.pathname.startsWith('/admin');

  return (
    <>
      <a href="#main-content" className="skip-link">Skip to main content</a>
      <Navbar />
      <ErrorBoundary>
      <main
        id="main-content"
        role="main"
        className={isAdminRoute ? 'app-main-admin' : undefined}
        style={{ minHeight: 'calc(100vh - 70px)', paddingTop: '1.5rem', paddingBottom: '3rem' }}
      >
        <Suspense fallback={<PageLoader />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/login" element={<PublicOnlyRoute><Login /></PublicOnlyRoute>} />
          <Route path="/register" element={<PublicOnlyRoute><Register /></PublicOnlyRoute>} />
          <Route path="/forgot-password" element={<PublicOnlyRoute><ForgotPassword /></PublicOnlyRoute>} />
          <Route path="/reset-password" element={<PublicOnlyRoute><ResetPassword /></PublicOnlyRoute>} />
          <Route path="/complete-profile" element={<CompleteProfileRoute><CompleteProfile /></CompleteProfileRoute>} />
          <Route path="/platform" element={<ProtectedRoute><PlatformDashboard /></ProtectedRoute>} />
          <Route path="/binges" element={<ProtectedRoute><BingeSelector /></ProtectedRoute>} />

          <Route path="/dashboard" element={<BingeRequired><Dashboard /></BingeRequired>} />
          <Route path="/book" element={<BingeRequired><BookingPage /></BingeRequired>} />
          <Route path="/booking/:ref" element={<BingeRequired><BookingConfirmation /></BingeRequired>} />
          <Route path="/my-bookings" element={<BingeRequired><MyBookings /></BingeRequired>} />
          <Route path="/payments" element={<BingeRequired><CustomerPayments /></BingeRequired>} />
          <Route path="/account" element={<ProtectedRoute><AccountCenter /></ProtectedRoute>} />
          <Route path="/payment/:ref" element={<BingeRequired><PaymentPage /></BingeRequired>} />

          <Route path="/admin/login" element={<AdminLogin />} />
          <Route path="/admin/register" element={<SuperAdminRoute><AdminRegister /></SuperAdminRoute>} />
          <Route path="/admin/platform" element={<AdminRoute><AdminEntranceDashboard /></AdminRoute>} />
          <Route path="/admin/account" element={<AdminRoute><AdminAccount /></AdminRoute>} />
          <Route path="/admin/all-users" element={<SuperAdminRoute><AdminAllUsers /></SuperAdminRoute>} />
          <Route path="/admin/binges" element={<AdminRoute><BingeManagement /></AdminRoute>} />
          <Route path="/admin/dashboard" element={<AdminBingeRequired><AdminDashboard /></AdminBingeRequired>} />
          <Route path="/admin/bookings" element={<AdminBingeRequired><AdminBookings /></AdminBingeRequired>} />
          <Route path="/admin/blocked-dates" element={<AdminBingeRequired><AdminBlockedDates /></AdminBingeRequired>} />
          <Route path="/admin/event-types" element={<AdminBingeRequired><AdminEventTypes /></AdminBingeRequired>} />
          <Route path="/admin/rate-codes" element={<AdminBingeRequired><AdminRateCodes /></AdminBingeRequired>} />
          <Route path="/admin/customer-pricing" element={<AdminBingeRequired><AdminCustomerPricing /></AdminBingeRequired>} />
          <Route path="/admin/reports" element={<AdminBingeRequired><AdminReports /></AdminBingeRequired>} />
          <Route path="/admin/book" element={<AdminBingeRequired><AdminBookingCreate /></AdminBingeRequired>} />
          <Route path="/admin/users-config" element={<AdminBingeRequired><AdminUsersConfig /></AdminBingeRequired>} />
          <Route path="/admin/users-config/:userId" element={<AdminBingeRequired><AdminUsersConfig /></AdminBingeRequired>} />

          <Route path="*" element={<NotFound />} />
        </Routes>
        </Suspense>
      </main>
      </ErrorBoundary>
      <ToastContainer position="top-right" theme="dark" autoClose={3000} />
    </>
  );
}

function App() {
  return (
    <ErrorBoundary>
    <BrowserRouter>
      <AuthProvider>
        <BingeProvider>
          <AppFrame />
        </BingeProvider>
      </AuthProvider>
    </BrowserRouter>
    </ErrorBoundary>
  );
}

export default App;
