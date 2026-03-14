const { createTransporter, emailTemplates } = require('../config/email');
const axios = require('axios');

class NotificationService {
    constructor() {
        this.emailTransporter = createTransporter();
    }

    // Send email notification
    async sendEmail(to, subject, html) {
        try {
            if (process.env.NODE_ENV === 'development') {
                console.log('📧 Email would be sent to:', to);
                console.log('Subject:', subject);
                return { success: true, method: 'email' };
            }

            const mailOptions = {
                from: `"SK Binge Galaxy" <${process.env.EMAIL_USER}>`,
                to: to,
                subject: subject,
                html: html
            };

            await this.emailTransporter.sendMail(mailOptions);
            console.log('📧 Email sent successfully to:', to);
            return { success: true, method: 'email' };

        } catch (error) {
            console.error('Email sending error:', error);
            return { success: false, error: error.message };
        }
    }

    // Send SMS notification (using mock service)
    async sendSMS(phone, message) {
        try {
            // In production, integrate with SMS gateway like Twilio, MSG91, etc.
            if (process.env.NODE_ENV === 'development') {
                console.log('📱 SMS would be sent to:', phone);
                console.log('Message:', message);
                return { success: true, method: 'sms' };
            }

            // Example integration with MSG91
            const smsData = {
                sender: 'SKBGAL',
                route: '4',
                country: '91',
                sms: [
                    {
                        message: message,
                        to: [phone.replace('+91', '')]
                    }
                ]
            };

            const response = await axios.post(
                `https://api.msg91.com/api/v2/sendsms?authkey=${process.env.SMS_API_KEY}`,
                smsData,
                {
                    headers: {
                        'Content-Type': 'application/json'
                    }
                }
            );

            console.log('📱 SMS sent successfully to:', phone);
            return { success: true, method: 'sms', response: response.data };

        } catch (error) {
            console.error('SMS sending error:', error);
            return { success: false, error: error.message };
        }
    }

    // Send WhatsApp notification (using mock service)
    async sendWhatsApp(phone, message) {
        try {
            // In production, integrate with WhatsApp Business API or services like Twilio
            if (process.env.NODE_ENV === 'development') {
                console.log('💬 WhatsApp would be sent to:', phone);
                console.log('Message:', message);
                return { success: true, method: 'whatsapp' };
            }

            // Example integration with WhatsApp API
            const whatsappData = {
                messaging_product: 'whatsapp',
                to: phone,
                type: 'text',
                text: {
                    body: message
                }
            };

            const response = await axios.post(
                `https://graph.facebook.com/v13.0/${process.env.WHATSAPP_PHONE_ID}/messages`,
                whatsappData,
                {
                    headers: {
                        'Authorization': `Bearer ${process.env.WHATSAPP_TOKEN}`,
                        'Content-Type': 'application/json'
                    }
                }
            );

            console.log('💬 WhatsApp sent successfully to:', phone);
            return { success: true, method: 'whatsapp', response: response.data };

        } catch (error) {
            console.error('WhatsApp sending error:', error);
            return { success: false, error: error.message };
        }
    }

    // Send booking confirmation
    async sendBookingConfirmation(booking, customer) {
        const emailSubject = `Booking Confirmed - ${booking.bookingId} - SK Binge Galaxy`;
        const emailHtml = emailTemplates.bookingConfirmation(booking, customer);
        
        const smsMessage = this.createBookingSMS(booking, customer);
        const whatsappMessage = this.createBookingWhatsApp(booking, customer);

        const results = [];

        // Send email
        if (customer.email) {
            results.push(await this.sendEmail(customer.email, emailSubject, emailHtml));
        }

        // Send SMS
        if (customer.phone) {
            results.push(await this.sendSMS(customer.phone, smsMessage));
        }

        // Send WhatsApp
        if (customer.phone) {
            results.push(await this.sendWhatsApp(customer.phone, whatsappMessage));
        }

        return results;
    }

    // Send payment receipt
    async sendPaymentReceipt(booking, customer, payment) {
        const emailSubject = `Payment Receipt - ${booking.bookingId} - SK Binge Galaxy`;
        const emailHtml = emailTemplates.paymentReceipt(booking, customer, payment);
        
        const smsMessage = this.createPaymentSMS(booking, payment);
        const whatsappMessage = this.createPaymentWhatsApp(booking, payment);

        const results = [];

        if (customer.email) {
            results.push(await this.sendEmail(customer.email, emailSubject, emailHtml));
        }

        if (customer.phone) {
            results.push(await this.sendSMS(customer.phone, smsMessage));
        }

        if (customer.phone) {
            results.push(await this.sendWhatsApp(customer.phone, whatsappMessage));
        }

        return results;
    }

    // Send booking reminder
    async sendBookingReminder(booking, customer) {
        const emailSubject = `Reminder: Your Event Tomorrow - ${booking.bookingId}`;
        const emailHtml = emailTemplates.bookingReminder(booking, customer);
        
        const smsMessage = this.createReminderSMS(booking);
        const whatsappMessage = this.createReminderWhatsApp(booking);

        const results = [];

        if (customer.email) {
            results.push(await this.sendEmail(customer.email, emailSubject, emailHtml));
        }

        if (customer.phone) {
            results.push(await this.sendSMS(customer.phone, smsMessage));
        }

        if (customer.phone) {
            results.push(await this.sendWhatsApp(customer.phone, whatsappMessage));
        }

        return results;
    }

    // Create SMS messages
    createBookingSMS(booking, customer) {
        return `Booking Confirmed! ID: ${booking.bookingId}. ${getEventTypeLabel(booking.eventType)} on ${formatDate(booking.bookingDate)} at ${booking.startTime}. Amount: ₹${booking.totalAmount}. SK Binge Galaxy - ${customer.phone ? 'Contact: +91 9876543210' : ''}`;
    }

    createPaymentSMS(booking, payment) {
        return `Payment Received! Booking ${booking.bookingId}. Transaction: ${payment.transactionId}. Amount: ₹${booking.totalAmount}. Thank you for choosing SK Binge Galaxy!`;
    }

    createReminderSMS(booking) {
        return `Reminder: Your ${getEventTypeLabel(booking.eventType)} is tomorrow at ${booking.startTime}. Booking ID: ${booking.bookingId}. Please arrive 15 mins early. SK Binge Galaxy`;
    }

    // Create WhatsApp messages
    createBookingWhatsApp(booking, customer) {
        return `🎉 *Booking Confirmed!*

*Booking ID:* ${booking.bookingId}
*Event:* ${getEventTypeLabel(booking.eventType)}
*Date:* ${formatDate(booking.bookingDate)}
*Time:* ${booking.startTime}
*Duration:* ${booking.duration} hours
*Guests:* ${booking.numberOfGuests}
*Amount:* ₹${booking.totalAmount}

We're excited to host your event! For queries, contact: +91 9876543210

Thank you for choosing SK Binge Galaxy! 🎬`;
    }

    createPaymentWhatsApp(booking, payment) {
        return `💰 *Payment Confirmed!*

*Booking ID:* ${booking.bookingId}
*Transaction ID:* ${payment.transactionId}
*Amount Paid:* ₹${booking.totalAmount}
*Payment Date:* ${formatDateTime(payment.paidAt)}

Your payment has been processed successfully. Keep this message as your receipt.

Thank you! SK Binge Galaxy 🎉`;
    }

    createReminderWhatsApp(booking) {
        return `⏰ *Event Reminder*

*Booking ID:* ${booking.bookingId}
*Event:* ${getEventTypeLabel(booking.eventType)}
*Date:* ${formatDate(booking.bookingDate)}
*Time:* ${booking.startTime}

*Important Notes:*
• Please arrive 15 minutes before your scheduled time
• Bring a valid ID for verification
• Maximum capacity: 20 guests

We look forward to hosting your event! For assistance: +91 9876543210

SK Binge Galaxy 🎬`;
    }
}

// Helper functions (duplicated from email config for standalone use)
function getEventTypeLabel(type) {
    const types = {
        'birthday': 'Birthday Celebration',
        'anniversary': 'Anniversary',
        'surprise': 'Surprise Party',
        'proposal': 'Marriage Proposal',
        'screening': 'HD Screening',
        'other': 'Other Event'
    };
    return types[type] || type;
}

function formatDate(dateString) {
    return new Date(dateString).toLocaleDateString('en-IN', {
        weekday: 'long',
        year: 'numeric',
        month: 'long',
        day: 'numeric'
    });
}

function formatDateTime(dateString) {
    return new Date(dateString).toLocaleString('en-IN');
}

module.exports = new NotificationService();