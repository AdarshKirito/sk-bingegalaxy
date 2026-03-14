const cron = require('node-cron');
const Booking = require('../models/Booking');
const NotificationService = require('./notification');

class NotificationScheduler {
    constructor() {
        this.init();
    }

    init() {
        // Schedule daily reminder check at 9 AM
        cron.schedule('0 9 * * *', () => {
            this.sendDailyReminders();
        });

        // Schedule hourly booking notifications check
        cron.schedule('0 * * * *', () => {
            this.sendBookingNotifications();
        });

        console.log('📅 Notification scheduler initialized');
    }

    async sendDailyReminders() {
        try {
            const tomorrow = new Date();
            tomorrow.setDate(tomorrow.getDate() + 1);
            tomorrow.setHours(0, 0, 0, 0);

            const dayAfter = new Date(tomorrow);
            dayAfter.setDate(dayAfter.getDate() + 1);

            // Find bookings for tomorrow
            const bookings = await Booking.find({
                bookingDate: {
                    $gte: tomorrow,
                    $lt: dayAfter
                },
                bookingStatus: { $in: ['confirmed', 'checked-in'] }
            }).populate('customer', 'name email phone');

            console.log(`Sending reminders for ${bookings.length} bookings tomorrow`);

            for (const booking of bookings) {
                await NotificationService.sendBookingReminder(booking, booking.customer);
                
                // Add delay to avoid rate limiting
                await new Promise(resolve => setTimeout(resolve, 1000));
            }

        } catch (error) {
            console.error('Error sending daily reminders:', error);
        }
    }

    async sendBookingNotifications() {
        try {
            // Find recent bookings that need notifications (last 5 minutes)
            const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000);
            
            const newBookings = await Booking.find({
                createdAt: { $gte: fiveMinutesAgo },
                notificationsSent: { $ne: true }
            }).populate('customer', 'name email phone');

            for (const booking of newBookings) {
                if (booking.paymentStatus === 'paid') {
                    await NotificationService.sendPaymentReceipt(booking, booking.customer, {
                        transactionId: booking.transactionId,
                        paidAt: booking.paidAt
                    });
                } else {
                    await NotificationService.sendBookingConfirmation(booking, booking.customer);
                }

                // Mark as notified
                booking.notificationsSent = true;
                await booking.save();

                await new Promise(resolve => setTimeout(resolve, 500));
            }

        } catch (error) {
            console.error('Error sending booking notifications:', error);
        }
    }
}

module.exports = new NotificationScheduler();