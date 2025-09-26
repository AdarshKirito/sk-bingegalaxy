// Block Dates Management functionality
class BlockDatesManager {
    constructor() {
        this.blockedDates = [];
        this.currentDate = new Date();
        this.selectedDate = null;
        this.init();
    }

    async init() {
        await this.checkAdminAuthentication();
        this.setupEventListeners();
        await this.loadBlockedDates();
        this.generateCalendar();
        this.setMinDate();
    }

    async checkAdminAuthentication() {
        const token = localStorage.getItem('token');
        const admin = JSON.parse(localStorage.getItem('user') || '{}');

        if (!token || !admin.id || admin.role !== 'admin') {
            window.location.href = 'admin-login';
            return;
        }

        document.getElementById('adminName').textContent = admin.name;
    }

    setupEventListeners() {
        // Block type change
        document.getElementById('blockType').addEventListener('change', (e) => {
            this.toggleTimeSlots(e.target.value === 'partial');
        });

        // Custom time slot toggle
        document.getElementById('slotCustom').addEventListener('change', (e) => {
            document.getElementById('customTimeRange').style.display = 
                e.target.checked ? 'block' : 'none';
        });

        // Block date form submission
        document.getElementById('blockDateForm').addEventListener('submit', (e) => {
            e.preventDefault();
            this.blockDate();
        });

        // Filter change
        document.getElementById('blockedFilter').addEventListener('change', (e) => {
            this.filterBlockedDates(e.target.value);
        });

        // Navigation
        this.setupNavigation();
    }

    setupNavigation() {
        document.getElementById('adminMenuBtn')?.addEventListener('click', (e) => {
            e.stopPropagation();
            document.getElementById('adminDropdown').classList.toggle('show');
        });

        document.addEventListener('click', () => {
            document.getElementById('adminDropdown').classList.remove('show');
        });

        document.getElementById('adminLogoutBtn')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.logout();
        });
    }

    toggleTimeSlots(show) {
        document.getElementById('timeSlotsGroup').style.display = show ? 'block' : 'none';
        if (!show) {
            // Uncheck all time slots
            document.querySelectorAll('.time-slot-option input').forEach(checkbox => {
                checkbox.checked = false;
            });
            document.getElementById('customTimeRange').style.display = 'none';
        }
    }

    setMinDate() {
        const today = new Date().toISOString().split('T')[0];
        document.getElementById('blockDate').min = today;
        document.getElementById('blockDate').value = today;
    }

    async loadBlockedDates() {
        try {
            const response = await fetch('/api/admin/blocked-dates', {
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.blockedDates = result.blockedDates || [];
                this.displayBlockedDates();
            } else {
                this.showError('Failed to load blocked dates');
            }
        } catch (error) {
            console.error('Error loading blocked dates:', error);
            this.showError('Network error loading blocked dates');
        }
    }

    generateCalendar() {
        const monthNames = [
            'January', 'February', 'March', 'April', 'May', 'June',
            'July', 'August', 'September', 'October', 'November', 'December'
        ];

        const year = this.currentDate.getFullYear();
        const month = this.currentDate.getMonth();
        
        // Update month display
        document.getElementById('currentMonth').textContent = 
            `${monthNames[month]} ${year}`;

        // Get first day of month and number of days
        const firstDay = new Date(year, month, 1).getDay();
        const daysInMonth = new Date(year, month + 1, 0).getDate();
        
        const calendarBody = document.getElementById('calendarBody');
        calendarBody.innerHTML = '';

        // Add empty cells for days before first day of month
        for (let i = 0; i < firstDay; i++) {
            const emptyDay = this.createCalendarDay('', true);
            calendarBody.appendChild(emptyDay);
        }

        // Add days of the month
        for (let day = 1; day <= daysInMonth; day++) {
            const date = new Date(year, month, day);
            const calendarDay = this.createCalendarDay(day, false, date);
            calendarBody.appendChild(calendarDay);
        }
    }

    createCalendarDay(day, isOtherMonth, date = null) {
        const dayElement = document.createElement('div');
        dayElement.className = 'calendar-day';
        
        if (isOtherMonth) {
            dayElement.classList.add('other-month');
            dayElement.innerHTML = '';
            return dayElement;
        }

        // Check if today
        const today = new Date();
        if (date && date.toDateString() === today.toDateString()) {
            dayElement.classList.add('today');
        }

        // Check if blocked
        const blockedInfo = this.getBlockedInfoForDate(date);
        if (blockedInfo) {
            if (blockedInfo.isFullyBlocked) {
                dayElement.classList.add('fully-blocked');
            } else if (blockedInfo.blockedSlots.length > 0) {
                dayElement.classList.add('partially-blocked');
            }
        }

        // Check if has bookings (you would integrate with bookings data)
        const hasBookings = this.checkDateHasBookings(date);
        if (hasBookings) {
            dayElement.classList.add('has-bookings');
        }

        dayElement.innerHTML = `
            <div class="day-number">${day}</div>
            <div class="day-events">
                ${blockedInfo ? `<span class="event-dot dot-blocked"></span>` : ''}
                ${hasBookings ? `<span class="event-dot dot-booking"></span>` : ''}
            </div>
        `;

        dayElement.addEventListener('click', () => {
            this.showDateDetails(date);
        });

        return dayElement;
    }

    getBlockedInfoForDate(date) {
        if (!date) return null;
        
        const dateStr = date.toISOString().split('T')[0];
        return this.blockedDates.find(blocked => 
            new Date(blocked.date).toISOString().split('T')[0] === dateStr
        );
    }

    checkDateHasBookings(date) {
        // This would integrate with your bookings data
        // For now, return random true/false for demonstration
        return Math.random() > 0.7;
    }

    async showDateDetails(date) {
        const modal = document.getElementById('dateDetailsModal');
        const content = document.getElementById('dateDetailsContent');
        const blockBtn = document.getElementById('blockDateBtn');

        const dateStr = date.toISOString().split('T')[0];
        const blockedInfo = this.getBlockedInfoForDate(date);
        const hasBookings = this.checkDateHasBookings(date);

        let detailsHTML = `
            <div class="date-details">
                <div class="detail-item">
                    <span class="detail-label">Date:</span>
                    <span class="detail-value">${this.formatDate(date)}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">Day:</span>
                    <span class="detail-value">${this.getDayName(date)}</span>
                </div>
        `;

        if (blockedInfo) {
            detailsHTML += `
                <div class="detail-item">
                    <span class="detail-label">Status:</span>
                    <span class="detail-value">
                        <strong style="color: ${blockedInfo.isFullyBlocked ? 'var(--danger)' : 'var(--warning)'}">
                            ${blockedInfo.isFullyBlocked ? 'Fully Blocked' : 'Partially Blocked'}
                        </strong>
                    </span>
                </div>
            `;

            if (blockedInfo.reason) {
                detailsHTML += `
                    <div class="detail-item">
                        <span class="detail-label">Reason:</span>
                        <span class="detail-value">${blockedInfo.reason}</span>
                    </div>
                `;
            }

            if (blockedInfo.blockedSlots.length > 0) {
                detailsHTML += `
                    <div class="detail-item full-width">
                        <span class="detail-label">Blocked Time Slots:</span>
                        <div class="time-slots-list">
                            ${blockedInfo.blockedSlots.map(slot => `
                                <div class="time-slot-item">
                                    <span>${slot.startTime} - ${slot.endTime}</span>
                                    ${slot.reason ? `<small>${slot.reason}</small>` : ''}
                                </div>
                            `).join('')}
                        </div>
                    </div>
                `;
            }

            blockBtn.style.display = 'none';
        } else {
            detailsHTML += `
                <div class="detail-item">
                    <span class="detail-label">Status:</span>
                    <span class="detail-value" style="color: var(--success); font-weight: 600;">
                        Available for Booking
                    </span>
                </div>
            `;

            if (hasBookings) {
                detailsHTML += `
                    <div class="detail-item">
                        <span class="detail-label">Bookings:</span>
                        <span class="detail-value" style="color: var(--primary);">
                            Has existing bookings
                        </span>
                    </div>
                `;
            }

            blockBtn.style.display = 'block';
            blockBtn.onclick = () => {
                this.prepareBlockForm(date);
                closeDateModal();
            };
        }

        detailsHTML += `</div>`;
        content.innerHTML = detailsHTML;
        modal.classList.remove('hidden');
    }

    prepareBlockForm(date) {
        document.getElementById('blockDate').value = date.toISOString().split('T')[0];
        document.getElementById('blockType').focus();
    }

    async blockDate() {
        const formData = this.getBlockFormData();
        
        if (!this.validateBlockForm(formData)) {
            return;
        }

        try {
            const response = await fetch('/api/admin/blocked-dates', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                body: JSON.stringify(formData)
            });

            if (response.ok) {
                const result = await response.json();
                if (result.success) {
                    this.showSuccess('Date blocked successfully');
                    this.loadBlockedDates();
                    this.generateCalendar();
                    this.clearBlockForm();
                } else {
                    this.showError(result.message);
                }
            } else {
                this.showError('Failed to block date');
            }
        } catch (error) {
            console.error('Error blocking date:', error);
            this.showError('Network error blocking date');
        }
    }

    getBlockFormData() {
        const blockDate = document.getElementById('blockDate').value;
        const blockType = document.getElementById('blockType').value;
        const reason = document.getElementById('blockReason').value;

        const data = {
            date: blockDate,
            reason: reason || 'No reason specified'
        };

        if (blockType === 'full') {
            data.isFullyBlocked = true;
        } else {
            data.isFullyBlocked = false;
            data.blockedSlots = this.getSelectedTimeSlots();
        }

        return data;
    }

    getSelectedTimeSlots() {
        const slots = [];
        const customChecked = document.getElementById('slotCustom').checked;

        if (customChecked) {
            const startTime = document.getElementById('customStartTime').value;
            const endTime = document.getElementById('customEndTime').value;
            if (startTime && endTime) {
                slots.push({
                    startTime,
                    endTime,
                    reason: document.getElementById('blockReason').value || 'Custom time block'
                });
            }
        } else {
            // Get checked predefined slots
            document.querySelectorAll('.time-slot-option input:checked').forEach(checkbox => {
                if (checkbox.id !== 'slotCustom') {
                    const [startTime, endTime] = checkbox.value.split('-');
                    slots.push({
                        startTime,
                        endTime: endTime === '00' ? '24:00' : endTime,
                        reason: document.getElementById('blockReason').value || 'Time slot block'
                    });
                }
            });
        }

        return slots;
    }

    validateBlockForm(formData) {
        if (!formData.date) {
            this.showError('Please select a date');
            return false;
        }

        if (formData.isFullyBlocked === false && formData.blockedSlots.length === 0) {
            this.showError('Please select at least one time slot for partial blocking');
            return false;
        }

        // Check if date is in past
        const selectedDate = new Date(formData.date);
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        if (selectedDate < today) {
            this.showError('Cannot block dates in the past');
            return false;
        }

        return true;
    }

    displayBlockedDates() {
        const container = document.getElementById('blockedDatesList');
        
        if (this.blockedDates.length === 0) {
            container.innerHTML = `
                <div class="empty-state">
                    <i class="fas fa-calendar-check" style="font-size: 3rem; color: #ccc; margin-bottom: 1rem;"></i>
                    <p>No dates are currently blocked</p>
                </div>
            `;
            return;
        }

        container.innerHTML = this.blockedDates.map(blocked => this.createBlockedDateItem(blocked)).join('');
    }

    createBlockedDateItem(blocked) {
        const date = new Date(blocked.date);
        const isPast = date < new Date();
        const typeClass = blocked.isFullyBlocked ? 'fully-blocked' : 'partially-blocked';

        return `
            <div class="blocked-date-item ${typeClass}">
                <div class="blocked-date-info">
                    <h4>${this.formatDate(date)}</h4>
                    <div class="blocked-date-meta">
                        <span>${blocked.isFullyBlocked ? 'Fully Blocked' : 'Partially Blocked'}</span>
                        <span>•</span>
                        <span>${blocked.reason}</span>
                        <span>•</span>
                        <span>${isPast ? 'Past Date' : 'Future Date'}</span>
                    </div>
                    ${blocked.blockedSlots && blocked.blockedSlots.length > 0 ? `
                    <div class="time-slots">
                        ${blocked.blockedSlots.map(slot => 
                            `<small>${slot.startTime}-${slot.endTime}</small>`
                        ).join(' ')}
                    </div>
                    ` : ''}
                </div>
                <div class="blocked-date-actions">
                    <button class="btn-icon btn-view" onclick="viewBlockedDate('${blocked._id}')">
                        <i class="fas fa-eye"></i>
                    </button>
                    <button class="btn-icon btn-delete" onclick="unblockDate('${blocked._id}')">
                        <i class="fas fa-unlock"></i>
                    </button>
                </div>
            </div>
        `;
    }

    filterBlockedDates(filter) {
        // This would filter the displayed blocked dates
        // Implementation depends on your data structure
        this.displayBlockedDates(); // Refresh display
    }

    async unblockDate(blockedDateId) {
        if (!confirm('Are you sure you want to unblock this date?')) {
            return;
        }

        try {
            const response = await fetch(`/api/admin/blocked-dates/${blockedDateId}`, {
                method: 'DELETE',
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                if (result.success) {
                    this.showSuccess('Date unblocked successfully');
                    this.loadBlockedDates();
                    this.generateCalendar();
                } else {
                    this.showError(result.message);
                }
            } else {
                this.showError('Failed to unblock date');
            }
        } catch (error) {
            console.error('Error unblocking date:', error);
            this.showError('Network error unblocking date');
        }
    }

    formatDate(date) {
        return date.toLocaleDateString('en-IN', {
            weekday: 'long',
            year: 'numeric',
            month: 'long',
            day: 'numeric'
        });
    }

    getDayName(date) {
        return date.toLocaleDateString('en-IN', { weekday: 'long' });
    }

    showError(message) {
        alert(`Error: ${message}`);
    }

    showSuccess(message) {
        alert(`Success: ${message}`);
    }

    logout() {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = 'admin-login';
    }
}

// Global functions for HTML onclick handlers
function previousMonth() {
    window.blockDatesManager.currentDate.setMonth(window.blockDatesManager.currentDate.getMonth() - 1);
    window.blockDatesManager.generateCalendar();
}

function nextMonth() {
    window.blockDatesManager.currentDate.setMonth(window.blockDatesManager.currentDate.getMonth() + 1);
    window.blockDatesManager.generateCalendar();
}

function clearBlockForm() {
    document.getElementById('blockDateForm').reset();
    document.getElementById('timeSlotsGroup').style.display = 'none';
    document.getElementById('customTimeRange').style.display = 'none';
}

function closeDateModal() {
    document.getElementById('dateDetailsModal').classList.add('hidden');
}

function closeBlockActionsModal() {
    document.getElementById('blockActionsModal').classList.add('hidden');
}

function viewBlockedDate(blockedDateId) {
    // Implementation for viewing blocked date details
    alert(`View details for blocked date ${blockedDateId}`);
}

function unblockDate(blockedDateId) {
    window.blockDatesManager.unblockDate(blockedDateId);
}

// Initialize block dates manager when page loads
document.addEventListener('DOMContentLoaded', () => {
    window.blockDatesManager = new BlockDatesManager();
});