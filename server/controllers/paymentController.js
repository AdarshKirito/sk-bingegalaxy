// const Booking = require('../models/Booking');
// const { v4: uuidv4 } = require('uuid');

// // Mock payment gateway (replace with actual Razorpay/Stripe integration)
// class MockPaymentGateway {
//     constructor() {
//         this.orders = new Map();
//         this.payments = new Map();
//     }

//     async createOrder(amount, currency = 'INR') {
//         const orderId = `order_${uuidv4().split('-')[0]}`;
//         const order = {
//             id: orderId,
//             amount: amount * 100, // Convert to paise
//             currency,
//             status: 'created',
//             created_at: Date.now()
//         };
        
//         this.orders.set(orderId, order);
//         return order;
//     }

//     async verifyPayment(paymentId, orderId, signature) {
//         // Mock verification - in real scenario, verify with payment gateway
//         return {
//             success: true,
//             paymentId: paymentId || `pay_${uuidv4().split('-')[0]}`,
//             orderId,
//             amount: this.orders.get(orderId)?.amount / 100,
//             status: 'captured'
//         };
//     }
// }

// const paymentGateway = new MockPaymentGateway();

// // Create payment order
// exports.createPaymentOrder = async (req, res) => {
//     try {
//         const { bookingId, amount } = req.body;
//         const customerId = req.user.id;

//         // Verify booking belongs to customer
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

//         if (booking.paymentStatus === 'paid') {
//             return res.status(400).json({
//                 success: false,
//                 message: 'Booking already paid'
//             });
//         }

//         // Create payment order
//         const order = await paymentGateway.createOrder(amount);

//         // Store order reference in booking (temporarily)
//         booking.paymentOrderId = order.id;
//         await booking.save();

//         res.json({
//             success: true,
//             order: {
//                 id: order.id,
//                 amount: order.amount,
//                 currency: order.currency,
//                 key: process.env.RAZORPAY_KEY_ID || 'mock_key' // In real scenario, use actual key
//             },
//             booking: {
//                 id: booking._id,
//                 bookingId: booking.bookingId,
//                 amount: booking.totalAmount
//             }
//         });

//     } catch (error) {
//         console.error('Payment order creation error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during payment order creation'
//         });
//     }
// };

// // Verify payment
// exports.verifyPayment = async (req, res) => {
//     try {
//         const { paymentId, orderId, signature, bookingId } = req.body;
//         const customerId = req.user.id;

//         // Verify booking belongs to customer
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

//         // Verify payment with gateway
//         const verification = await paymentGateway.verifyPayment(paymentId, orderId, signature);

//         if (verification.success) {
//             // Update booking payment status
//             booking.paymentStatus = 'paid';
//             booking.paymentMethod = 'online';
//             booking.transactionId = verification.paymentId;
//             booking.paidAt = new Date();
//             await booking.save();

//             // TODO: Send confirmation notifications
//             await sendBookingConfirmation(booking);

//             res.json({
//                 success: true,
//                 message: 'Payment verified successfully',
//                 payment: verification,
//                 booking: {
//                     id: booking._id,
//                     bookingId: booking.bookingId,
//                     status: booking.bookingStatus
//                 }
//             });
//         } else {
//             booking.paymentStatus = 'failed';
//             await booking.save();

//             res.status(400).json({
//                 success: false,
//                 message: 'Payment verification failed'
//             });
//         }

//     } catch (error) {
//         console.error('Payment verification error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during payment verification'
//         });
//     }
// };

// // Payment webhook handler (for real payment gateways)
// exports.handlePaymentWebhook = async (req, res) => {
//     try {
//         // In real scenario, verify webhook signature
//         const { event, payload } = req.body;

//         if (event === 'payment.captured') {
//             // Update booking status based on webhook
//             const booking = await Booking.findOne({ paymentOrderId: payload.order_id });
//             if (booking) {
//                 booking.paymentStatus = 'paid';
//                 booking.transactionId = payload.payment_id;
//                 booking.paidAt = new Date();
//                 await booking.save();

//                 await sendBookingConfirmation(booking);
//             }
//         }

//         res.json({ success: true });

//     } catch (error) {
//         console.error('Webhook handling error:', error);
//         res.status(500).json({ success: false });
//     }
// };

// // Get payment status
// exports.getPaymentStatus = async (req, res) => {
//     try {
//         const { bookingId } = req.params;
//         const customerId = req.user.id;

//         const booking = await Booking.findOne({
//             $or: [{ _id: bookingId }, { bookingId: bookingId }],
//             customer: customerId
//         }).select('bookingId paymentStatus transactionId totalAmount paidAt');

//         if (!booking) {
//             return res.status(404).json({
//                 success: false,
//                 message: 'Booking not found'
//             });
//         }

//         res.json({
//             success: true,
//             payment: {
//                 status: booking.paymentStatus,
//                 transactionId: booking.transactionId,
//                 amount: booking.totalAmount,
//                 paidAt: booking.paidAt
//             }
//         });

//     } catch (error) {
//         console.error('Get payment status error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error fetching payment status'
//         });
//     }
// };

// // Mock notification function (to be implemented fully later)
// async function sendBookingConfirmation(booking) {
//     console.log(`Booking confirmation sent for ${booking.bookingId}`);
//     // TODO: Implement email, WhatsApp, SMS notifications
// }

//======================================================================

// const NotificationService = require('../utils/notification');

// // In the verifyPayment function, after payment verification:
// exports.verifyPayment = async (req, res) => {
//     try {
//         // ... existing payment verification code ...

//         if (verification.success) {
//             // Update booking payment status
//             booking.paymentStatus = 'paid';
//             booking.paymentMethod = 'online';
//             booking.transactionId = verification.paymentId;
//             booking.paidAt = new Date();
//             await booking.save();

//             // Send payment receipt notifications
//             await NotificationService.sendPaymentReceipt(booking, booking.customer, {
//                 transactionId: verification.paymentId,
//                 paidAt: new Date()
//             });

//             res.json({
//                 success: true,
//                 message: 'Payment verified successfully',
//                 payment: verification,
//                 booking: {
//                     id: booking._id,
//                     bookingId: booking.bookingId,
//                     status: booking.bookingStatus
//                 }
//             });
//         }

//     } catch (error) {
//         console.error('Payment verification error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during payment verification'
//         });
//     }
// };


//======================================================================



const Booking = require('../models/Booking');
const { v4: uuidv4 } = require('uuid');
const NotificationService = require('../utils/notification');

// Mock payment gateway (replace with actual Razorpay/Stripe integration)
class MockPaymentGateway {
    constructor() {
        this.orders = new Map();
        this.payments = new Map();
    }

    async createOrder(amount, currency = 'INR') {
        const orderId = `order_${uuidv4().split('-')[0]}`;
        const order = {
            id: orderId,
            amount: amount * 100, // Convert to paise
            currency,
            status: 'created',
            created_at: Date.now()
        };
        
        this.orders.set(orderId, order);
        return order;
    }

    async verifyPayment(paymentId, orderId, signature) {
        // Mock verification - in real scenario, verify with payment gateway
        return {
            success: true,
            paymentId: paymentId || `pay_${uuidv4().split('-')[0]}`,
            orderId,
            amount: this.orders.get(orderId)?.amount / 100,
            status: 'captured'
        };
    }
}

const paymentGateway = new MockPaymentGateway();

// Create payment order
exports.createPaymentOrder = async (req, res) => {
    try {
        const { bookingId, amount } = req.body;
        const customerId = req.user.id;

        // Verify booking belongs to customer
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

        if (booking.paymentStatus === 'paid') {
            return res.status(400).json({
                success: false,
                message: 'Booking already paid'
            });
        }

        // Create payment order
        const order = await paymentGateway.createOrder(amount);

        // Store order reference in booking (temporarily)
        booking.paymentOrderId = order.id;
        await booking.save();

        res.json({
            success: true,
            order: {
                id: order.id,
                amount: order.amount,
                currency: order.currency,
                key: process.env.RAZORPAY_KEY_ID || 'mock_key' // In real scenario, use actual key
            },
            booking: {
                id: booking._id,
                bookingId: booking.bookingId,
                amount: booking.totalAmount
            }
        });

    } catch (error) {
        console.error('Payment order creation error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during payment order creation'
        });
    }
};

// Verify payment
exports.verifyPayment = async (req, res) => {
    try {
        const { paymentId, orderId, signature, bookingId } = req.body;
        const customerId = req.user.id;

        // Verify booking belongs to customer
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

        // Verify payment with gateway
        const verification = await paymentGateway.verifyPayment(paymentId, orderId, signature);

        if (verification.success) {
            // Update booking payment status
            booking.paymentStatus = 'paid';
            booking.paymentMethod = 'online';
            booking.transactionId = verification.paymentId;
            booking.paidAt = new Date();
            await booking.save();

            // Populate customer details for notification
            await booking.populate('customer', 'name email phone');

            // Send payment receipt notifications
            await NotificationService.sendPaymentReceipt(booking, booking.customer, {
                transactionId: verification.paymentId,
                paidAt: new Date()
            });

            res.json({
                success: true,
                message: 'Payment verified successfully',
                payment: verification,
                booking: {
                    id: booking._id,
                    bookingId: booking.bookingId,
                    status: booking.bookingStatus
                }
            });
        } else {
            booking.paymentStatus = 'failed';
            await booking.save();

            res.status(400).json({
                success: false,
                message: 'Payment verification failed'
            });
        }

    } catch (error) {
        console.error('Payment verification error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during payment verification'
        });
    }
};

// Payment webhook handler (for real payment gateways)
exports.handlePaymentWebhook = async (req, res) => {
    try {
        // In real scenario, verify webhook signature
        const { event, payload } = req.body;

        if (event === 'payment.captured') {
            // Update booking status based on webhook
            const booking = await Booking.findOne({ paymentOrderId: payload.order_id });
            if (booking) {
                booking.paymentStatus = 'paid';
                booking.transactionId = payload.payment_id;
                booking.paidAt = new Date();
                await booking.save();

                await sendBookingConfirmation(booking);
            }
        }

        res.json({ success: true });

    } catch (error) {
        console.error('Webhook handling error:', error);
        res.status(500).json({ success: false });
    }
};

// Get payment status
exports.getPaymentStatus = async (req, res) => {
    try {
        const { bookingId } = req.params;
        const customerId = req.user.id;

        const booking = await Booking.findOne({
            $or: [{ _id: bookingId }, { bookingId: bookingId }],
            customer: customerId
        }).select('bookingId paymentStatus transactionId totalAmount paidAt');

        if (!booking) {
            return res.status(404).json({
                success: false,
                message: 'Booking not found'
            });
        }

        res.json({
            success: true,
            payment: {
                status: booking.paymentStatus,
                transactionId: booking.transactionId,
                amount: booking.totalAmount,
                paidAt: booking.paidAt
            }
        });

    } catch (error) {
        console.error('Get payment status error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error fetching payment status'
        });
    }
};

// Mock notification function (to be implemented fully later)
async function sendBookingConfirmation(booking) {
    console.log(`Booking confirmation sent for ${booking.bookingId}`);
    // TODO: Implement email, WhatsApp, SMS notifications
}