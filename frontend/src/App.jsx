import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ToastContainer } from 'react-toastify';
import 'react-toastify/dist/ReactToastify.css';
import { AuthProvider, useAuth } from './context/AuthContext';

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
import AdminDashboard from './pages/AdminDashboard';
import AdminBookings from './pages/AdminBookings';
import AdminBlockedDates from './pages/AdminBlockedDates';
import AdminEventTypes from './pages/AdminEventTypes';
import AdminReports from './pages/AdminReports';
import AdminBookingCreate from './pages/AdminBookingCreate';
import AdminCustomerEdit from './pages/AdminCustomerEdit';
import AdminRateCodes from './pages/AdminRateCodes';
import AdminCustomerPricing from './pages/AdminCustomerPricing';
import NotFound from './pages/NotFound';

function ProtectedRoute({ children }) {
  const { isAuthenticated, isAdmin } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (isAdmin) return <Navigate to="/admin/dashboard" replace />;
  return children;
}

function AdminRoute({ children }) {
  const { isAuthenticated, isAdmin } = useAuth();
  if (!isAuthenticated) return <Navigate to="/admin/login" replace />;
  if (!isAdmin) return <Navigate to="/" replace />;
  return children;
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Navbar />
        <main style={{ minHeight: 'calc(100vh - 70px)', paddingTop: '1.5rem', paddingBottom: '3rem' }}>
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/login" element={<Login />} />
            <Route path="/register" element={<Register />} />
            <Route path="/forgot-password" element={<ForgotPassword />} />
            <Route path="/reset-password" element={<ResetPassword />} />

            <Route path="/dashboard" element={<ProtectedRoute><Dashboard /></ProtectedRoute>} />
            <Route path="/book" element={<ProtectedRoute><BookingPage /></ProtectedRoute>} />
            <Route path="/booking/:ref" element={<ProtectedRoute><BookingConfirmation /></ProtectedRoute>} />
            <Route path="/my-bookings" element={<ProtectedRoute><MyBookings /></ProtectedRoute>} />
            <Route path="/payment/:ref" element={<ProtectedRoute><PaymentPage /></ProtectedRoute>} />

            <Route path="/admin/login" element={<AdminLogin />} />
            <Route path="/admin/dashboard" element={<AdminRoute><AdminDashboard /></AdminRoute>} />
            <Route path="/admin/bookings" element={<AdminRoute><AdminBookings /></AdminRoute>} />
            <Route path="/admin/blocked-dates" element={<AdminRoute><AdminBlockedDates /></AdminRoute>} />
            <Route path="/admin/event-types" element={<AdminRoute><AdminEventTypes /></AdminRoute>} />
            <Route path="/admin/reports" element={<AdminRoute><AdminReports /></AdminRoute>} />
            <Route path="/admin/book" element={<AdminRoute><AdminBookingCreate /></AdminRoute>} />
            <Route path="/admin/customer/:id" element={<AdminRoute><AdminCustomerEdit /></AdminRoute>} />
            <Route path="/admin/rate-codes" element={<AdminRoute><AdminRateCodes /></AdminRoute>} />
            <Route path="/admin/customer-pricing" element={<AdminRoute><AdminCustomerPricing /></AdminRoute>} />

            <Route path="*" element={<NotFound />} />
          </Routes>
        </main>
        <ToastContainer position="top-right" theme="dark" autoClose={3000} />
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
