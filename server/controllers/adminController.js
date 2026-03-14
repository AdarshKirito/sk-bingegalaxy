const Booking = require('../models/Booking');
const User = require('../models/User');

// Get all bookings with customer details
exports.getBookings = async (req, res) => {
    try {
        const { page = 1, limit = 10, status, date } = req.query;

        let query = {};
        if (status && status !== 'all') {
            query.bookingStatus = status;
        }

        if (date && date !== 'all') {
            const now = new Date();
            switch (date) {
                case 'today':
                    query.bookingDate = {
                        $gte: new Date(now.setHours(0, 0, 0, 0)),
                        $lt: new Date(now.setHours(23, 59, 59, 999))
                    };
                    break;
                case 'upcoming':
                    query.bookingDate = { $gte: new Date() };
                    break;
                case 'past':
                    query.bookingDate = { $lt: new Date() };
                    break;
            }
        }

        const bookings = await Booking.find(query)
            .populate('customer', 'name email phone')
            .sort({ bookingDate: -1, createdAt: -1 })
            .limit(limit * 1)
            .skip((page - 1) * limit);

        const total = await Booking.countDocuments(query);

        res.json({
            success: true,
            bookings,
            totalPages: Math.ceil(total / limit),
            currentPage: page,
            total
        });

    } catch (error) {
        console.error('Admin get bookings error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error fetching bookings'
        });
    }
};

// Get booking details
exports.getBookingDetails = async (req, res) => {
    try {
        const { bookingId } = req.params;

        const booking = await Booking.findOne({
            $or: [{ _id: bookingId }, { bookingId: bookingId }]
        }).populate('customer', 'name email phone');

        if (!booking) {
            return res.status(404).json({
                success: false,
                message: 'Booking not found'
            });
        }

        res.json({
            success: true,
            booking
        });

    } catch (error) {
        console.error('Admin get booking details error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error fetching booking details'
        });
    }
};

// Update booking
exports.updateBooking = async (req, res) => {
    try {
        const { bookingId } = req.params;
        const updateData = req.body;

        const booking = await Booking.findOneAndUpdate(
            { $or: [{ _id: bookingId }, { bookingId: bookingId }] },
            { $set: updateData },
            { new: true, runValidators: true }
        ).populate('customer', 'name email phone');

        if (!booking) {
            return res.status(404).json({
                success: false,
                message: 'Booking not found'
            });
        }

        res.json({
            success: true,
            message: 'Booking updated successfully',
            booking
        });

    } catch (error) {
        console.error('Admin update booking error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error updating booking'
        });
    }
};

// Check-in booking
exports.checkInBooking = async (req, res) => {
    try {
        const { bookingId } = req.params;

        const booking = await Booking.findOne({
            $or: [{ _id: bookingId }, { bookingId: bookingId }]
        });

        if (!booking) {
            return res.status(404).json({
                success: false,
                message: 'Booking not found'
            });
        }

        if (booking.bookingStatus !== 'confirmed') {
            return res.status(400).json({
                success: false,
                message: 'Only confirmed bookings can be checked in'
            });
        }

        booking.bookingStatus = 'checked-in';
        booking.checkedInAt = new Date();
        await booking.save();

        res.json({
            success: true,
            message: 'Booking checked in successfully',
            booking
        });

    } catch (error) {
        console.error('Admin check-in booking error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during check-in'
        });
    }
};

// Check-out booking
exports.checkOutBooking = async (req, res) => {
    try {
        const { bookingId } = req.params;

        const booking = await Booking.findOne({
            $or: [{ _id: bookingId }, { bookingId: bookingId }]
        });

        if (!booking) {
            return res.status(404).json({
                success: false,
                message: 'Booking not found'
            });
        }

        if (booking.bookingStatus !== 'checked-in') {
            return res.status(400).json({
                success: false,
                message: 'Only checked-in bookings can be checked out'
            });
        }

        booking.bookingStatus = 'completed';
        booking.checkedOutAt = new Date();
        await booking.save();

        res.json({
            success: true,
            message: 'Booking checked out successfully',
            booking
        });

    } catch (error) {
        console.error('Admin check-out booking error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during check-out'
        });
    }
};

// Cancel booking (admin override)
exports.cancelBookingAdmin = async (req, res) => {
    try {
        const { bookingId } = req.params;

        const booking = await Booking.findOne({
            $or: [{ _id: bookingId }, { bookingId: bookingId }]
        });

        if (!booking) {
            return res.status(404).json({
                success: false,
                message: 'Booking not found'
            });
        }

        booking.bookingStatus = 'cancelled';
        booking.cancelledAt = new Date();
        booking.cancelledBy = req.user.id;
        await booking.save();

        res.json({
            success: true,
            message: 'Booking cancelled successfully',
            booking
        });

    } catch (error) {
        console.error('Admin cancel booking error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during cancellation'
        });
    }
};

// // Get admin stats
// exports.getStats = async (req, res) => {
//     try {
//         const today = new Date();
//         today.setHours(0, 0, 0, 0);

//         const tomorrow = new Date(today);
//         tomorrow.setDate(tomorrow.getDate() + 1);

//         const stats = {
//             totalBookings: await Booking.countDocuments(),
//             todayBookings: await Booking.countDocuments({
//                 bookingDate: {
//                     $gte: today,
//                     $lt: tomorrow
//                 }
//             }),
//             totalRevenue: await Booking.aggregate([
//                 { $match: { paymentStatus: 'paid' } },
//                 { $group: { _id: null, total: { $sum: '$totalAmount' } } }
//             ]),
//             activeBookings: await Booking.countDocuments({
//                 bookingStatus: { $in: ['confirmed', 'checked-in'] }
//             })
//         };

//         res.json({
//             success: true,
//             stats
//         });

//     } catch (error) {
//         console.error('Admin stats error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error fetching stats'
//         });
//     }
// };

// Get admin stats - FIXED VERSION
exports.getStats = async (req, res) => {
    try {
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        const tomorrow = new Date(today);
        tomorrow.setDate(tomorrow.getDate() + 1);

        // Fix the aggregation - handle empty result
        const revenueResult = await Booking.aggregate([
            { $match: { paymentStatus: 'paid' } },
            { $group: { _id: null, total: { $sum: '$totalAmount' } } }
        ]);

        const stats = {
            totalBookings: await Booking.countDocuments(),
            todayBookings: await Booking.countDocuments({
                bookingDate: {
                    $gte: today,
                    $lt: tomorrow
                }
            }),
            totalRevenue: revenueResult[0]?.total || 0, // Fixed this line
            activeBookings: await Booking.countDocuments({
                bookingStatus: { $in: ['confirmed', 'checked-in'] }
            }),
            totalCustomers: await User.countDocuments({ role: 'customer' }),
            occupancyRate: 0 // You can calculate this later
        };

        res.json({
            success: true,
            stats
        });

    } catch (error) {
        console.error('Admin stats error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error fetching stats'
        });
    }
};