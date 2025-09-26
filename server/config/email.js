const nodemailer = require('nodemailer');

// Create transporter
const createTransporter = () => {
    return nodemailer.createTransport({
        host: process.env.EMAIL_HOST,
        port: process.env.EMAIL_PORT,
        secure: false,
        auth: {
            user: process.env.EMAIL_USER,
            pass: process.env.EMAIL_PASS
        }
    });
};

// Email templates
const emailTemplates = {
    bookingConfirmation: (booking, customer) => `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <style>
                body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                .header { background: linear-gradient(135deg, #6a11cb 0%, #2575fc 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                .booking-details { background: white; padding: 20px; border-radius: 8px; margin: 20px 0; }
                .detail-row { display: flex; justify-content: space-between; margin-bottom: 10px; }
                .detail-label { font-weight: bold; color: #666; }
                .total-amount { font-size: 1.2em; font-weight: bold; color: #6a11cb; text-align: center; margin: 20px 0; }
                .footer { text-align: center; margin-top: 30px; color: #666; font-size: 0.9em; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h1>🎉 Booking Confirmed!</h1>
                    <p>SK Binge Galaxy - Your event is scheduled!</p>
                </div>
                <div class="content">
                    <p>Dear ${customer.name},</p>
                    <p>Your booking has been confirmed successfully. We're excited to host your event!</p>
                    
                    <div class="booking-details">
                        <h3>Booking Details</h3>
                        <div class="detail-row">
                            <span class="detail-label">Booking ID:</span>
                            <span>${booking.bookingId}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Event Type:</span>
                            <span>${getEventTypeLabel(booking.eventType)}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Date & Time:</span>
                            <span>${formatDate(booking.bookingDate)} at ${booking.startTime}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Duration:</span>
                            <span>${booking.duration} hours</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Number of Guests:</span>
                            <span>${booking.numberOfGuests}</span>
                        </div>
                        ${booking.specialRequests ? `
                        <div class="detail-row">
                            <span class="detail-label">Special Requests:</span>
                            <span>${booking.specialRequests}</span>
                        </div>
                        ` : ''}
                    </div>

                    <div class="total-amount">
                        Total Amount: ₹${booking.totalAmount.toLocaleString()}
                    </div>

                    <p><strong>Additional Services:</strong></p>
                    <ul>
                        ${Object.entries(booking.additionalServices || {})
                          .filter(([_, value]) => value)
                          .map(([service]) => `<li>${getServiceLabel(service)}</li>`)
                          .join('') || '<li>No additional services selected</li>'}
                    </ul>

                    <p>We look forward to making your event memorable! If you have any questions, please contact us at info@skbingegalaxy.com or +91 9876543210.</p>
                    
                    <div class="footer">
                        <p>SK Binge Galaxy<br>
                        Contact: info@skbingegalaxy.com | +91 9876543210</p>
                    </div>
                </div>
            </div>
        </body>
        </html>
    `,

    paymentReceipt: (booking, customer, payment) => `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <style>
                body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                .header { background: linear-gradient(135deg, #00b894 0%, #00cec9 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                .receipt-details { background: white; padding: 20px; border-radius: 8px; margin: 20px 0; }
                .detail-row { display: flex; justify-content: space-between; margin-bottom: 10px; }
                .detail-label { font-weight: bold; color: #666; }
                .amount-breakdown { border-top: 1px solid #ddd; padding-top: 10px; margin-top: 10px; }
                .total-amount { font-size: 1.3em; font-weight: bold; color: #00b894; text-align: center; margin: 20px 0; padding: 15px; background: #f0fff4; border-radius: 5px; }
                .footer { text-align: center; margin-top: 30px; color: #666; font-size: 0.9em; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h1>💰 Payment Receipt</h1>
                    <p>SK Binge Galaxy - Payment Confirmation</p>
                </div>
                <div class="content">
                    <p>Dear ${customer.name},</p>
                    <p>Thank you for your payment. Here's your receipt for booking ${booking.bookingId}.</p>
                    
                    <div class="receipt-details">
                        <h3>Payment Details</h3>
                        <div class="detail-row">
                            <span class="detail-label">Transaction ID:</span>
                            <span>${payment.transactionId}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Payment Date:</span>
                            <span>${formatDateTime(payment.paidAt)}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Payment Method:</span>
                            <span>${booking.paymentMethod}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Booking ID:</span>
                            <span>${booking.bookingId}</span>
                        </div>
                        
                        <div class="amount-breakdown">
                            <div class="detail-row">
                                <span class="detail-label">Base Amount:</span>
                                <span>₹${(2000 * booking.duration).toLocaleString()}</span>
                            </div>
                            ${Object.entries(booking.additionalServices || {})
                              .filter(([_, value]) => value)
                              .map(([service, _]) => `
                                <div class="detail-row">
                                    <span class="detail-label">${getServiceLabel(service)}:</span>
                                    <span>₹${getServicePrice(service).toLocaleString()}</span>
                                </div>
                              `).join('')}
                            ${booking.numberOfGuests > 10 ? `
                            <div class="detail-row">
                                <span class="detail-label">Guest Surcharge:</span>
                                <span>₹${((booking.numberOfGuests - 10) * 100).toLocaleString()}</span>
                            </div>
                            ` : ''}
                        </div>
                        
                        <div class="total-amount">
                            Total Paid: ₹${booking.totalAmount.toLocaleString()}
                        </div>
                    </div>

                    <p>This receipt confirms your payment has been processed successfully. Keep this receipt for your records.</p>
                    
                    <div class="footer">
                        <p>SK Binge Galaxy<br>
                        Contact: info@skbingegalaxy.com | +91 9876543210</p>
                    </div>
                </div>
            </div>
        </body>
        </html>
    `,

    bookingReminder: (booking, customer) => `
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <style>
                body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                .header { background: linear-gradient(135deg, #fd79a8 0%, #fdcb6e 100%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                .reminder-details { background: white; padding: 20px; border-radius: 8px; margin: 20px 0; }
                .detail-row { display: flex; justify-content: space-between; margin-bottom: 10px; }
                .detail-label { font-weight: bold; color: #666; }
                .footer { text-align: center; margin-top: 30px; color: #666; font-size: 0.9em; }
            </style>
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <h1>⏰ Event Reminder</h1>
                    <p>SK Binge Galaxy - Don't forget your event!</p>
                </div>
                <div class="content">
                    <p>Dear ${customer.name},</p>
                    <p>This is a friendly reminder about your upcoming event at SK Binge Galaxy.</p>
                    
                    <div class="reminder-details">
                        <h3>Event Details</h3>
                        <div class="detail-row">
                            <span class="detail-label">Booking ID:</span>
                            <span>${booking.bookingId}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Event Type:</span>
                            <span>${getEventTypeLabel(booking.eventType)}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Date & Time:</span>
                            <span>${formatDate(booking.bookingDate)} at ${booking.startTime}</span>
                        </div>
                        <div class="detail-row">
                            <span class="detail-label">Duration:</span>
                            <span>${booking.duration} hours</span>
                        </div>
                    </div>

                    <p><strong>Important Notes:</strong></p>
                    <ul>
                        <li>Please arrive 15 minutes before your scheduled time</li>
                        <li>Bring a valid ID for verification</li>
                        <li>Contact us if you're running late</li>
                        <li>Maximum capacity: 20 guests</li>
                    </ul>

                    <p>We're excited to host your event! If you need to make any changes, please contact us at least 24 hours in advance.</p>
                    
                    <div class="footer">
                        <p>SK Binge Galaxy<br>
                        Contact: info@skbingegalaxy.com | +91 9876543210</p>
                    </div>
                </div>
            </div>
        </body>
        </html>
    `
};

// Helper functions
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

function getServiceLabel(service) {
    const services = {
        'decorations': 'Special Decorations',
        'beverages': 'Beverages Package',
        'photoshoot': 'Professional Photoshoot',
        'fogEffects': 'Fog Effects',
        'redCarpet': 'Red Carpet Entry',
        'cake': 'Custom Cake'
    };
    return services[service] || service;
}

function getServicePrice(service) {
    const prices = {
        'decorations': 1500,
        'beverages': 800,
        'photoshoot': 1200,
        'fogEffects': 1000,
        'redCarpet': 500,
        'cake': 600
    };
    return prices[service] || 0;
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

module.exports = {
    createTransporter,
    emailTemplates
};