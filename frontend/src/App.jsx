import { lazy, Suspense, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { AuthProvider, useAuth } from './context/AuthContext';
import { BingeProvider, useBinge } from './context/BingeContext';
import { CurrencyProvider } from './context/CurrencyContext';
import ErrorBoundary from './components/ErrorBoundary';
import PWAUpdatePrompt from './components/PWAUpdatePrompt';
import { trackPageView } from './services/analytics';

import Navbar from './components/Navbar';
import DelegationBanner from './components/authority/DelegationBanner';
import { ConfirmProvider } from './components/ui/ConfirmProvider';
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
const Membership = lazy(() => import('./pages/Membership'));
const AdminLoyaltyCenter = lazy(() => import('./pages/AdminLoyaltyCenter'));
const CustomerPayments = lazy(() => import('./pages/CustomerPayments'));
const AccountCenter = lazy(() => import('./pages/AccountCenter'));
const CustomerSettings = lazy(() => import('./pages/CustomerSettings'));
const PaymentPage = lazy(() => import('./pages/PaymentPage'));
const AboutBinge = lazy(() => import('./pages/AboutBinge'));
const AdminLogin = lazy(() => import('./pages/AdminLogin'));
const SuperAdminDashboard = lazy(() => import('./pages/SuperAdminDashboard'));
const AuthorityHandover = lazy(() => import('./pages/AuthorityHandover'));
const AdminHomeEditor = lazy(() => import('./pages/AdminHomeEditor'));
const MySessions = lazy(() => import('./pages/MySessions'));
const MfaSetup = lazy(() => import('./pages/MfaSetup'));
const VerifyEmail = lazy(() => import('./pages/VerifyEmail'));
const AdminRegister = lazy(() => import('./pages/AdminRegister'));
const AdminDashboard = lazy(() => import('./pages/AdminDashboard'));
const AdminBookings = lazy(() => import('./pages/AdminBookings'));
const AdminBlockedDates = lazy(() => import('./pages/AdminBlockedDates'));
const AdminEventTypes = lazy(() => import('./pages/AdminEventTypes'));
const AdminReports = lazy(() => import('./pages/AdminReports'));
const AdminBookingCreate = lazy(() => import('./pages/AdminBookingCreate'));
const AdminTermsEditor = lazy(() => import('./pages/AdminTermsEditor'));
const Terms = lazy(() => import('./pages/Terms'));
const AdminUsersConfig = lazy(() => import('./pages/AdminUsersConfig'));
const AdminRateCodes = lazy(() => import('./pages/AdminRateCodes'));
const AdminCustomerPricing = lazy(() => import('./pages/AdminCustomerPricing'));
const AdminCustomerEdit = lazy(() => import('./pages/AdminCustomerEdit'));
const AdminVenueRooms = lazy(() => import('./pages/AdminVenueRooms'));
const AdminSurgeRules = lazy(() => import('./pages/AdminSurgeRules'));
const AdminWaitlist = lazy(() => import('./pages/AdminWaitlist'));
const AdminCustomerFreezes = lazy(() => import('./pages/AdminCustomerFreezes'));
const AdminRiskFlags = lazy(() => import('./pages/AdminRiskFlags'));
const AdminSupportConsole = lazy(() => import('./pages/AdminSupportConsole'));
const AdminRecoveryQueues = lazy(() => import('./pages/AdminRecoveryQueues'));
const AdminApprovals = lazy(() => import('./pages/AdminApprovals'));
const AdminDisputes = lazy(() => import('./pages/AdminDisputes'));
const AdminFailedRefunds = lazy(() => import('./pages/AdminFailedRefunds'));
const AdminSlotHolds = lazy(() => import('./pages/AdminSlotHolds'));
const AdminTaxes = lazy(() => import('./pages/AdminTaxes'));
const AdminCurrencies = lazy(() => import('./pages/AdminCurrencies'));
const AdminAccountPageEditor = lazy(() => import('./pages/AdminAccountPageEditor'));
const AdminNotificationTemplates = lazy(() => import('./pages/AdminNotificationTemplates'));
const AdminOps = lazy(() => import('./pages/AdminOps'));
const CustomerNotifications = lazy(() => import('./pages/CustomerNotifications'));
const BingeManagement = lazy(() => import('./pages/BingeManagement'));
const BingeSelector = lazy(() => import('./pages/BingeSelector'));
const PlatformDashboard = lazy(() => import('./pages/PlatformDashboard'));
const AdminEntranceDashboard = lazy(() => import('./pages/AdminEntranceDashboard'));
const AdminAccount = lazy(() => import('./pages/AdminAccount'));
const AdminAllUsers = lazy(() => import('./pages/AdminAllUsers'));
const CompleteProfile = lazy(() => import('./pages/CompleteProfile'));
const TransferAccept = lazy(() => import('./pages/TransferAccept'));
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
  if (isAuthenticated && user?.active) {
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

/**
 * Gate for routes that historically required {@code role === SUPER_ADMIN}.
 *
 * Now also admits ADMINs who have an active Authority Handover delegation that
 * includes the supplied {@code scope}. When no scope is provided, the gate is
 * native-super-admin-only (used for the Authority Handover page itself, since
 * delegated admins must never be able to grant authority to themselves).
 *
 * Server-side enforcement is performed by the API gateway (it elevates
 * X-User-Role to SUPER_ADMIN only for paths matching the granted scope) and by
 * each downstream controller's existing role check. The frontend gate is for UX
 * only \u2014 a stale/forged client cannot bypass the gateway.
 */
function SuperAdminRoute({ children, scope }) {
  const { isAuthenticated, isAdmin, isSuperAdmin, effectiveAuthority, loading } = useAuth();
  if (loading) return <PageLoader />;
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  if (isSuperAdmin) return children;
  // Delegated admin path: only admit if the requested scope is in the active grant.
  if (scope && effectiveAuthority?.delegated && Array.isArray(effectiveAuthority.scopes)
      && effectiveAuthority.scopes.includes(scope)) {
    return children;
  }
  return <Navigate to="/admin/platform" replace />;
}

/* Requires binge selected — redirects to selector if not.
 * Separation of duties: staff (ADMIN/SUPER_ADMIN) accounts are bounced to the
 * admin console rather than transacting through the customer booking / My
 * Bookings / payments flow under their staff identity. A staff member who also
 * wants to be a customer uses a SEPARATE customer account (different email). */
function BingeRequired({ children }) {
  const { isAuthenticated, isAdmin } = useAuth();
  const { selectedBinge } = useBinge();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (isAdmin) return <Navigate to="/admin/platform" replace />;
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
  useEffect(() => { trackPageView(location.pathname + location.search); }, [location]);

  return (
    <>
      <a href="#main-content" className="skip-link">Skip to main content</a>
      <Navbar />
      {isAdminRoute && <DelegationBanner />}
      <PWAUpdatePrompt />
      <ErrorBoundary>
      <main
        id="main-content"
        role="main"
        className={isAdminRoute ? 'app-main-admin' : undefined}
        style={{ minHeight: 'calc(100vh - 70px)', paddingBottom: '3rem' }}
      >
        <Suspense fallback={<PageLoader />}>
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/login" element={<PublicOnlyRoute><Login /></PublicOnlyRoute>} />
          <Route path="/register" element={<PublicOnlyRoute><Register /></PublicOnlyRoute>} />
          <Route path="/forgot-password" element={<PublicOnlyRoute><ForgotPassword /></PublicOnlyRoute>} />
          <Route path="/reset-password" element={<PublicOnlyRoute><ResetPassword /></PublicOnlyRoute>} />
          <Route path="/verify-email" element={<VerifyEmail />} />
          <Route path="/terms" element={<Terms />} />
          {/* Booking-transfer magic link landing — public by design: the emailed
              token is the credential and the recipient may not have an account. */}
          <Route path="/transfers/:token" element={<TransferAccept />} />
          <Route path="/complete-profile" element={<CompleteProfileRoute><CompleteProfile /></CompleteProfileRoute>} />
          <Route path="/platform" element={<ProtectedRoute><PlatformDashboard /></ProtectedRoute>} />
          <Route path="/binges" element={<ProtectedRoute><BingeSelector /></ProtectedRoute>} />

          <Route path="/dashboard" element={<BingeRequired><Dashboard /></BingeRequired>} />
          <Route path="/book" element={<BingeRequired><BookingPage /></BingeRequired>} />
          <Route path="/booking/:ref" element={<BingeRequired><BookingConfirmation /></BingeRequired>} />
          <Route path="/my-bookings" element={<BingeRequired><MyBookings /></BingeRequired>} />
          <Route path="/membership" element={<ProtectedRoute><Membership /></ProtectedRoute>} />
          <Route path="/payments" element={<BingeRequired><CustomerPayments /></BingeRequired>} />
          <Route path="/about" element={<BingeRequired><AboutBinge /></BingeRequired>} />
          <Route path="/account" element={<ProtectedRoute><AccountCenter /></ProtectedRoute>} />
          <Route path="/account/notifications" element={<ProtectedRoute><CustomerNotifications /></ProtectedRoute>} />
          <Route path="/account/sessions" element={<ProtectedRoute><MySessions /></ProtectedRoute>} />
          <Route path="/settings" element={<ProtectedRoute><CustomerSettings /></ProtectedRoute>} />
          <Route path="/account/security/mfa" element={<ProtectedRoute><MfaSetup /></ProtectedRoute>} />
          <Route path="/payment/:ref" element={<BingeRequired><PaymentPage /></BingeRequired>} />

          <Route path="/admin/login" element={<PublicOnlyRoute><AdminLogin /></PublicOnlyRoute>} />
          <Route path="/admin/register" element={<SuperAdminRoute scope="ADMIN_REGISTER"><AdminRegister /></SuperAdminRoute>} />
          <Route path="/admin/platform" element={<AdminRoute><AdminEntranceDashboard /></AdminRoute>} />
          <Route path="/admin/account" element={<AdminRoute><AdminAccount /></AdminRoute>} />
          <Route path="/admin/all-users" element={<SuperAdminRoute scope="ALL_USERS"><AdminAllUsers /></SuperAdminRoute>} />
          <Route path="/admin/customers/:id/edit" element={<SuperAdminRoute scope="CUSTOMER_EDIT"><AdminCustomerEdit /></SuperAdminRoute>} />
          <Route path="/admin/super" element={<SuperAdminRoute scope="SUPER_DASHBOARD"><SuperAdminDashboard /></SuperAdminRoute>} />
          {/* Authority Handover — native super-admin only (delegated admins must never grant authority to themselves) */}
          <Route path="/admin/super/authority" element={<SuperAdminRoute><AuthorityHandover /></SuperAdminRoute>} />
          <Route path="/admin/home-editor" element={<SuperAdminRoute scope="HOME_CMS"><AdminHomeEditor /></SuperAdminRoute>} />
          <Route path="/admin/terms-editor" element={<SuperAdminRoute scope="HOME_CMS"><AdminTermsEditor /></SuperAdminRoute>} />
          <Route path="/admin/sessions" element={<AdminRoute><MySessions /></AdminRoute>} />
          <Route path="/admin/security/mfa" element={<AdminRoute><MfaSetup /></AdminRoute>} />
          <Route path="/admin/binges" element={<AdminRoute><BingeManagement /></AdminRoute>} />
          <Route path="/admin/dashboard" element={<AdminBingeRequired><AdminDashboard /></AdminBingeRequired>} />
          <Route path="/admin/bookings" element={<AdminBingeRequired><AdminBookings /></AdminBingeRequired>} />
          <Route path="/admin/blocked-dates" element={<AdminBingeRequired><AdminBlockedDates /></AdminBingeRequired>} />
          <Route path="/admin/event-types" element={<AdminBingeRequired><AdminEventTypes /></AdminBingeRequired>} />
          <Route path="/admin/rate-codes" element={<AdminBingeRequired><AdminRateCodes /></AdminBingeRequired>} />
          <Route path="/admin/loyalty-center" element={<SuperAdminRoute scope="LOYALTY"><AdminLoyaltyCenter /></SuperAdminRoute>} />
          <Route path="/admin/customer-pricing" element={<AdminBingeRequired><AdminCustomerPricing /></AdminBingeRequired>} />
          <Route path="/admin/venue-rooms" element={<AdminBingeRequired><AdminVenueRooms /></AdminBingeRequired>} />
          <Route path="/admin/surge-rules" element={<AdminBingeRequired><AdminSurgeRules /></AdminBingeRequired>} />
          <Route path="/admin/waitlist" element={<AdminBingeRequired><AdminWaitlist /></AdminBingeRequired>} />
          <Route path="/admin/customer-freezes" element={<AdminBingeRequired><AdminCustomerFreezes /></AdminBingeRequired>} />
          <Route path="/admin/risk-flags" element={<AdminBingeRequired><AdminRiskFlags /></AdminBingeRequired>} />
          <Route path="/admin/support" element={<AdminRoute><AdminSupportConsole /></AdminRoute>} />
          <Route path="/admin/recovery" element={<AdminBingeRequired><AdminRecoveryQueues /></AdminBingeRequired>} />
          <Route path="/admin/approvals" element={<AdminBingeRequired><AdminApprovals /></AdminBingeRequired>} />
          <Route path="/admin/disputes" element={<AdminBingeRequired><AdminDisputes /></AdminBingeRequired>} />
          <Route path="/admin/failed-refunds" element={<AdminBingeRequired><AdminFailedRefunds /></AdminBingeRequired>} />
          <Route path="/admin/slot-holds" element={<AdminBingeRequired><AdminSlotHolds /></AdminBingeRequired>} />
          <Route path="/admin/taxes" element={<AdminBingeRequired><AdminTaxes /></AdminBingeRequired>} />
          <Route path="/admin/currencies" element={<SuperAdminRoute scope="CURRENCIES"><AdminCurrencies /></SuperAdminRoute>} />
          <Route path="/admin/account-page-editor" element={<SuperAdminRoute scope="ACCOUNT_CMS"><AdminAccountPageEditor /></SuperAdminRoute>} />
          <Route path="/admin/binges/:bingeId/account-page-editor" element={<AdminRoute><AdminAccountPageEditor /></AdminRoute>} />
          <Route path="/admin/notification-templates" element={<SuperAdminRoute scope="NOTIFICATIONS"><AdminNotificationTemplates /></SuperAdminRoute>} />
          <Route path="/admin/ops" element={<SuperAdminRoute scope="OPS"><AdminOps /></SuperAdminRoute>} />
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

export function AppContent() {
  return (
    <ErrorBoundary>
      <AuthProvider>
        <BingeProvider>
          <ConfirmProvider>
            <CurrencyProvider>
              <AppFrame />
            </CurrencyProvider>
          </ConfirmProvider>
        </BingeProvider>
      </AuthProvider>
    </ErrorBoundary>
  );
}

function App() {
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AppContent />
    </BrowserRouter>
  );
}

export default App;
