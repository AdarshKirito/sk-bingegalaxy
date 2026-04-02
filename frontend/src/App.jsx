import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { AuthProvider, useAuth } from './context/AuthContext';
import { BingeProvider, useBinge } from './context/BingeContext';

import Navbar from './components/Navbar';
import Home from './pages/Home';
import Login from './pages/Login';
import Register from './pages/Register';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import Dashboard from './pages/Dashboard';
import BookingPage from './pages/BookingPage';
import BookingConfirmation from './pages/BookingConfirmation';
import MyBookings from './pages/MyBookings';
import PaymentPage from './pages/PaymentPage';
import AdminLogin from './pages/AdminLogin';
import AdminRegister from './pages/AdminRegister';
import AdminDashboard from './pages/AdminDashboard';
import AdminBookings from './pages/AdminBookings';
import AdminBlockedDates from './pages/AdminBlockedDates';
import AdminEventTypes from './pages/AdminEventTypes';
import AdminReports from './pages/AdminReports';
import AdminBookingCreate from './pages/AdminBookingCreate';
import AdminUsersConfig from './pages/AdminUsersConfig';
import BingeManagement from './pages/BingeManagement';
import BingeSelector from './pages/BingeSelector';
import NotFound from './pages/NotFound';

function ProtectedRoute({ children }) {
  const { isAuthenticated, isAdmin } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (isAdmin) return <Navigate to="/admin/binges" replace />;
  return children;
}

function AdminRoute({ children }) {
  const { isAuthenticated, isAdmin } = useAuth();
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  return children;
}

function SuperAdminRoute({ children }) {
  const { isAuthenticated, isAdmin, isSuperAdmin } = useAuth();
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  if (!isSuperAdmin) return <Navigate to="/admin/binges" replace />;
  return children;
}

/* Requires binge selected — redirects to selector if not */
function BingeRequired({ children }) {
  const { isAuthenticated } = useAuth();
  const { selectedBinge } = useBinge();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (!selectedBinge) return <Navigate to="/binges" replace />;
  return children;
}

function AdminBingeRequired({ children }) {
  const { isAuthenticated, isAdmin } = useAuth();
  const { selectedBinge } = useBinge();
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  if (!selectedBinge) return <Navigate to="/admin/binges" replace />;
  return children;
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <BingeProvider>
        <Navbar />
        <main style={{ minHeight: 'calc(100vh - 70px)', paddingTop: '1.5rem', paddingBottom: '3rem' }}>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />
            <Route path="/forgot-password" element={<ForgotPassword />} />
            <Route path="/reset-password" element={<ResetPassword />} />
            <Route path="/binges" element={<ProtectedRoute><BingeSelector /></ProtectedRoute>} />

            <Route path="/dashboard" element={<BingeRequired><Dashboard /></BingeRequired>} />
            <Route path="/book" element={<BingeRequired><BookingPage /></BingeRequired>} />
            <Route path="/booking/:ref" element={<BingeRequired><BookingConfirmation /></BingeRequired>} />
            <Route path="/my-bookings" element={<BingeRequired><MyBookings /></BingeRequired>} />
            <Route path="/payment/:ref" element={<BingeRequired><PaymentPage /></BingeRequired>} />

            <Route path="/admin/login" element={<AdminLogin />} />
            <Route path="/admin/register" element={<SuperAdminRoute><AdminRegister /></SuperAdminRoute>} />
            <Route path="/admin/binges" element={<AdminRoute><BingeManagement /></AdminRoute>} />
            <Route path="/admin/dashboard" element={<AdminBingeRequired><AdminDashboard /></AdminBingeRequired>} />
            <Route path="/admin/bookings" element={<AdminBingeRequired><AdminBookings /></AdminBingeRequired>} />
            <Route path="/admin/blocked-dates" element={<AdminBingeRequired><AdminBlockedDates /></AdminBingeRequired>} />
            <Route path="/admin/event-types" element={<AdminBingeRequired><AdminEventTypes /></AdminBingeRequired>} />
            <Route path="/admin/reports" element={<AdminBingeRequired><AdminReports /></AdminBingeRequired>} />
            <Route path="/admin/book" element={<AdminBingeRequired><AdminBookingCreate /></AdminBingeRequired>} />
            <Route path="/admin/users-config" element={<AdminBingeRequired><AdminUsersConfig /></AdminBingeRequired>} />
            <Route path="/admin/users-config/:userId" element={<AdminBingeRequired><AdminUsersConfig /></AdminBingeRequired>} />

            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
        <ToastContainer position="top-right" theme="dark" autoClose={3000} />
        </BingeProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
