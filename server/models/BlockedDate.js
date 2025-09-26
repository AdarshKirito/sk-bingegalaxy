// const mongoose = require('mongoose');

// const blockedDateSchema = new mongoose.Schema({
//     date: {
//         type: Date,
//         required: true
//     },
//     blockedSlots: [{
//         startTime: String,
//         endTime: String,
//         reason: String
//     }],
//     isFullyBlocked: {
//         type: Boolean,
//         default: false
//     },
//     blockedBy: {
//         type: mongoose.Schema.Types.ObjectId,
//         ref: 'User',
//         required: true
//     },
//     createdAt: {
//         type: Date,
//         default: Date.now
//     }
// });

// // Ensure date is stored without time component
// blockedDateSchema.pre('save', function(next) {
//     if (this.date) {
//         this.date = new Date(this.date.setHours(0, 0, 0, 0));
//     }
//     next();
// });

// module.exports = mongoose.model('BlockedDate', blockedDateSchema);

const mongoose = require('mongoose');

const blockedDateSchema = new mongoose.Schema({
    date: {
        type: Date,
        required: true,
        unique: true
    },
    isFullyBlocked: {
        type: Boolean,
        default: false
    },
    blockedSlots: [{
        startTime: String,
        endTime: String,
        reason: String
    }],
    reason: {
        type: String,
        default: 'No reason specified'
    },
    blockedBy: {
        type: mongoose.Schema.Types.ObjectId,
        ref: 'User',
        required: true
    }
}, {
    timestamps: true
});

// Ensure date is stored without time component
blockedDateSchema.pre('save', function(next) {
    if (this.date) {
        const dateOnly = new Date(this.date);
        dateOnly.setHours(0, 0, 0, 0);
        this.date = dateOnly;
    }
    next();
});

// Create index for faster queries
blockedDateSchema.index({ date: 1 });

module.exports = mongoose.model('BlockedDate', blockedDateSchema);