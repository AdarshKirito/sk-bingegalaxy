const express = require('express');
const { register, login, forgotPassword, resetPassword, getProfile } = require('../controllers/authController');
const { protect } = require('../middleware/auth');
const router = express.Router();

router.post('/register', register);
router.post('/login', login);
router.post('/forgot-password', forgotPassword);
router.put('/reset-password/:resetToken', resetPassword);
router.get('/profile', protect, getProfile);

module.exports = router;