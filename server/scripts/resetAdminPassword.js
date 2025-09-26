require('dotenv').config();
const mongoose = require('mongoose');
const User = require('../models/User');

const reset = async () => {
    try {
        const uri = process.env.MONGODB_URI;
        if (!uri) {
            console.error('MONGODB_URI not set');
            process.exit(1);
        }
        await mongoose.connect(uri, { useNewUrlParser: true, useUnifiedTopology: true });
        console.log('Connected to MongoDB');

        const email = process.env.ADMIN_EMAIL || 'admin@skbingegalaxy.com';
        const newPassword = process.env.ADMIN_PASSWORD || 'admin123';

        const admin = await User.findOne({ email });
        if (!admin) {
            console.error('Admin user not found for email:', email);
            process.exit(1);
        }

        admin.password = newPassword;
        admin.isActive = true;
        admin.isVerified = true;
        await admin.save();

        console.log('Admin password reset successfully for', email);
        process.exit(0);
    } catch (err) {
        console.error('Failed to reset admin password:', err);
        process.exit(1);
    }
};

if (require.main === module) reset();
module.exports = reset;
