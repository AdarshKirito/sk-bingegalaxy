// Admin Bookings Management functionality
class AdminBookingsManager {
    constructor() {
        this.bookings = [];
        this.filteredBookings = [];
        this.currentPage = 1;
        this.itemsPerPage = 10;
        this.filters = {
            search: '',
            status: 'all',
            date: 'all',
            event: 'all'
        };
        this.currentBooking = null;
        this.init();
    }

    async init() {
        await this.checkAdminAuthentication();
        await this.loadBookings();
        this.setupEventListeners();
        this.applyFilters();
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

    async loadBookings() {
        try {
            const response = await fetch('/api/admin/bookings', {
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.bookings = result.bookings || [];
                this.updateStats();
                this.displayBookings();
            } else {
                this.showError('Failed to load bookings');
            }
        } catch (error) {
            console.error('Error loading bookings:', error);
            this.showError('Network error loading bookings');
        }
    }

    updateStats() {
        const today = new Date().toDateString();
        const todayBookings = this.bookings.filter(booking => 
            new Date(booking.bookingDate).toDateString() === today
        );
        
        const checkedIn = this.bookings.filter(booking => 
            booking.bookingStatus === 'checked-in'
        ).length;

        const todayRevenue = todayBookings.reduce((sum, booking) => 
            sum + (booking.paymentStatus === 'paid' ? booking.totalAmount : 0), 0
        );

        document.getElementById('totalBookings').textContent = this.bookings.length;
        document.getElementById('todayBookings').textContent = todayBookings.length;
        document.getElementById('checkedIn').textContent = checkedIn;
        document.getElementById('revenue').textContent = `₹${todayRevenue.toLocaleString()}`;
    }

    displayBookings() {
        const tbody = document.getElementById('bookingsTableBody');

        if (this.filteredBookings.length === 0) {
            tbody.innerHTML = `
                <tr>
                    <td colspan="9" class="no-data">No bookings found matching your filters</td>
                </tr>
            `;
            return;
        }

        const startIndex = (this.currentPage - 1) * this.itemsPerPage;
        const endIndex = startIndex + this.itemsPerPage;
        const pageBookings = this.filteredBookings.slice(startIndex, endIndex);

        tbody.innerHTML = pageBookings.map(booking => this.createBookingRow(booking)).join('');

        this.updatePagination();
    }

    createBookingRow(booking) {
        const customer = booking.customer || {};
        
        return `
            <tr>
                <td>
                    <strong>${booking.bookingId}</strong>
                    <br><small>${this.formatDate(booking.createdAt)}</small>
                </td>
                <td>
                    <div class="customer-info">
                        <span class="customer-name">${customer.name || 'N/A'}</span>
                        <span class="customer-contact">${customer.email || ''}</span>
                        <span class="customer-contact">${customer.phone || ''}</span>
                    </div>
                </td>
                <td>${this.getEventTypeLabel(booking.eventType)}</td>
                <td>
                    ${this.formatDate(booking.bookingDate)}
                    <br><small>${booking.startTime} - ${booking.endTime}</small>
                </td>
                <td>${booking.numberOfGuests}</td>
                <td>₹${booking.totalAmount.toLocaleString()}</td>
                <td>
                    <span class="status-badge badge-${booking.bookingStatus}">
                        ${booking.bookingStatus}
                    </span>
                </td>
                <td>
                    <span class="payment-status payment-${booking.paymentStatus || 'pending'}">
                        ${booking.paymentStatus || 'pending'}
                    </span>
                </td>
                <td>
                    <div class="actions-dropdown">
                        <button class="actions-btn" onclick="toggleActionsMenu('${booking.bookingId}')">
                            Actions ▼
                        </button>
                        <div class="actions-menu" id="actions-${booking.bookingId}">
                            <button class="action-item view" onclick="viewBookingDetails('${booking.bookingId}')">
                                👁️ View Details
                            </button>
                            <button class="action-item edit" onclick="editBooking('${booking.bookingId}')">
                                ✏️ Edit Booking
                            </button>
                            ${booking.bookingStatus === 'confirmed' ? `
                            <button class="action-item checkin" onclick="checkInBooking('${booking.bookingId}')">
                                ✅ Check-in
                            </button>
                            ` : ''}
                            ${booking.bookingStatus === 'checked-in' ? `
                            <button class="action-item checkout" onclick="checkOutBooking('${booking.bookingId}')">
                                🏠 Check-out
                            </button>
                            ` : ''}
                            ${['confirmed', 'checked-in'].includes(booking.bookingStatus) ? `
                            <button class="action-item cancel" onclick="cancelBookingAdmin('${booking.bookingId}')">
                                ❌ Cancel
                            </button>
                            ` : ''}
                        </div>
                    </div>
                </td>
            </tr>
        `;
    }

    applyFilters() {
        this.filteredBookings = this.bookings.filter(booking => {
            // Search filter
            if (this.filters.search) {
                const searchTerm = this.filters.search.toLowerCase();
                const matchesId = booking.bookingId.toLowerCase().includes(searchTerm);
                const matchesName = (booking.customer?.name || '').toLowerCase().includes(searchTerm);
                const matchesEmail = (booking.customer?.email || '').toLowerCase().includes(searchTerm);
                const matchesPhone = (booking.customer?.phone || '').includes(this.filters.search);
                
                if (!matchesId && !matchesName && !matchesEmail && !matchesPhone) return false;
            }

            // Status filter
            if (this.filters.status !== 'all' && booking.bookingStatus !== this.filters.status) {
                return false;
            }

            // Event type filter
            if (this.filters.event !== 'all' && booking.eventType !== this.filters.event) {
                return false;
            }

            // Date filter
            if (this.filters.date !== 'all') {
                const bookingDate = new Date(booking.bookingDate);
                const today = new Date();
                today.setHours(0, 0, 0, 0);

                switch (this.filters.date) {
                    case 'today':
                        if (bookingDate.toDateString() !== today.toDateString()) return false;
                        break;
                    case 'tomorrow':
                        const tomorrow = new Date(today);
                        tomorrow.setDate(tomorrow.getDate() + 1);
                        if (bookingDate.toDateString() !== tomorrow.toDateString()) return false;
                        break;
                    case 'week':
                        const weekEnd = new Date(today);
                        weekEnd.setDate(weekEnd.getDate() + 7);
                        if (bookingDate < today || bookingDate > weekEnd) return false;
                        break;
                    case 'month':
                        const monthEnd = new Date(today);
                        monthEnd.setMonth(monthEnd.getMonth() + 1);
                        if (bookingDate < today || bookingDate > monthEnd) return false;
                        break;
                }
            }

            return true;
        });

        this.currentPage = 1;
        this.displayBookings();
    }

    updatePagination() {
        const pagination = document.getElementById('pagination');
        const totalPages = Math.ceil(this.filteredBookings.length / this.itemsPerPage);

        if (totalPages <= 1) {
            pagination.innerHTML = '';
            return;
        }

        pagination.innerHTML = `
            <button onclick="adminBookingsManager.prevPage()" ${this.currentPage === 1 ? 'disabled' : ''}>
                ← Previous
            </button>
            
            <span class="pagination-info">
                Page ${this.currentPage} of ${totalPages} (${this.filteredBookings.length} bookings)
            </span>
            
            <button onclick="adminBookingsManager.nextPage()" ${this.currentPage === totalPages ? 'disabled' : ''}>
                Next →
            </button>
        `;
    }

    setupEventListeners() {
        // Filter event listeners
        document.getElementById('searchInput').addEventListener('input', (e) => {
            this.filters.search = e.target.value;
            this.applyFilters();
        });

        document.getElementById('statusFilter').addEventListener('change', (e) => {
            this.filters.status = e.target.value;
            this.applyFilters();
        });

        document.getElementById('dateFilter').addEventListener('change', (e) => {
            this.filters.date = e.target.value;
            this.applyFilters();
        });

        document.getElementById('eventFilter').addEventListener('change', (e) => {
            this.filters.event = e.target.value;
            this.applyFilters();
        });

        // Navigation
        this.setupNavigation();

        // Close dropdowns when clicking outside
        document.addEventListener('click', (e) => {
            if (!e.target.closest('.actions-dropdown')) {
                document.querySelectorAll('.actions-menu').forEach(menu => {
                    menu.classList.remove('show');
                });
            }
        });
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

    async editBooking(bookingId) {
        try {
            const response = await fetch(`/api/admin/bookings/${bookingId}`, {
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.displayEditModal(result.booking);
            } else {
                this.showError('Failed to load booking details');
            }
        } catch (error) {
            console.error('Error loading booking details:', error);
            this.showError('Network error loading booking details');
        }
    }

    displayEditModal(booking) {
        this.currentBooking = booking;
        const modal = document.getElementById('editModal');
        const content = document.getElementById('editFormContent');

        const services = booking.additionalServices || {};

        content.innerHTML = `
            <div class="edit-form">
                <div class="form-section">
                    <h4>Customer Information</h4>
                    <div class="form-group">
                        <label>Customer Name</label>
                        <input type="text" class="form-control" value="${booking.customer?.name || ''}" disabled>
                    </div>
                    <div class="form-group">
                        <label>Contact Information</label>
                        <input type="text" class="form-control" value="${booking.customer?.email || ''} | ${booking.customer?.phone || ''}" disabled>
                    </div>
                </div>

                <div class="form-section">
                    <h4>Booking Details</h4>
                    <div class="form-grid">
                        <div class="form-group">
                            <label for="editEventType">Event Type</label>
                            <select class="form-control" id="editEventType">
                                <option value="birthday" ${booking.eventType === 'birthday' ? 'selected' : ''}>Birthday</option>
                                <option value="anniversary" ${booking.eventType === 'anniversary' ? 'selected' : ''}>Anniversary</option>
                                <option value="surprise" ${booking.eventType === 'surprise' ? 'selected' : ''}>Surprise</option>
                                <option value="proposal" ${booking.eventType === 'proposal' ? 'selected' : ''}>Proposal</option>
                                <option value="screening" ${booking.eventType === 'screening' ? 'selected' : ''}>Screening</option>
                                <option value="other" ${booking.eventType === 'other' ? 'selected' : ''}>Other</option>
                            </select>
                        </div>

                        <div class="form-group">
                            <label for="editBookingDate">Date</label>
                            <input type="date" class="form-control" id="editBookingDate" value="${booking.bookingDate.split('T')[0]}">
                        </div>

                        <div class="form-group">
                            <label for="editStartTime">Start Time</label>
                            <input type="time" class="form-control" id="editStartTime" value="${booking.startTime}">
                        </div>

                        <div class="form-group">
                            <label for="editDuration">Duration (hours)</label>
                            <select class="form-control" id="editDuration">
                                <option value="2" ${booking.duration === 2 ? 'selected' : ''}>2 Hours</option>
                                <option value="3" ${booking.duration === 3 ? 'selected' : ''}>3 Hours</option>
                                <option value="4" ${booking.duration === 4 ? 'selected' : ''}>4 Hours</option>
                                <option value="5" ${booking.duration === 5 ? 'selected' : ''}>5 Hours</option>
                            </select>
                        </div>

                        <div class="form-group">
                            <label for="editGuests">Number of Guests</label>
                            <input type="number" class="form-control" id="editGuests" value="${booking.numberOfGuests}" min="1" max="20">
                        </div>

                        <div class="form-group">
                            <label for="editStatus">Booking Status</label>
                            <select class="form-control" id="editStatus">
                                <option value="confirmed" ${booking.bookingStatus === 'confirmed' ? 'selected' : ''}>Confirmed</option>
                                <option value="checked-in" ${booking.bookingStatus === 'checked-in' ? 'selected' : ''}>Checked-in</option>
                                <option value="completed" ${booking.bookingStatus === 'completed' ? 'selected' : ''}>Completed</option>
                                <option value="cancelled" ${booking.bookingStatus === 'cancelled' ? 'selected' : ''}>Cancelled</option>
                            </select>
                        </div>
                    </div>
                </div>

                <div class="form-section">
                    <h4>Additional Services</h4>
                    <div class="services-grid">
                        <label class="service-checkbox">
                            <input type="checkbox" id="editDecorations" ${services.decorations ? 'checked' : ''}>
                            <span>Special Decorations (+₹1500)</span>
                        </label>
                        <label class="service-checkbox">
                            <input type="checkbox" id="editBeverages" ${services.beverages ? 'checked' : ''}>
                            <span>Beverages Package (+₹800)</span>
                        </label>
                        <label class="service-checkbox">
                            <input type="checkbox" id="editPhotoshoot" ${services.photoshoot ? 'checked' : ''}>
                            <span>Professional Photoshoot (+₹1200)</span>
                        </label>
                        <label class="service-checkbox">
                            <input type="checkbox" id="editFogEffects" ${services.fogEffects ? 'checked' : ''}>
                            <span>Fog Effects (+₹1000)</span>
                        </label>
                        <label class="service-checkbox">
                            <input type="checkbox" id="editRedCarpet" ${services.redCarpet ? 'checked' : ''}>
                            <span>Red Carpet (+₹500)</span>
                        </label>
                        <label class="service-checkbox">
                            <input type="checkbox" id="editCake" ${services.cake ? 'checked' : ''}>
                            <span>Custom Cake (+₹600)</span>
                        </label>
                    </div>
                </div>

                <div class="form-section">
                    <h4>Special Requests</h4>
                    <textarea class="form-control" id="editSpecialRequests" rows="4">${booking.specialRequests || ''}</textarea>
                </div>
            </div>
        `;

        modal.classList.remove('hidden');
    }

    async saveBookingChanges() {
        if (!this.currentBooking) return;

        const updatedData = {
            eventType: document.getElementById('editEventType').value,
            bookingDate: document.getElementById('editBookingDate').value,
            startTime: document.getElementById('editStartTime').value,
            duration: parseInt(document.getElementById('editDuration').value),
            numberOfGuests: parseInt(document.getElementById('editGuests').value),
            bookingStatus: document.getElementById('editStatus').value,
            additionalServices: {
                decorations: document.getElementById('editDecorations').checked,
                beverages: document.getElementById('editBeverages').checked,
                photoshoot: document.getElementById('editPhotoshoot').checked,
                fogEffects: document.getElementById('editFogEffects').checked,
                redCarpet: document.getElementById('editRedCarpet').checked,
                cake: document.getElementById('editCake').checked
            },
            specialRequests: document.getElementById('editSpecialRequests').value
        };

        try {
            const response = await fetch(`/api/admin/bookings/${this.currentBooking.bookingId}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                body: JSON.stringify(updatedData)
            });

            if (response.ok) {
                const result = await response.json();
                if (result.success) {
                    this.showSuccess('Booking updated successfully');
                    this.loadBookings(); // Reload bookings
                    this.closeEditModal();
                } else {
                    this.showError(result.message);
                }
            } else {
                this.showError('Failed to update booking');
            }
        } catch (error) {
            console.error('Error updating booking:', error);
            this.showError('Network error updating booking');
        }
    }

    async performBookingAction(bookingId, action, data = {}) {
        try {
            const response = await fetch(`/api/admin/bookings/${bookingId}/${action}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                body: JSON.stringify(data)
            });

            if (response.ok) {
                const result = await response.json();
                if (result.success) {
                    this.showSuccess(`Booking ${action} successful`);
                    this.loadBookings(); // Reload bookings
                } else {
                    this.showError(result.message);
                }
            } else {
                this.showError(`Failed to ${action} booking`);
            }
        } catch (error) {
            console.error(`Error ${action} booking:`, error);
            this.showError(`Network error ${action} booking`);
        }
    }

    nextPage() {
        const totalPages = Math.ceil(this.filteredBookings.length / this.itemsPerPage);
        if (this.currentPage < totalPages) {
            this.currentPage++;
            this.displayBookings();
        }
    }

    prevPage() {
        if (this.currentPage > 1) {
            this.currentPage--;
            this.displayBookings();
        }
    }

    closeEditModal() {
        document.getElementById('editModal').classList.add('hidden');
        this.currentBooking = null;
    }

    getEventTypeLabel(type) {
        const types = {
            'birthday': 'Birthday',
            'anniversary': 'Anniversary',
            'surprise': 'Surprise',
            'proposal': 'Proposal',
            'screening': 'Screening',
            'other': 'Other'
        };
        return types[type] || type;
    }

    formatDate(dateString) {
        return new Date(dateString).toLocaleDateString('en-IN');
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
function toggleActionsMenu(bookingId) {
    const menu = document.getElementById(`actions-${bookingId}`);
    menu.classList.toggle('show');
}

function editBooking(bookingId) {
    window.adminBookingsManager.editBooking(bookingId);
}

function checkInBooking(bookingId) {
    if (confirm('Check in this booking?')) {
        window.adminBookingsManager.performBookingAction(bookingId, 'checkin');
    }
}

function checkOutBooking(bookingId) {
    if (confirm('Check out this booking?')) {
        window.adminBookingsManager.performBookingAction(bookingId, 'checkout');
    }
}

function cancelBookingAdmin(bookingId) {
    if (confirm('Are you sure you want to cancel this booking?')) {
        window.adminBookingsManager.performBookingAction(bookingId, 'cancel');
    }
}

function viewBookingDetails(bookingId) {
    // Similar to customer view but with admin privileges
    alert(`View details for booking ${bookingId}`);
}

function saveBookingChanges() {
    window.adminBookingsManager.saveBookingChanges();
}

function closeEditModal() {
    window.adminBookingsManager.closeEditModal();
}

function closeActionsModal() {
    document.getElementById('actionsModal').classList.add('hidden');
}

function refreshBookings() {
    window.adminBookingsManager.loadBookings();
}

// Initialize admin bookings manager when page loads
document.addEventListener('DOMContentLoaded', () => {
    window.adminBookingsManager = new AdminBookingsManager();
});