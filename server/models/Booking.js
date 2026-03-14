const mongoose = require('mongoose');
const { v4: uuidv4 } = require('uuid');

const bookingSchema = new mongoose.Schema({
    bookingId: {
        type: String,
        default: () => `SKBG${uuidv4().split('-')[0].toUpperCase()}`,
        unique: true
    },
    customer: {
        type: mongoose.Schema.Types.ObjectId,
        ref: 'User',
        required: true
    },
    eventType: {
        type: String,
        required: true,
        enum: ['birthday', 'anniversary', 'surprise', 'proposal', 'screening', 'other']
    },
    bookingDate: {
        type: Date,
        required: true
    },
    startTime: {
        type: String,
        required: true
    },
    duration: {
        type: Number,
        required: true,
        min: 1,
        max: 8
    },
    endTime: {
        type: String,
        required: true
    },
    numberOfGuests: {
        type: Number,
        required: true,
        min: 1,
        max: 20
    },
    additionalServices: {
        decorations: { type: Boolean, default: false },
        beverages: { type: Boolean, default: false },
        photoshoot: { type: Boolean, default: false },
        fogEffects: { type: Boolean, default: false },
        redCarpet: { type: Boolean, default: false },
        cake: { type: Boolean, default: false }
    },
    specialRequests: {
        type: String,
        maxlength: 500
    },
    totalAmount: {
        type: Number,
        required: true
    },
    paymentStatus: {
        type: String,
        enum: ['pending', 'paid', 'failed', 'refunded'],
        default: 'pending'
    },
    bookingStatus: {
        type: String,
        enum: ['confirmed', 'cancelled', 'completed', 'checked-in'],
        default: 'confirmed'
    },
    paymentMethod: {
        type: String,
        enum: ['upi', 'card', 'netbanking', 'cash']
    },
    transactionId: String,
    createdAt: {
        type: Date,
        default: Date.now
    }
});

// Calculate end time based on start time and duration
bookingSchema.pre('save', function(next) {
    if (this.startTime && this.duration) {
        const [hours, minutes] = this.startTime.split(':').map(Number);
        const endHours = (hours + this.duration) % 24;
        this.endTime = `${endHours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}`;
    }
    next();
});

module.exports = mongoose.model('Booking', bookingSchema);