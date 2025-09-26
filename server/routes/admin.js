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
const router = express.Router();
const User = require('../models/User');

// Simple test route
router.get('/test', (req, res) => {
    res.json({ success: true, message: 'Admin routes are working!' });
});

// Simple stats route
router.get('/stats', (req, res) => {
    try {
        const stats = {
            totalBookings: 0,
            todayBookings: 0,
            totalRevenue: 0,
            activeBookings: 0,
            totalCustomers: 0,
            occupancyRate: 0
        };
        res.json({ success: true, stats });
    } catch (error) {
        res.status(500).json({ success: false, message: 'Error fetching stats' });
    }
});

// Dev-only: debug admin existence
router.get('/debug-admin', async (req, res) => {
    if (process.env.NODE_ENV !== 'development') {
        return res.status(403).json({ success: false, message: 'Debug endpoint available only in development' });
    }

    try {
        const admin = await User.findOne({ role: 'admin' }).select('-password');
        if (!admin) {
            return res.json({ success: true, exists: false });
        }

        return res.json({
            success: true,
            exists: true,
            admin: {
                email: admin.email,
                name: admin.name,
                isActive: admin.isActive,
                isVerified: admin.isVerified
            }
        });
    } catch (error) {
        console.error('Debug admin error:', error);
        res.status(500).json({ success: false, message: 'Server error' });
    }
});

module.exports = router;

// Temporarily comment out all other routes to isolate the issue
/*
const {
    getBookings,
    getBookingDetails,
    updateBooking,
    checkInBooking,
    checkOutBooking,
    cancelBookingAdmin
} = require('../controllers/adminController');

const {
    getBlockedDates,
    createBlockedDate,
    updateBlockedDate,
    deleteBlockedDate
} = require('../controllers/blockedDatesController');

router.get('/bookings', protect, admin, getBookings);
router.get('/bookings/:bookingId', protect, admin, getBookingDetails);
router.put('/bookings/:bookingId', protect, admin, updateBooking);
router.put('/bookings/:bookingId/checkin', protect, admin, checkInBooking);
router.put('/bookings/:bookingId/checkout', protect, admin, checkOutBooking);
router.put('/bookings/:bookingId/cancel', protect, admin, cancelBookingAdmin);

// Blocked Dates routes
router.get('/blocked-dates', protect, admin, getBlockedDates);
router.post('/blocked-dates', protect, admin, createBlockedDate);
router.put('/blocked-dates/:id', protect, admin, updateBlockedDate);
router.delete('/blocked-dates/:id', protect, admin, deleteBlockedDate);
*/
