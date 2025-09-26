const BlockedDate = require('../models/BlockedDate');

// Get all blocked dates
exports.getBlockedDates = async (req, res) => {
    try {
        const { futureOnly = false } = req.query;
        
        let query = {};
        if (futureOnly) {
            query.date = { $gte: new Date() };
        }

        const blockedDates = await BlockedDate.find(query)
            .populate('blockedBy', 'name')
            .sort({ date: 1 });

        res.json({
            success: true,
            blockedDates
        });

    } catch (error) {
        console.error('Get blocked dates error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error fetching blocked dates'
        });
    }
};

// Create blocked date
exports.createBlockedDate = async (req, res) => {
    try {
        const { date, isFullyBlocked, blockedSlots, reason } = req.body;

        // Check if date is already blocked
        const existingBlock = await BlockedDate.findOne({ date: new Date(date) });
        if (existingBlock) {
            return res.status(400).json({
                success: false,
                message: 'This date is already blocked'
            });
        }

        // Check if date is in past
        const blockDate = new Date(date);
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        if (blockDate < today) {
            return res.status(400).json({
                success: false,
                message: 'Cannot block dates in the past'
            });
        }

        const blockedDate = await BlockedDate.create({
            date: blockDate,
            isFullyBlocked: isFullyBlocked || false,
            blockedSlots: blockedSlots || [],
            reason: reason || 'No reason specified',
            blockedBy: req.user.id
        });

        await blockedDate.populate('blockedBy', 'name');

        res.status(201).json({
            success: true,
            message: 'Date blocked successfully',
            blockedDate
        });

    } catch (error) {
        console.error('Create blocked date error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error creating blocked date'
        });
    }
};

// Update blocked date
exports.updateBlockedDate = async (req, res) => {
    try {
        const { id } = req.params;
        const updateData = req.body;

        const blockedDate = await BlockedDate.findByIdAndUpdate(
            id,
            { $set: updateData },
            { new: true, runValidators: true }
        ).populate('blockedBy', 'name');

        if (!blockedDate) {
            return res.status(404).json({
                success: false,
                message: 'Blocked date not found'
            });
        }

        res.json({
            success: true,
            message: 'Blocked date updated successfully',
            blockedDate
        });

    } catch (error) {
        console.error('Update blocked date error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error updating blocked date'
        });
    }
};

// Delete blocked date
exports.deleteBlockedDate = async (req, res) => {
    try {
        const { id } = req.params;

        const blockedDate = await BlockedDate.findByIdAndDelete(id);

        if (!blockedDate) {
            return res.status(404).json({
                success: false,
                message: 'Blocked date not found'
            });
        }

        res.json({
            success: true,
            message: 'Date unblocked successfully'
        });

    } catch (error) {
        console.error('Delete blocked date error:', error);
        res.status(500).json({
            success: false,
            message: 'Server error deleting blocked date'
        });
    }
};