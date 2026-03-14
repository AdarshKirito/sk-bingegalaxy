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

        // Date change — re-filter slots so past ones are hidden when today is chosen
        document.getElementById('blockDate').addEventListener('change', (e) => {
            this.filterSlotsForDate(e.target.value);
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
        } else {
            // Filter out already-past slots if today is selected
            this.filterSlotsForDate(document.getElementById('blockDate').value);
        }
    }

    filterSlotsForDate(dateValue) {
        const todayKey = this.toLocalDateKey(new Date());
        const isToday = (dateValue === todayKey);
        const now = new Date();
        const nowMinutes = (now.getHours() * 60) + now.getMinutes();

        document.querySelectorAll('.time-slot-option').forEach(option => {
            const checkbox = option.querySelector('input[type="checkbox"]');
            if (!checkbox || checkbox.id === 'slotCustom') return;

            const value = checkbox.value; // e.g. "09:00-12:00"
            if (!value) return;

            const endTimeStr = value.split('-')[1];
            const [endH, endM] = endTimeStr.split(':').map(Number);
            const endMinutes = (endH * 60) + endM;

            const isPast = isToday && (endMinutes <= nowMinutes);
            option.classList.toggle('past-slot', isPast);
            checkbox.disabled = isPast;
            if (isPast) checkbox.checked = false;

            // Show/hide "(unavailable)" hint
            let hint = option.querySelector('.past-hint');
            if (isPast) {
                if (!hint) {
                    hint = document.createElement('span');
                    hint.className = 'past-hint';
                    hint.textContent = '(unavailable)';
                    option.appendChild(hint);
                }
            } else if (hint) {
                hint.remove();
            }
        });
    }

    setMinDate() {
        const today = this.toLocalDateKey(new Date());
        document.getElementById('blockDate').min = today;
        document.getElementById('blockDate').value = today;
    }

    toLocalDateKey(dateInput) {
        const d = new Date(dateInput);
        const year = d.getFullYear();
        const month = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
    }

    toStoredDateKey(dateInput) {
        if (typeof dateInput === 'string' && dateInput.includes('T')) {
            return dateInput.split('T')[0];
        }

        const d = new Date(dateInput);
        const year = d.getUTCFullYear();
        const month = String(d.getUTCMonth() + 1).padStart(2, '0');
        const day = String(d.getUTCDate()).padStart(2, '0');
        return `${year}-${month}-${day}`;
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

        const dateStr = this.toLocalDateKey(date);
        return this.blockedDates.find(blocked => 
            this.toStoredDateKey(blocked.date) === dateStr
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

        const blockedInfo = this.getBlockedInfoForDate(date);
        const hasBookings = this.checkDateHasBookings(date);

        let detailsHTML = `
            <div class="bd-date-details">
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Date:</span>
                    <span class="bd-detail-value">${this.formatDate(date)}</span>
                </div>
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Day:</span>
                    <span class="bd-detail-value">${this.getDayName(date)}</span>
                </div>
        `;

        if (blockedInfo) {
            detailsHTML += `
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Status:</span>
                    <span class="bd-detail-value">
                        <strong style="color: ${blockedInfo.isFullyBlocked ? 'var(--danger)' : 'var(--warning)'}">
                            ${blockedInfo.isFullyBlocked ? 'Fully Blocked' : 'Partially Blocked'}
                        </strong>
                    </span>
                </div>
            `;

            if (blockedInfo.reason) {
                detailsHTML += `
                    <div class="bd-detail-item">
                        <span class="bd-detail-label">Reason:</span>
                        <span class="bd-detail-value">${blockedInfo.reason}</span>
                    </div>
                `;
            }

            if (blockedInfo.blockedSlots.length > 0) {
                detailsHTML += `
                    <div class="bd-detail-item bd-full-width">
                        <span class="bd-detail-label">Blocked Time Slots:</span>
                        <div class="bd-time-slots-list">
                            ${blockedInfo.blockedSlots.map(slot => `
                                <div class="bd-time-slot-item">
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
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Status:</span>
                    <span class="bd-detail-value" style="color: var(--success); font-weight: 600;">
                        Available for Booking
                    </span>
                </div>
            `;

            if (hasBookings) {
                detailsHTML += `
                    <div class="bd-detail-item">
                        <span class="bd-detail-label">Bookings:</span>
                        <span class="bd-detail-value" style="color: var(--primary);">
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
        document.getElementById('blockDate').value = this.toLocalDateKey(date);
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
                    await this.loadBlockedDates();
                    this.generateCalendar();
                    clearBlockForm();
                } else {
                    this.showError(result.message);
                }
            } else {
                let errorMessage = 'Failed to block date';
                try {
                    const errorResult = await response.json();
                    if (errorResult && errorResult.message) {
                        errorMessage = errorResult.message;
                    }
                } catch (parseError) {
                    // Keep fallback message when response body is not JSON.
                }
                this.showError(errorMessage);
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

    to24Hour(hour, min, period) {
        let h = parseInt(hour, 10);
        if (period === 'AM') {
            if (h === 12) h = 0;
        } else {
            if (h !== 12) h += 12;
        }
        return `${String(h).padStart(2, '0')}:${min || '00'}`;
    }

    getSelectedTimeSlots() {
        const slots = [];
        const customChecked = document.getElementById('slotCustom').checked;

        if (customChecked) {
            const startHour = document.getElementById('customStartHour').value;
            const startMin = document.getElementById('customStartMin').value;
            const startPeriod = document.getElementById('customStartPeriod').value;
            const endHour = document.getElementById('customEndHour').value;
            const endMin = document.getElementById('customEndMin').value;
            const endPeriod = document.getElementById('customEndPeriod').value;

            if (startHour && endHour) {
                const startTime = this.to24Hour(startHour, startMin, startPeriod);
                const endTime = this.to24Hour(endHour, endMin, endPeriod);
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

    timeToMinutes(timeText) {
        if (!timeText || !timeText.includes(':')) return null;
        const [h, m] = timeText.split(':').map(Number);
        if (Number.isNaN(h) || Number.isNaN(m)) return null;
        return (h * 60) + m;
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

        // Compare date-only strings in local timezone to avoid UTC parsing shifts.
        const todayKey = this.toLocalDateKey(new Date());
        if (formData.date < todayKey) {
            this.showError('Cannot block dates in the past');
            return false;
        }

        // Validate each custom/partial slot: start < end, and minimum 30-minute duration.
        if (formData.isFullyBlocked === false && formData.blockedSlots.length > 0) {
            for (const slot of formData.blockedSlots) {
                const startMins = this.timeToMinutes(slot.startTime);
                const endMins   = this.timeToMinutes(slot.endTime);

                if (startMins === null || endMins === null) {
                    this.showError('Invalid time range. Please select both start and end times.');
                    return false;
                }

                if (endMins <= startMins) {
                    this.showError('End time must be after start time.');
                    return false;
                }

                if ((endMins - startMins) < 30) {
                    this.showError('Time slot must be at least 30 minutes long.');
                    return false;
                }
            }
        }

        // If partial block is for today, at least one selected slot must still be in the future.
        if (formData.isFullyBlocked === false && formData.date === todayKey) {
            const now = new Date();
            const nowMinutes = (now.getHours() * 60) + now.getMinutes();

            const hasFutureSlot = formData.blockedSlots.some((slot) => {
                const endMinutes = this.timeToMinutes(slot.endTime);
                return endMinutes !== null && endMinutes > nowMinutes;
            });

            if (!hasFutureSlot) {
                this.showError('Selected time slot is in the past. Choose a future time slot for today.');
                return false;
            }
        }

        return true;
    }

    displayBlockedDates() {
        const container = document.getElementById('blockedDatesList');
        const selectedFilter = document.getElementById('blockedFilter')?.value || 'all';
        const filteredDates = this.getFilteredBlockedDates(selectedFilter);
        const countInfo = document.getElementById('blockedCountInfo');

        if (countInfo) {
            countInfo.textContent = `Showing ${filteredDates.length} of ${this.blockedDates.length}`;
        }
        
        if (filteredDates.length === 0) {
            const emptyMessage = selectedFilter === 'future'
                ? 'No future blocked dates found'
                : selectedFilter === 'past'
                    ? 'No past blocked dates found'
                    : 'No dates are currently blocked';

            container.innerHTML = `
                <div class="empty-state">
                    <i class="fas fa-calendar-check" style="font-size: 3rem; color: #ccc; margin-bottom: 1rem;"></i>
                    <p>${emptyMessage}</p>
                </div>
            `;
            return;
        }

        container.innerHTML = filteredDates.map(blocked => this.createBlockedDateItem(blocked)).join('');
    }

    getFilteredBlockedDates(filter) {
        const todayKey = this.toLocalDateKey(new Date());

        if (filter === 'future') {
            return this.blockedDates.filter((blocked) => this.toStoredDateKey(blocked.date) >= todayKey);
        }

        if (filter === 'past') {
            return this.blockedDates.filter((blocked) => this.toStoredDateKey(blocked.date) < todayKey);
        }

        return this.blockedDates;
    }

    createBlockedDateItem(blocked) {
        const date = new Date(blocked.date);
        const todayKey = this.toLocalDateKey(new Date());
        const isPast = this.toStoredDateKey(blocked.date) < todayKey;
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
        // Keep dropdown behavior explicit and refresh the list with selected filter.
        this.displayBlockedDates();
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

    viewBlockedDate(blockedDateId) {
        const blockedInfo = this.blockedDates.find(item => item._id === blockedDateId);

        if (!blockedInfo) {
            this.showError('Blocked date details not found');
            return;
        }

        const modal = document.getElementById('dateDetailsModal');
        const content = document.getElementById('dateDetailsContent');
        const blockBtn = document.getElementById('blockDateBtn');
        const date = new Date(blockedInfo.date);

        let detailsHTML = `
            <div class="bd-date-details">
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Date:</span>
                    <span class="bd-detail-value">${this.formatDate(date)}</span>
                </div>
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Day:</span>
                    <span class="bd-detail-value">${this.getDayName(date)}</span>
                </div>
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Status:</span>
                    <span class="bd-detail-value">
                        <strong style="color: ${blockedInfo.isFullyBlocked ? 'var(--danger)' : 'var(--warning)'}">
                            ${blockedInfo.isFullyBlocked ? 'Fully Blocked' : 'Partially Blocked'}
                        </strong>
                    </span>
                </div>
                <div class="bd-detail-item">
                    <span class="bd-detail-label">Reason:</span>
                    <span class="bd-detail-value">${blockedInfo.reason || 'No reason specified'}</span>
                </div>
        `;

        if (blockedInfo.blockedSlots && blockedInfo.blockedSlots.length > 0) {
            detailsHTML += `
                <div class="bd-detail-item bd-full-width">
                    <span class="bd-detail-label">Blocked Time Slots:</span>
                    <div class="bd-time-slots-list">
                        ${blockedInfo.blockedSlots.map(slot => `
                            <div class="bd-time-slot-item">
                                <span>${slot.startTime} - ${slot.endTime}</span>
                                ${slot.reason ? `<small>${slot.reason}</small>` : ''}
                            </div>
                        `).join('')}
                    </div>
                </div>
            `;
        }

        detailsHTML += '</div>';
        content.innerHTML = detailsHTML;
        blockBtn.style.display = 'none';
        modal.classList.remove('hidden');
    }

    formatDate(date) {
        return date.toLocaleDateString('en-IN', {
            timeZone: 'UTC',
            weekday: 'long',
            year: 'numeric',
            month: 'long',
            day: 'numeric'
        });
    }

    getDayName(date) {
        return date.toLocaleDateString('en-IN', {
            timeZone: 'UTC',
            weekday: 'long'
        });
    }

    showError(message) {
        this.showToast(`Error: ${message}`, 'error');
    }

    showSuccess(message) {
        this.showToast(`Success: ${message}`, 'success');
    }

    showToast(message, type = 'success') {
        const existing = document.querySelector('.bd-toast');
        if (existing) {
            existing.remove();
        }

        const toast = document.createElement('div');
        toast.className = `bd-toast bd-toast-${type}`;
        toast.textContent = message;
        document.body.appendChild(toast);

        requestAnimationFrame(() => {
            toast.classList.add('show');
        });

        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 250);
        }, 2600);
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
    window.blockDatesManager.viewBlockedDate(blockedDateId);
}

function unblockDate(blockedDateId) {
    window.blockDatesManager.unblockDate(blockedDateId);
}

// Initialize block dates manager when page loads
document.addEventListener('DOMContentLoaded', () => {
    window.blockDatesManager = new BlockDatesManager();
});