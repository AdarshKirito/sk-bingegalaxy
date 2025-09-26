// const User = require('../models/User');
// const jwt = require('jsonwebtoken');
// const { validationResult } = require('express-validator');

// // Generate JWT Token
// const generateToken = (id) => {
//     return jwt.sign({ id }, process.env.JWT_SECRET, {
//         expiresIn: process.env.JWT_EXPIRE,
//     });
// };

// // Register User
// exports.register = async (req, res) => {
//     try {
//         const { name, email, phone, password, role } = req.body;

//         // Check if user already exists
//         const existingUser = await User.findOne({
//             $or: [{ email }, { phone }]
//         });

//         if (existingUser) {
//             return res.status(400).json({
//                 success: false,
//                 message: 'User already exists with this email or phone number'
//             });
//         }

//         // Create user
//         const user = await User.create({
//             name,
//             email,
//             phone,
//             password,
//             role: role || 'customer'
//         });

//         // Generate token
//         const token = generateToken(user._id);

//         res.status(201).json({
//             success: true,
//             message: 'Registration successful',
//             token,
//             user: {
//                 id: user._id,
//                 name: user.name,
//                 email: user.email,
//                 phone: user.phone,
//                 role: user.role
//             }
//         });

//     } catch (error) {
//         console.error('Registration error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during registration'
//         });
//     }
// };

// // Login User
// exports.login = async (req, res) => {
//     try {
//         const { email, password } = req.body;

//         // Check if user exists
//         const user = await User.findOne({ email });
//         if (!user) {
//             return res.status(401).json({
//                 success: false,
//                 message: 'Invalid credentials'
//             });
//         }

//         // Check password
//         const isMatch = await user.matchPassword(password);
//         if (!isMatch) {
//             return res.status(401).json({
//                 success: false,
//                 message: 'Invalid credentials'
//             });
//         }

        

//         // Generate token
//         const token = generateToken(user._id);

//         res.json({
//             success: true,
//             message: 'Login successful',
//             token,
//             user: {
//                 id: user._id,
//                 name: user.name,
//                 email: user.email,
//                 phone: user.phone,
//                 role: user.role
//             }
//         });

//     } catch (error) {
//         console.error('Login error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error during login'
//         });
//     }
// };

// // Forgot Password
// exports.forgotPassword = async (req, res) => {
//     try {
//         const { email } = req.body;

//         const user = await User.findOne({ email });
//         if (!user) {
//             return res.status(404).json({
//                 success: false,
//                 message: 'User not found with this email'
//             });
//         }

//         // In a real application, you would generate a reset token and send email
//         // For now, we'll just return a success message
//         res.json({
//             success: true,
//             message: 'Password reset instructions sent to your email'
//         });

//     } catch (error) {
//         console.error('Forgot password error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error'
//         });
//     }
// };

// // Reset Password
// exports.resetPassword = async (req, res) => {
//     try {
//         const { resetToken } = req.params;
//         const { password } = req.body;

//         // In a real application, you would verify the reset token
//         // For now, we'll simulate the reset process
//         res.json({
//             success: true,
//             message: 'Password reset successfully'
//         });

//     } catch (error) {
//         console.error('Reset password error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error'
//         });
//     }
// };

// // Get User Profile
// exports.getProfile = async (req, res) => {
//     try {
//         const user = await User.findById(req.user.id).select('-password');
//         res.json({
//             success: true,
//             user
//         });
//     } catch (error) {
//         console.error('Get profile error:', error);
//         res.status(500).json({
//             success: false,
//             message: 'Server error'
//         });
//     }
// };

// // Check environment variables first
// if (process.env.ADMIN_SEED_ENABLED === 'true') {
//     // Use process.env.ADMIN_EMAIL and process.env.ADMIN_PASSWORD
// }





//======================================================================================================


const User = require('../models/User');
const jwt = require('jsonwebtoken');
const { validationResult } = require('express-validator');

// Generate JWT Token
const generateToken = (id) => {
    return jwt.sign({ id }, process.env.JWT_SECRET || 'fallback-secret', {
        expiresIn: process.env.JWT_EXPIRE || '30d',
    });
};

// Register User
exports.register = async (req, res) => {
    try {
        const { name, email, phone, password, role } = req.body;

        // Check if user already exists
        const existingUser = await User.findOne({
            $or: [{ email }, { phone }]
        });

        if (existingUser) {
            return res.status(400).json({
                success: false,
                message: 'User already exists with this email or phone number'
            });
        }

        // Create user
        const user = await User.create({
            name,
            email,
            phone,
            password,
            role: role || 'customer'
        });

        // Generate token
        const token = generateToken(user._id);

        res.status(201).json({
            success: true,
            message: 'Registration successful',
            token,
            user: {
                id: user._id,
                name: user.name,
                email: user.email,
                phone: user.phone,
                role: user.role
            }
        });

    } catch (error) {
        console.error('Registration error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during registration'
        });
    }
};

// Login User (PRODUCTION VERSION - No hard-coded admin)
exports.login = async (req, res) => {
    try {
        const { email, password } = req.body;

        // Check if user exists
        const user = await User.findOne({ email }).select('+password');
        if (!user) {
            return res.status(401).json({
                success: false,
                message: 'Invalid credentials'
            });
        }

        // Check if user is active
        if (!user.isActive) {
            return res.status(401).json({
                success: false,
                message: 'Account has been deactivated'
            });
        }

        // Check password
        const isMatch = await user.matchPassword(password);
        if (!isMatch) {
            return res.status(401).json({
                success: false,
                message: 'Invalid credentials'
            });
        }

        // Generate token
        const token = generateToken(user._id);

        res.json({
            success: true,
            message: 'Login successful',
            token,
            user: {
                id: user._id,
                name: user.name,
                email: user.email,
                phone: user.phone,
                role: user.role
            }
        });

    } catch (error) {
        console.error('Login error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error during login'
        });
    }
};

// Forgot Password
exports.forgotPassword = async (req, res) => {
    try {
        const { email } = req.body;

        const user = await User.findOne({ email });
        if (!user) {
            return res.status(404).json({
                success: false,
                message: 'User not found with this email'
            });
        }

        // In a real application, you would generate a reset token and send email
        // For now, we'll just return a success message
        res.json({
            success: true,
            message: 'Password reset instructions sent to your email'
        });

    } catch (error) {
        console.error('Forgot password error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error'
        });
    }
};

// Reset Password
exports.resetPassword = async (req, res) => {
    try {
        const { resetToken } = req.params;
        const { password } = req.body;

        // In a real application, you would verify the reset token
        // For now, we'll simulate the reset process
        res.json({
            success: true,
            message: 'Password reset successfully'
        });

    } catch (error) {
        console.error('Reset password error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error'
        });
    }
};

// Get User Profile
exports.getProfile = async (req, res) => {
    try {
        const user = await User.findById(req.user.id).select('-password');
        res.json({
            success: true,
            user
        });
    } catch (error) {
        console.error('Get profile error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error'
        });
    }
};
