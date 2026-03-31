import { useNavigate, useLocation } from 'react-router-dom';
import { adminService } from '../services/endpoints';
import { toast } from 'react-toastify';
import BookingWizard from '../components/BookingWizard';

export default function AdminBookingCreate() {
  const navigate = useNavigate();
  const location = useLocation();
  const reinstateData = location.state?.reinstate || null;
  const editBookingData = location.state?.editBooking || null;

  const handleSubmit = async (payload) => {
    if (editBookingData?.bookingRef) {
      // Edit mode: cancel old booking, create new one with updated details
      try {
        await adminService.cancelBooking(editBookingData.bookingRef, 'Reservation modified by admin');
      } catch {
        toast.info('Note: Original booking was already cancelled or completed.');
      }
    }
    const res = await adminService.adminCreateBooking(payload);
    const ref = res.data.data?.bookingRef || 'created';
    toast.success(editBookingData ? `Reservation updated — new ref: ${ref}` : `Booking ${ref} created successfully!`);
    navigate('/admin/bookings');
  };

  return (
    <BookingWizard
      isAdmin={true}
      reinstateData={reinstateData}
      editBookingData={editBookingData}
      onSubmit={handleSubmit}
      onCancel={() => navigate('/admin/bookings')}
    />
  );
}
