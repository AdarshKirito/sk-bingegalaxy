const express = require('express');
const { protect } = require('../middleware/auth');
const {
    createPaymentOrder,
    verifyPayment,
    handlePaymentWebhook,
    getPaymentStatus
} = require('../controllers/paymentController');

const router = express.Router();

router.post('/create-order', protect, createPaymentOrder);
router.post('/verify', protect, verifyPayment);
router.post('/webhook', handlePaymentWebhook);
router.get('/status/:bookingId', protect, getPaymentStatus);

module.exports = router;