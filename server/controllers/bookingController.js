// const Booking = require('../models/Booking');
// const BlockedDate = require('../models/BlockedDate');
// const { v4: uuidv4 } = require('uuid');

// // Price configuration
// const PRICE_CONFIG = {
//     baseRate: 2000, // per hour
//     services: {
//         decorations: 1500,
//         beverages: 800,
//         photoshoot: 1200,
//         fogEffects: 1000,
//         redCarpet: 500,
//         cake: 600
//     },
//     guestSurcharge: 100 // per guest above 10
// };

// // Check availability
// exports.checkAvailability = async (req, res) => {
//     try {
//         const { date, duration } = req.query;
        
//         if (!date) {
//             return res.status(400).json({
//                 success: false,
//                 message: 'Date is required'
//             });
//         }

//         const selectedDate = new Date(date);
//         const availableSlots = await generateAvailableSlots(selectedDate, parseInt(duration) || 3);

//         res.json({
//             success: true,
//             availableSlots,
//             date: selectedDate.toISOString().split('T')[0]
//         });

//     } catch (error) {
//         console.error('Availability check error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during availability check'
//         });
//     }
// };

// // Calculate price
// exports.calculatePrice = async (req, res) => {
//     try {
//         const { duration, guests, services } = req.body;

//         const totalAmount = calculateTotalAmount(duration, guests, services);

//         res.json({
//             success: true,
//             breakdown: {
//                 baseRate: PRICE_CONFIG.baseRate * duration,
//                 serviceCharges: calculateServiceCharges(services),
//                 guestSurcharge: guests > 10 ? (guests - 10) * PRICE_CONFIG.guestSurcharge : 0,
//                 duration: duration
//             },
//             totalAmount
//         });

//     } catch (error) {
//         console.error('Price calculation error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during price calculation'
//         });
//     }
// };

// // Create booking
// exports.createBooking = async (req, res) => {
//     try {
//         const {
//             eventType,
//             bookingDate,
//             startTime,
//             duration,
//             numberOfGuests,
//             additionalServices,
//             specialRequests
//         } = req.body;

//         const customer = req.user.id;

//         // Check availability again before booking
//         const isAvailable = await checkSlotAvailability(bookingDate, startTime, duration);
//         if (!isAvailable) {
//             return res.status(400).json({
//                 success: false,
//                 message: 'Selected slot is no longer available'
//             });
//         }

//         // Calculate total amount
//         const totalAmount = calculateTotalAmount(duration, numberOfGuests, additionalServices);

//         // Create booking
//         const booking = await Booking.create({
//             customer,
//             eventType,
//             bookingDate: new Date(bookingDate),
//             startTime,
//             duration,
//             numberOfGuests,
//             additionalServices: additionalServices || {},
//             specialRequests,
//             totalAmount
//         });

//         // Populate customer details
//         await booking.populate('customer', 'name email phone');

//         res.status(201).json({
//             success: true,
//             message: 'Booking created successfully',
//             booking: {
//                 id: booking._id,
//                 bookingId: booking.bookingId,
//                 eventType: booking.eventType,
//                 bookingDate: booking.bookingDate,
//                 startTime: booking.startTime,
//                 duration: booking.duration,
//                 totalAmount: booking.totalAmount,
//                 status: booking.bookingStatus
//             }
//         });

//     } catch (error) {
//         console.error('Booking creation error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during booking creation'
//         });
//     }
// };

// // Get customer bookings
// exports.getCustomerBookings = async (req, res) => {
//     try {
//         const customerId = req.user.id;
//         const { page = 1, limit = 10, status } = req.query;

//         const query = { customer: customerId };
//         if (status && status !== 'all') {
//             query.bookingStatus = status;
//         }

//         const bookings = await Booking.find(query)
//             .sort({ bookingDate: -1 })
//             .limit(limit * 1)
//             .skip((page - 1) * limit);

//         const total = await Booking.countDocuments(query);

//         res.json({
//             success: true,
//             bookings,
//             totalPages: Math.ceil(total / limit),
//             currentPage: page,
//             total
//         });

//     } catch (error) {
//         console.error('Get bookings error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error fetching bookings'
//         });
//     }
// };

// // Get booking details
// exports.getBookingDetails = async (req, res) => {
//     try {
//         const { bookingId } = req.params;
//         const customerId = req.user.id;

//         const booking = await Booking.findOne({
//             $or: [{ _id: bookingId }, { bookingId: bookingId }],
//             customer: customerId
//         }).populate('customer', 'name email phone');

//         if (!booking) {
//             return res.status(404).json({
//                 success: false,
//                 message: 'Booking not found'
//             });
//         }

//         res.json({
//             success: true,
//             booking
//         });

//     } catch (error) {
//         console.error('Get booking details error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error fetching booking details'
//         });
//     }
// };

// // Cancel booking
// exports.cancelBooking = async (req, res) => {
//     try {
//         const { bookingId } = req.params;
//         const customerId = req.user.id;

//         const booking = await Booking.findOne({
//             $or: [{ _id: bookingId }, { bookingId: bookingId }],
//             customer: customerId
//         });

//         if (!booking) {
//             return res.status(404).json({
//                 success: false,
//                 message: 'Booking not found'
//             });
//         }

//         // Check if booking can be cancelled (e.g., not within 24 hours)
//         const bookingDateTime = new Date(`${booking.bookingDate}T${booking.startTime}`);
//         const now = new Date();
//         const hoursDifference = (bookingDateTime - now) / (1000 * 60 * 60);

//         if (hoursDifference < 24) {
//             return res.status(400).json({
//                 success: false,
//                 message: 'Bookings can only be cancelled at least 24 hours in advance'
//             });
//         }

//         booking.bookingStatus = 'cancelled';
//         await booking.save();

//         res.json({
//             success: true,
//             message: 'Booking cancelled successfully'
//         });

//     } catch (error) {
//         console.error('Cancel booking error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during booking cancellation'
//         });
//     }
// };

// // Helper functions
// async function generateAvailableSlots(date, duration) {
//     const slots = [];
//     const startHour = 9; // 9 AM
//     const endHour = 22; // 10 PM
    
//     for (let hour = startHour; hour <= endHour - duration; hour++) {
//         const startTime = `${hour.toString().padStart(2, '0')}:00`;
//         const endTime = `${(hour + duration).toString().padStart(2, '0')}:00`;
        
//         const isAvailable = await checkSlotAvailability(date, startTime, duration);
        
//         if (isAvailable) {
//             slots.push({
//                 startTime,
//                 endTime,
//                 available: true
//             });
//         }
//     }
    
//     return slots;
// }

// async function checkSlotAvailability(date, startTime, duration) {
//     const bookingDate = new Date(date);
    
//     // Check if date is fully blocked
//     const fullyBlocked = await BlockedDate.findOne({
//         date: bookingDate,
//         isFullyBlocked: true
//     });
    
//     if (fullyBlocked) return false;
    
//     // Check if specific slot is blocked
//     const slotBlocked = await BlockedDate.findOne({
//         date: bookingDate,
//         'blockedSlots.startTime': startTime
//     });
    
//     if (slotBlocked) return false;
    
//     // Check for existing bookings that overlap
//     const endTime = calculateEndTime(startTime, duration);
//     const overlappingBooking = await Booking.findOne({
//         bookingDate: bookingDate,
//         bookingStatus: { $ne: 'cancelled' },
//         $or: [
//             {
//                 startTime: { $lt: endTime },
//                 endTime: { $gt: startTime }
//             }
//         ]
//     });
    
//     return !overlappingBooking;
// }

// function calculateEndTime(startTime, duration) {
//     const [hours, minutes] = startTime.split(':').map(Number);
//     const endHours = (hours + duration) % 24;
//     return `${endHours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}`;
// }

// function calculateTotalAmount(duration, guests, services = {}) {
//     let total = PRICE_CONFIG.baseRate * duration;
    
//     // Add service charges
//     total += calculateServiceCharges(services);
    
//     // Add guest surcharge
//     if (guests > 10) {
//         total += (guests - 10) * PRICE_CONFIG.guestSurcharge;
//     }
    
//     return total;
// }

// function calculateServiceCharges(services) {
//     return Object.keys(services).reduce((total, service) => {
//         if (services[service] && PRICE_CONFIG.services[service]) {
//             return total + PRICE_CONFIG.services[service];
//         }
//         return total;
//     }, 0);
// }

//===============================================================================

// const NotificationService = require('../utils/notification');

// // In the createBooking function, after creating the booking:
// exports.createBooking = async (req, res) => {
//     try {
//         // ... existing booking creation code ...

//         // Create booking
//         const booking = await Booking.create({
//             customer,
//             eventType,
//             bookingDate: new Date(bookingDate),
//             startTime,
//             duration,
//             numberOfGuests,
//             additionalServices: additionalServices || {},
//             specialRequests,
//             totalAmount
//         });

//         // Populate customer details
//         await booking.populate('customer', 'name email phone');

//         // Send booking confirmation notifications
//         await NotificationService.sendBookingConfirmation(booking, booking.customer);

//         res.status(201).json({
//             success: true,
//             message: 'Booking created successfully',
//             booking: {
//                 id: booking._id,
//                 bookingId: booking.bookingId,
//                 eventType: booking.eventType,
//                 bookingDate: booking.bookingDate,
//                 startTime: booking.startTime,
//                 duration: booking.duration,
//                 totalAmount: booking.totalAmount,
//                 status: booking.bookingStatus
//             }
//         });

//     } catch (error) {
//         console.error('Booking creation error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during booking creation'
//         });
//     }
// };




//===============================================================================

const Booking = require('../models/Booking');
const BlockedDate = require('../models/BlockedDate');
const { v4: uuidv4 } = require('uuid');
const NotificationService = require('../utils/notification');

// Price configuration
const PRICE_CONFIG = {
    baseRate: 2000, // per hour
    services: {
        decorations: 1500,
        beverages: 800,
        photoshoot: 1200,
        fogEffects: 1000,
        redCarpet: 500,
        cake: 600
    },
    guestSurcharge: 100 // per guest above 10
};

// Check availability
exports.checkAvailability = async (req, res) => {
    try {
        const { date, duration } = req.query;
        
        if (!date) {
            return res.status(400).json({
                success: false,
                message: 'Date is required'
            });
        }

        const selectedDate = new Date(date);
        const availableSlots = await generateAvailableSlots(selectedDate, parseInt(duration) || 3);

        res.json({
            success: true,
            availableSlots,
            date: selectedDate.toISOString().split('T')[0]
        });

    } catch (error) {
        console.error('Availability check error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during availability check'
        });
    }
};

// Calculate price
exports.calculatePrice = async (req, res) => {
    try {
        const { duration, guests, services } = req.body;

        const totalAmount = calculateTotalAmount(duration, guests, services);

        res.json({
            success: true,
            breakdown: {
                baseRate: PRICE_CONFIG.baseRate * duration,
                serviceCharges: calculateServiceCharges(services),
                guestSurcharge: guests > 10 ? (guests - 10) * PRICE_CONFIG.guestSurcharge : 0,
                duration: duration
            },
            totalAmount
        });

    } catch (error) {
        console.error('Price calculation error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during price calculation'
        });
    }
};

// Create booking
exports.createBooking = async (req, res) => {
    try {
        const {
            eventType,
            bookingDate,
            startTime,
            duration,
            numberOfGuests,
            additionalServices,
            specialRequests
        } = req.body;

        const customer = req.user.id;

        // Check availability again before booking
        const isAvailable = await checkSlotAvailability(bookingDate, startTime, duration);
        if (!isAvailable) {
            return res.status(400).json({
                success: false,
                message: 'Selected slot is no longer available'
            });
        }

        // Calculate total amount
        const totalAmount = calculateTotalAmount(duration, numberOfGuests, additionalServices);

        // Create booking
        const booking = await Booking.create({
            customer,
            eventType,
            bookingDate: new Date(bookingDate),
            startTime,
            duration,
            numberOfGuests,
            additionalServices: additionalServices || {},
            specialRequests,
            totalAmount
        });

        // Populate customer details
        await booking.populate('customer', 'name email phone');

        // Send booking confirmation notifications
        await NotificationService.sendBookingConfirmation(booking, booking.customer);

        res.status(201).json({
            success: true,
            message: 'Booking created successfully',
            booking: {
                id: booking._id,
                bookingId: booking.bookingId,
                eventType: booking.eventType,
                bookingDate: booking.bookingDate,
                startTime: booking.startTime,
                duration: booking.duration,
                totalAmount: booking.totalAmount,
                status: booking.bookingStatus
            }
        });

    } catch (error) {
        console.error('Booking creation error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during booking creation'
        });
    }
};

// Get customer bookings
exports.getCustomerBookings = async (req, res) => {
    try {
        const customerId = req.user.id;
        const { page = 1, limit = 10, status } = req.query;

        const query = { customer: customerId };
        if (status && status !== 'all') {
            query.bookingStatus = status;
        }

        const bookings = await Booking.find(query)
            .sort({ bookingDate: -1 })
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
        console.error('Get bookings error:', error);
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
        const customerId = req.user.id;

        const booking = await Booking.findOne({
            $or: [{ _id: bookingId }, { bookingId: bookingId }],
            customer: customerId
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
        console.error('Get booking details error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error fetching booking details'
        });
    }
};

// Cancel booking
exports.cancelBooking = async (req, res) => {
    try {
        const { bookingId } = req.params;
        const customerId = req.user.id;

        const booking = await Booking.findOne({
            $or: [{ _id: bookingId }, { bookingId: bookingId }],
            customer: customerId
        });

        if (!booking) {
            return res.status(404).json({
                success: false,
                message: 'Booking not found'
            });
        }

        // Check if booking can be cancelled (e.g., not within 24 hours)
        const bookingDateTime = new Date(`${booking.bookingDate}T${booking.startTime}`);
        const now = new Date();
        const hoursDifference = (bookingDateTime - now) / (1000 * 60 * 60);

        if (hoursDifference < 24) {
            return res.status(400).json({
                success: false,
                message: 'Bookings can only be cancelled at least 24 hours in advance'
            });
        }

        booking.bookingStatus = 'cancelled';
        await booking.save();

        res.json({
            success: true,
            message: 'Booking cancelled successfully'
        });

    } catch (error) {
        console.error('Cancel booking error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during booking cancellation'
        });
    }
};

// Helper functions
async function generateAvailableSlots(date, duration) {
    const slots = [];
    const startHour = 9; // 9 AM
    const endHour = 22; // 10 PM
    
    for (let hour = startHour; hour <= endHour - duration; hour++) {
        const startTime = `${hour.toString().padStart(2, '0')}:00`;
        const endTime = `${(hour + duration).toString().padStart(2, '0')}:00`;
        
        const isAvailable = await checkSlotAvailability(date, startTime, duration);
        
        if (isAvailable) {
            slots.push({
                startTime,
                endTime,
                available: true
            });
        }
    }
    
    return slots;
}

async function checkSlotAvailability(date, startTime, duration) {
    const bookingDate = new Date(date);
    
    // Check if date is fully blocked
    const fullyBlocked = await BlockedDate.findOne({
        date: bookingDate,
        isFullyBlocked: true
    });
    
    if (fullyBlocked) return false;
    
    // Check if specific slot is blocked
    const slotBlocked = await BlockedDate.findOne({
        date: bookingDate,
        'blockedSlots.startTime': startTime
    });
    
    if (slotBlocked) return false;
    
    // Check for existing bookings that overlap
    const endTime = calculateEndTime(startTime, duration);
    const overlappingBooking = await Booking.findOne({
        bookingDate: bookingDate,
        bookingStatus: { $ne: 'cancelled' },
        $or: [
            {
                startTime: { $lt: endTime },
                endTime: { $gt: startTime }
            }
        ]
    });
    
    return !overlappingBooking;
}

function calculateEndTime(startTime, duration) {
    const [hours, minutes] = startTime.split(':').map(Number);
    const endHours = (hours + duration) % 24;
    return `${endHours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}`;
}

function calculateTotalAmount(duration, guests, services = {}) {
    let total = PRICE_CONFIG.baseRate * duration;
    
    // Add service charges
    total += calculateServiceCharges(services);
    
    // Add guest surcharge
    if (guests > 10) {
        total += (guests - 10) * PRICE_CONFIG.guestSurcharge;
    }
    
    return total;
}

function calculateServiceCharges(services) {
    return Object.keys(services).reduce((total, service) => {
        if (services[service] && PRICE_CONFIG.services[service]) {
            return total + PRICE_CONFIG.services[service];
        }
        return total;
    }, 0);
}