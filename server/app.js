// require('dotenv').config();
// const express = require('express');
// const cors = require('cors');
// const connectDB = require('./config/database');
// const NotificationScheduler = require('./utils/scheduler'); 
// const path = require('path');


// // Route imports
// const authRoutes = require('./routes/auth');
// const bookingRoutes = require('./routes/booking');
// const adminRoutes = require('./routes/admin');

// // Initialize express app
// const app = express();

// // Connect to database
// connectDB();

// // Seed admin user in development
// if (process.env.NODE_ENV === 'development') {
//     const seedAdmin = require('./scripts/seedAdmin');
//     setTimeout(() => {
//         seedAdmin();
//     }, 2000); // Wait 2 seconds for DB connection
// }

// // Start notification scheduler
// if (process.env.NODE_ENV !== 'test') {
//     NotificationScheduler; // This initializes the scheduler
// }


// // Middleware
// app.use(cors());
// app.use(express.json());
// app.use(express.urlencoded({ extended: true }));

// // Serve static files from client folder
// app.use(express.static(path.join(__dirname, '../client')));

// // Routes
// app.use('/api/auth', authRoutes);
// app.use('/api/bookings', bookingRoutes);
// app.use('/api/admin', adminRoutes);
// // Add after other route imports
// const paymentRoutes = require('./routes/payment');

// app.use('/api/payments', paymentRoutes);

// // Serve HTML pages
// app.get('/', (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/index.html'));
// });

// app.get(['/admin-block-dates', '/admin-block-dates.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/admin-block-dates.html'));
// });

// app.get(['/admin-bookings', '/admin-bookings.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/admin-bookings.html'));
// });

// app.get(['/admin-dashboard', '/admin-dashboard.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/admin-dashboard.html'));
// });

// app.get(['/admin-login', '/admin-login.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/admin-login.html'));
// });

// app.get(['/booking', '/booking.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/booking.html'));
// });

// app.get(['/confirmation', '/confirmation.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/confirmation.html'));
// });

// app.get(['/dashboard', '/dashboard.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/dashboard.html'));
// });

// app.get(['/login', '/login.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/login.html'));
// });

// app.get(['/my-bookings', '/my-bookings.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/my-bookings.html'));
// });

// app.get(['/payment', '/payment.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/payment.html'));
// });

// app.get(['/register', '/register.html'], (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/register.html'));
// });




// // Handle 404
// app.use('*', (req, res) => {
//     res.status(404).json({ message: 'Route not found' });
// });

// const PORT = process.env.PORT || 5000;

// app.listen(PORT, () => {
//     console.log(`Server running on port ${PORT}`);
// });



//======================================================================    


// require('dotenv').config();
// const mongoose = require('mongoose');
// const express = require('express');
// const cors = require('cors');
// const connectDB = require('./config/database');
// const NotificationScheduler = require('./utils/scheduler'); 
// const path = require('path');
// const seedAdmin = require('./scripts/seedAdmin');


// // Connect to MongoDB first, then start server
// const startServer = async () => {
//     try {
//         // Connect to MongoDB
//         await mongoose.connect(process.env.MONGODB_URI, {
//             useNewUrlParser: true,
//             useUnifiedTopology: true,
//         });
//         console.log('MongoDB Connected:', mongoose.connection.host);

//         // Seed admin user (using existing connection)
//         await seedAdmin();

//         // Start server
//         app.listen(PORT, () => {
//             console.log(`🚀 Server running on port ${PORT}`);
//             console.log(`📁 Environment: ${process.env.NODE_ENV}`);
//         });
//     } catch (error) {
//         console.error('Failed to start server:', error);
//         process.exit(1);
//     }
// };

// startServer();

// // Initialize express app
// const app = express();

// // Connect to database
// connectDB();

// // Middleware
// app.use(cors());
// app.use(express.json());
// app.use(express.urlencoded({ extended: true }));

// // Serve static files from client folder
// app.use(express.static(path.join(__dirname, '../client')));

// // Route imports (ALL imports at the top)
// const authRoutes = require('./routes/auth');
// const bookingRoutes = require('./routes/booking');
// const adminRoutes = require('./routes/admin');
// const paymentRoutes = require('./routes/payment');

// // Routes
// app.use('/api/auth', authRoutes);
// app.use('/api/bookings', bookingRoutes);
// app.use('/api/admin', adminRoutes);
// app.use('/api/payments', paymentRoutes);

// // Serve HTML pages
// const pages = [
//     { paths: ['/', '/index.html'], file: 'index.html' },
//     { paths: ['/admin-block-dates', '/admin-block-dates.html'], file: 'admin-block-dates.html' },
//     { paths: ['/admin-bookings', '/admin-bookings.html'], file: 'admin-bookings.html' },
//     { paths: ['/admin-dashboard', '/admin-dashboard.html'], file: 'admin-dashboard.html' },
//     { paths: ['/admin-login', '/admin-login.html'], file: 'admin-login.html' },
//     { paths: ['/booking', '/booking.html'], file: 'booking.html' },
//     { paths: ['/confirmation', '/confirmation.html'], file: 'confirmation.html' },
//     { paths: ['/dashboard', '/dashboard.html'], file: 'dashboard.html' },
//     { paths: ['/login', '/login.html'], file: 'login.html' },
//     { paths: ['/my-bookings', '/my-bookings.html'], file: 'my-bookings.html' },
//     { paths: ['/payment', '/payment.html'], file: 'payment.html' },
//     { paths: ['/register', '/register.html'], file: 'register.html' }
// ];

// pages.forEach(({ paths, file }) => {
//     app.get(paths, (req, res) => {
//         res.sendFile(path.join(__dirname, `../client/pages/${file}`));
//     });
// });



// // Seed admin user in development
// if (process.env.NODE_ENV === 'development') {
//     try {
//         const seedAdmin = require('./scripts/seedAdmin');
//         setTimeout(() => {
//             seedAdmin().then(() => {
//                 console.log('✅ Admin seeding completed');
//             }).catch(err => {
//                 console.error('❌ Admin seeding failed:', err);
//             });
//         }, 2000);
//     } catch (error) {
//         console.log('⚠️  Admin seeding skipped:', error.message);
//     }
// }

// // Start notification scheduler (AFTER routes)
// if (process.env.NODE_ENV !== 'test') {
//     try {
//         // Make sure NotificationScheduler is properly initialized
//         if (typeof NotificationScheduler === 'function') {
//             NotificationScheduler();
//         } else if (NotificationScheduler && typeof NotificationScheduler.init === 'function') {
//             NotificationScheduler.init();
//         }
//         console.log('✅ Notification scheduler initialized');
//     } catch (error) {
//         console.error('❌ Notification scheduler failed:', error);
//     }
// }

// // Handle 404 for API routes
// app.use('/api/*', (req, res) => {
//     res.status(404).json({ 
//         success: false, 
//         message: 'API endpoint not found' 
//     });
// });

// // Handle 404 for HTML routes - serve index.html for SPA routing
// app.get('*', (req, res) => {
//     res.sendFile(path.join(__dirname, '../client/pages/index.html'));
// });

// const PORT = process.env.PORT || 5000;

// app.listen(PORT, () => {
//     console.log(`🚀 Server running on port ${PORT}`);
//     console.log(`📁 Environment: ${process.env.NODE_ENV || 'development'}`);
// });






//=================================================================





require('dotenv').config();
const mongoose = require('mongoose');
const express = require('express');
const cors = require('cors');
const path = require('path');
const NotificationScheduler = require('./utils/scheduler'); 
const seedAdmin = require('./scripts/seedAdmin');

// Initialize express app
const app = express();
const PORT = process.env.PORT || 5000;

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Serve static files
app.use(express.static(path.join(__dirname, '../client')));

// Route imports
const authRoutes = require('./routes/auth');
const bookingRoutes = require('./routes/booking');
const adminRoutes = require('./routes/admin');
const paymentRoutes = require('./routes/payment');

// Routes
app.use('/api/auth', authRoutes);
app.use('/api/bookings', bookingRoutes);
app.use('/api/admin', adminRoutes);
app.use('/api/payments', paymentRoutes);

// Serve HTML pages
const pages = [
    { paths: ['/', '/index.html'], file: 'index.html' },
    { paths: ['/admin-block-dates', '/admin-block-dates.html'], file: 'admin-block-dates.html' },
    { paths: ['/admin-bookings', '/admin-bookings.html'], file: 'admin-bookings.html' },
    { paths: ['/admin-dashboard', '/admin-dashboard.html'], file: 'admin-dashboard.html' },
    { paths: ['/admin-login', '/admin-login.html'], file: 'admin-login.html' },
    { paths: ['/booking', '/booking.html'], file: 'booking.html' },
    { paths: ['/confirmation', '/confirmation.html'], file: 'confirmation.html' },
    { paths: ['/dashboard', '/dashboard.html'], file: 'dashboard.html' },
    { paths: ['/login', '/login.html'], file: 'login.html' },
    { paths: ['/my-bookings', '/my-bookings.html'], file: 'my-bookings.html' },
    { paths: ['/payment', '/payment.html'], file: 'payment.html' },
    { paths: ['/register', '/register.html'], file: 'register.html' }
];

pages.forEach(({ paths, file }) => {
    app.get(paths, (req, res) => {
        res.sendFile(path.join(__dirname, `../client/pages/${file}`));
    });
});

// Handle 404 for API routes
app.use('/api/*', (req, res) => {
    res.status(404).json({ 
        success: false, 
        message: 'API endpoint not found' 
    });
});

// Handle 404 for HTML routes
app.get('*', (req, res) => {
    res.sendFile(path.join(__dirname, '../client/pages/index.html'));
});

// Function to start server on available port
const startServer = async (port) => {
    return new Promise((resolve, reject) => {
        const server = app.listen(port, () => {
            console.log(`🚀 Server running on port ${port}`);
            resolve(server);
        }).on('error', (err) => {
            if (err.code === 'EADDRINUSE') {
                console.log(`⚠️  Port ${port} is busy, trying ${port + 1}...`);
                resolve(null); // Port busy, try next
            } else {
                reject(err); // Other error
            }
        });
    });
};

// Main startup function
const initializeApp = async () => {
    try {
        // Connect to MongoDB
        await mongoose.connect(process.env.MONGODB_URI, {
            useNewUrlParser: true,
            useUnifiedTopology: true,
        });
        console.log('✅ MongoDB Connected');

        // Seed admin user
        await seedAdmin();

        // Start notification scheduler
        if (process.env.NODE_ENV !== 'test') {
            try {
                if (typeof NotificationScheduler === 'function') {
                    NotificationScheduler();
                }
                console.log('✅ Notification scheduler initialized');
            } catch (error) {
                console.error('❌ Notification scheduler failed:', error);
            }
        }

        // Try to start server on PORT, if busy try next port
        let server = null;
        let currentPort = PORT;
        
        while (!server && currentPort < PORT + 10) {
            server = await startServer(currentPort);
            if (!server) currentPort++;
        }

        if (!server) {
            throw new Error(`Could not find available port between ${PORT} and ${PORT + 10}`);
        }

        console.log(`📁 Environment: ${process.env.NODE_ENV || 'development'}`);

    } catch (error) {
        console.error('❌ Failed to start server:', error);
        process.exit(1);
    }
};

// Start the application
initializeApp();