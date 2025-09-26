require('dotenv').config();
const mongoose = require('mongoose');
const User = require('../models/User');
const jwt = require('jsonwebtoken');

const run = async () => {
    try {
        if (!process.env.MONGODB_URI) {
            console.error('MONGODB_URI is not set in env');
            process.exit(1);
        }

        await mongoose.connect(process.env.MONGODB_URI, {
            useNewUrlParser: true,
            useUnifiedTopology: true,
        });
        console.log('Connected to MongoDB');

        const adminEmail = process.env.ADMIN_EMAIL || 'admin@skbingegalaxy.com';
        const adminPassword = process.env.ADMIN_PASSWORD || 'admin123';

        let admin = await User.findOne({ email: adminEmail }).select('+password');
        if (!admin) {
            console.log('Admin not found, creating...');
            admin = await User.create({
                name: process.env.ADMIN_NAME || 'System Administrator',
                email: adminEmail,
                phone: process.env.ADMIN_PHONE || '0000000000',
                password: adminPassword,
                role: 'admin',
                isVerified: true,
                isActive: true
            });
            console.log('Admin created');
        } else {
            console.log('Admin exists:', admin.email);
        }

        // verify password
        const isMatch = await admin.matchPassword(adminPassword);
        console.log('Password match:', isMatch);

        if (!isMatch) {
            console.error('Admin password does not match expected ADMIN_PASSWORD');
            process.exit(1);
        }

        // generate token for dev use
        const token = jwt.sign({ id: admin._id }, process.env.JWT_SECRET || 'fallback-secret', { expiresIn: '30d' });
        console.log('\nDEV LOGIN TOKEN (use as Bearer token):\n');
        console.log(token);

        process.exit(0);
    } catch (error) {
        console.error('Dev smoke test error:', error);
        process.exit(1);
    }
};

if (require.main === module) run();

module.exports = run;
