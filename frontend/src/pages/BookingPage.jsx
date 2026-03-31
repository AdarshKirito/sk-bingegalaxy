import { useNavigate } from 'react-router-dom';
import { bookingService } from '../services/endpoints';
import { toast } from 'react-toastify';
import BookingWizard from '../components/BookingWizard';
import './BookingPage.css';

export default function BookingPage() {
  const navigate = useNavigate();

  const handleSubmit = async (payload) => {
    const res = await bookingService.createBooking(payload);
    const ref = res.data.data.bookingRef;
    toast.success('Booking created!');
    navigate(`/booking/${ref}`);
  };

  return <BookingWizard isAdmin={false} onSubmit={handleSubmit} />;
}
