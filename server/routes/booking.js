const express = require('express');
const { protect } = require('../middleware/auth');
const { 
    checkAvailability, 
    createBooking, 
    getCustomerBookings,
    getBookingDetails,
    cancelBooking,
    calculatePrice
} = require('../controllers/bookingController');

const router = express.Router();

router.get('/availability', checkAvailability);
router.post('/calculate-price', calculatePrice);
router.post('/create', protect, createBooking);
router.get('/my-bookings', protect, getCustomerBookings);
router.get('/:bookingId', protect, getBookingDetails);
router.put('/:bookingId/cancel', protect, cancelBooking);

module.exports = router;