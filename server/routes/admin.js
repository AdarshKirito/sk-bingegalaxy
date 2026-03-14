// const express = require('express');
// const { protect, admin } = require('../middleware/auth');
// const {
//     getBookings,
//     getBookingDetails,
//     updateBooking,
//     checkInBooking,
//     checkOutBooking,
//     cancelBookingAdmin,
//     getStats
// } = require('../controllers/adminController');

// const router = express.Router();

// router.get('/bookings', protect, admin, getBookings);
// router.get('/bookings/:bookingId', protect, admin, getBookingDetails);
// router.put('/bookings/:bookingId', protect, admin, updateBooking);
// router.put('/bookings/:bookingId/checkin', protect, admin, checkInBooking);
// router.put('/bookings/:bookingId/checkout', protect, admin, checkOutBooking);
// router.put('/bookings/:bookingId/cancel', protect, admin, cancelBookingAdmin);
// router.get('/stats', protect, admin, getStats);

// module.exports = router;

//=================================================================

// Add to existing admin routes
// const {
//     getBlockedDates,
//     createBlockedDate,
//     updateBlockedDate,
//     deleteBlockedDate
// } = require('../controllers/blockedDatesController');

// router.get('/blocked-dates', protect, admin, getBlockedDates);
// router.post('/blocked-dates', protect, admin, createBlockedDate);
// router.put('/blocked-dates/:id', protect, admin, updateBlockedDate);
// router.delete('/blocked-dates/:id', protect, admin, deleteBlockedDate);


//=================================================================

const express = require('express');
const { protect, admin } = require('../middleware/auth');
const {
    getBookings,
    getBookingDetails,
    updateBooking,
    checkInBooking,
    checkOutBooking,
    cancelBookingAdmin,
    getStats
} = require('../controllers/adminController');
const {
    getBlockedDates,
    createBlockedDate,
    updateBlockedDate,
    deleteBlockedDate
} = require('../controllers/blockedDatesController');

const router = express.Router();

router.get('/test', (req, res) => {
    res.json({ success: true, message: 'Admin routes are working!' });
});

router.get('/stats', protect, admin, getStats);

router.get('/bookings', protect, admin, getBookings);
router.get('/bookings/:bookingId', protect, admin, getBookingDetails);
router.put('/bookings/:bookingId', protect, admin, updateBooking);
router.put('/bookings/:bookingId/checkin', protect, admin, checkInBooking);
router.put('/bookings/:bookingId/checkout', protect, admin, checkOutBooking);
router.put('/bookings/:bookingId/cancel', protect, admin, cancelBookingAdmin);

router.get('/blocked-dates', protect, admin, getBlockedDates);
router.post('/blocked-dates', protect, admin, createBlockedDate);
router.put('/blocked-dates/:id', protect, admin, updateBlockedDate);
router.delete('/blocked-dates/:id', protect, admin, deleteBlockedDate);

module.exports = router;
