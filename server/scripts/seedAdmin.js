const mongoose = require('mongoose');
const User = require('../models/User');

const seedAdmin = async () => {
    try {
        // Use existing connection if available
        if (mongoose.connection.readyState !== 1) {
            console.log('❌ Database not connected. Please ensure MongoDB connection is established first.');
            return;
        }

        console.log('✅ Using existing database connection for admin seeding...');

        // Check if admin already exists
        const existingAdmin = await User.findOne({ 
            email: process.env.ADMIN_EMAIL || 'admin@skbingegalaxy.com',
            role: 'admin' 
        });
        
        if (existingAdmin) {
            console.log('⚠️  Admin user already exists:');
            console.log('   Email:', existingAdmin.email);
            console.log('   Name:', existingAdmin.name);
            return;
        }

        // Create admin user
        const adminUser = await User.create({
            name: process.env.ADMIN_NAME || 'System Administrator',
            email: process.env.ADMIN_EMAIL || 'admin@skbingegalaxy.com',
            phone: process.env.ADMIN_PHONE || '0000000000',
            password: process.env.ADMIN_PASSWORD || 'admin123',
            role: 'admin',
            isVerified: true,
            isActive: true
        });

        console.log('✅ Admin user created successfully!');
        console.log('📧 Email:', adminUser.email);
        console.log('👤 Role:', adminUser.role);
        
    } catch (error) {
        console.error('❌ Error seeding admin:', error);
        throw error; // Re-throw to handle in calling function
    }
};

// Only run directly if called as standalone script
if (require.main === module) {
    require('dotenv').config();
    
    const connectAndSeed = async () => {
        try {
            await mongoose.connect(process.env.MONGODB_URI);
            console.log('✅ Connected to database for admin seeding...');
            await seedAdmin();
            await mongoose.disconnect();
        } catch (error) {
            console.error('❌ Error:', error);
            process.exit(1);
        }
    };
    
    connectAndSeed();
}

module.exports = seedAdmin;