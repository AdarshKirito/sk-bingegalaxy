const BlockedDate = require('../models/BlockedDate');

const parseDateOnlyUTC = (dateStr) => {
    if (!dateStr || typeof dateStr !== 'string') {
        return null;
    }

    const [year, month, day] = dateStr.split('-').map(Number);
    if (!year || !month || !day) {
        return null;
    }

    return new Date(Date.UTC(year, month - 1, day, 0, 0, 0, 0));
};

const todayUTCDateOnly = () => {
    const now = new Date();
    return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate(), 0, 0, 0, 0));
};

const todayLocalDateKey = () => {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
};

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

        const blockDate = parseDateOnlyUTC(date);
        if (!blockDate) {
            return res.status(400).json({
                success: false,
                message: 'Invalid date format. Use YYYY-MM-DD'
            });
        }

        // Check if date is in past using local date keys.
        const selectedDateKey = date;
        const todayKey = todayLocalDateKey();

        if (selectedDateKey < todayKey) {
            return res.status(400).json({
                success: false,
                message: 'Cannot block dates in the past'
            });
        }

        // Validate partial slots: start < end, minimum 30-minute duration.
        if (!isFullyBlocked && Array.isArray(blockedSlots) && blockedSlots.length > 0) {
            const timeToMins = (t) => {
                if (!t || !t.includes(':')) return null;
                const [h, m] = t.split(':').map(Number);
                if (Number.isNaN(h) || Number.isNaN(m)) return null;
                return (h * 60) + m;
            };
            for (const slot of blockedSlots) {
                const startMins = timeToMins(slot.startTime);
                const endMins   = timeToMins(slot.endTime);
                if (startMins === null || endMins === null) {
                    return res.status(400).json({ success: false, message: 'Invalid time range in blocked slot.' });
                }
                if (endMins <= startMins) {
                    return res.status(400).json({ success: false, message: 'End time must be after start time.' });
                }
                if ((endMins - startMins) < 30) {
                    return res.status(400).json({ success: false, message: 'Each time slot must be at least 30 minutes long.' });
                }
            }
        }

        // Check if date is already blocked. If partially blocked, merge new slots.
        const existingBlock = await BlockedDate.findOne({ date: blockDate });
        if (existingBlock) {
            if (existingBlock.isFullyBlocked) {
                return res.status(400).json({
                    success: false,
                    message: 'This date is already fully blocked'
                });
            }

            if (isFullyBlocked) {
                existingBlock.isFullyBlocked = true;
                existingBlock.blockedSlots = [];
                existingBlock.reason = reason || existingBlock.reason;
                await existingBlock.save();
                await existingBlock.populate('blockedBy', 'name');

                return res.status(200).json({
                    success: true,
                    message: 'Date updated to fully blocked',
                    blockedDate: existingBlock
                });
            }

            const incomingSlots = Array.isArray(blockedSlots) ? blockedSlots : [];
            const slotKey = (slot) => `${slot.startTime}-${slot.endTime}`;
            const existingKeys = new Set((existingBlock.blockedSlots || []).map(slotKey));
            const uniqueNewSlots = incomingSlots.filter((slot) => !existingKeys.has(slotKey(slot)));

            existingBlock.blockedSlots = [...(existingBlock.blockedSlots || []), ...uniqueNewSlots];
            existingBlock.reason = reason || existingBlock.reason;
            await existingBlock.save();
            await existingBlock.populate('blockedBy', 'name');

            return res.status(200).json({
                success: true,
                message: uniqueNewSlots.length > 0
                    ? 'Time slots added to existing blocked date'
                    : 'Selected time slot already exists for this date',
                blockedDate: existingBlock
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