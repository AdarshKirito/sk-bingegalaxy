// Bookings Management functionality
class BookingsManager {
    constructor() {
        this.bookings = [];
        this.filteredBookings = [];
        this.currentPage = 1;
        this.itemsPerPage = 6;
        this.filters = {
            status: 'all',
            date: 'all',
            search: ''
        };
        this.init();
    }

    async init() {
        await this.checkAuthentication();
        await this.loadBookings();
        this.setupEventListeners();
        this.applyFilters();
    }

    async checkAuthentication() {
        const token = localStorage.getItem('token');
        const user = JSON.parse(localStorage.getItem('user') || '{}');

        if (!token || !user.id) {
            window.location.href = 'login';
            return;
        }

        document.getElementById('userName').textContent = `Welcome, ${user.name}`;
    }

    async loadBookings() {
        try {
            const response = await fetch('/api/bookings/my-bookings', {
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.bookings = result.bookings || [];
                this.displayBookings();
            } else {
                this.showError('Failed to load bookings');
            }
        } catch (error) {
            console.error('Error loading bookings:', error);
            this.showError('Network error loading bookings');
        }
    }

    displayBookings() {
        const container = document.getElementById('bookingsGrid');
        const emptyState = document.getElementById('emptyState');

        if (this.filteredBookings.length === 0) {
            container.classList.add('hidden');
            emptyState.classList.remove('hidden');
            return;
        }

        container.classList.remove('hidden');
        emptyState.classList.add('hidden');

        const startIndex = (this.currentPage - 1) * this.itemsPerPage;
        const endIndex = startIndex + this.itemsPerPage;
        const pageBookings = this.filteredBookings.slice(startIndex, endIndex);

        container.innerHTML = pageBookings.map(booking => this.createBookingCard(booking)).join('');

        this.updatePagination();
    }

    createBookingCard(booking) {
        const canCancel = this.canCancelBooking(booking);
        const isUpcoming = this.isUpcomingBooking(booking);

        return `
            <div class="booking-card">
                <div class="booking-header">
                    <div>
                        <div class="booking-id">${booking.bookingId}</div>
                        <div class="detail-value">${this.getEventTypeLabel(booking.eventType)}</div>
                    </div>
                    <div class="booking-status status-${booking.bookingStatus}">
                        ${booking.bookingStatus.toUpperCase()}
                    </div>
                </div>

                <div class="booking-details">
                    <div class="detail-item">
                        <span class="detail-label">Date & Time</span>
                        <span class="detail-value">${this.formatDate(booking.bookingDate)} at ${booking.startTime}</span>
                    </div>
                    <div class="detail-item">
                        <span class="detail-label">Duration</span>
                        <span class="detail-value">${booking.duration} hours</span>
                    </div>
                    <div class="detail-item">
                        <span class="detail-label">Guests</span>
                        <span class="detail-value">${booking.numberOfGuests} people</span>
                    </div>
                    <div class="detail-item">
                        <span class="detail-label">Amount</span>
                        <span class="detail-value">₹${booking.totalAmount.toLocaleString()}</span>
                    </div>
                </div>

                ${booking.specialRequests ? `
                <div class="detail-item">
                    <span class="detail-label">Special Requests</span>
                    <span class="detail-value">${booking.specialRequests}</span>
                </div>
                ` : ''}

                <div class="booking-actions">
                    <button class="btn btn-primary btn-sm" onclick="viewBookingDetails('${booking.bookingId}')">
                        View Details
                    </button>
                    ${isUpcoming && canCancel ? `
                    <button class="btn btn-danger btn-sm" onclick="showCancelModal('${booking.bookingId}')">
                        Cancel Booking
                    </button>
                    ` : ''}
                    ${booking.paymentStatus === 'paid' ? `
                    <button class="btn btn-secondary btn-sm" onclick="downloadReceipt('${booking.bookingId}')">
                        Download Receipt
                    </button>
                    ` : ''}
                </div>
            </div>
        `;
    }

    updatePagination() {
        const pagination = document.getElementById('pagination');
        const totalPages = Math.ceil(this.filteredBookings.length / this.itemsPerPage);

        if (totalPages <= 1) {
            pagination.innerHTML = '';
            return;
        }

        pagination.innerHTML = `
            <button class="btn-prev" onclick="bookingsManager.prevPage()" ${this.currentPage === 1 ? 'disabled' : ''}>
                ← Previous
            </button>
            
            <span class="pagination-info">
                Page ${this.currentPage} of ${totalPages}
            </span>
            
            <button class="btn-next" onclick="bookingsManager.nextPage()" ${this.currentPage === totalPages ? 'disabled' : ''}>
                Next →
            </button>
        `;
    }

    applyFilters() {
        this.filteredBookings = this.bookings.filter(booking => {
            // Status filter
            if (this.filters.status !== 'all' && booking.bookingStatus !== this.filters.status) {
                return false;
            }

            // Date filter
            if (this.filters.date !== 'all') {
                const bookingDate = new Date(booking.bookingDate);
                const today = new Date();
                today.setHours(0, 0, 0, 0);

                switch (this.filters.date) {
                    case 'upcoming':
                        if (bookingDate < today) return false;
                        break;
                    case 'past':
                        if (bookingDate >= today) return false;
                        break;
                    case 'today':
                        if (bookingDate.toDateString() !== today.toDateString()) return false;
                        break;
                }
            }

            // Search filter
            if (this.filters.search) {
                const searchTerm = this.filters.search.toLowerCase();
                const matchesId = booking.bookingId.toLowerCase().includes(searchTerm);
                const matchesEvent = this.getEventTypeLabel(booking.eventType).toLowerCase().includes(searchTerm);
                
                if (!matchesId && !matchesEvent) return false;
            }

            return true;
        });

        this.currentPage = 1;
        this.displayBookings();
    }

    setupEventListeners() {
        // Filter event listeners
        document.getElementById('statusFilter').addEventListener('change', (e) => {
            this.filters.status = e.target.value;
            this.applyFilters();
        });

        document.getElementById('dateFilter').addEventListener('change', (e) => {
            this.filters.date = e.target.value;
            this.applyFilters();
        });

        document.getElementById('searchBookings').addEventListener('input', (e) => {
            this.filters.search = e.target.value;
            this.applyFilters();
        });

        // Navigation
        this.setupNavigation();

        // Modal close on outside click
        document.addEventListener('click', (e) => {
            if (e.target.classList.contains('modal')) {
                this.closeModals();
            }
        });
    }

    setupNavigation() {
        document.getElementById('userMenuBtn')?.addEventListener('click', (e) => {
            e.stopPropagation();
            document.getElementById('userDropdown').classList.toggle('show');
        });

        document.addEventListener('click', () => {
            document.getElementById('userDropdown').classList.remove('show');
        });

        document.getElementById('logoutBtn')?.addEventListener('click', (e) => {
            e.preventDefault();
            this.logout();
        });
    }

    async viewBookingDetails(bookingId) {
        try {
            const response = await fetch(`/api/bookings/${bookingId}`, {
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.displayBookingModal(result.booking);
            } else {
                this.showError('Failed to load booking details');
            }
        } catch (error) {
            console.error('Error loading booking details:', error);
            this.showError('Network error loading booking details');
        }
    }

    displayBookingModal(booking) {
        const modal = document.getElementById('bookingModal');
        const content = document.getElementById('bookingDetailsContent');
        const actionBtn = document.getElementById('actionButton');

        const services = Object.keys(booking.additionalServices || {})
            .filter(service => booking.additionalServices[service])
            .map(service => this.getServiceLabel(service));

        content.innerHTML = `
            <div class="booking-detail-modal">
                <div class="detail-section">
                    <h4>Booking Information</h4>
                    <div class="booking-details">
                        <div class="detail-item">
                            <span class="detail-label">Booking ID</span>
                            <span class="detail-value">${booking.bookingId}</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Status</span>
                            <span class="booking-status status-${booking.bookingStatus}">
                                ${booking.bookingStatus.toUpperCase()}
                            </span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Payment Status</span>
                            <span class="detail-value">${booking.paymentStatus || 'Pending'}</span>
                        </div>
                    </div>
                </div>

                <div class="detail-section">
                    <h4>Event Details</h4>
                    <div class="booking-details">
                        <div class="detail-item">
                            <span class="detail-label">Event Type</span>
                            <span class="detail-value">${this.getEventTypeLabel(booking.eventType)}</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Date & Time</span>
                            <span class="detail-value">${this.formatDate(booking.bookingDate)} at ${booking.startTime}</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Duration</span>
                            <span class="detail-value">${booking.duration} hours</span>
                        </div>
                        <div class="detail-item">
                            <span class="detail-label">Number of Guests</span>
                            <span class="detail-value">${booking.numberOfGuests}</span>
                        </div>
                    </div>
                </div>

                ${services.length > 0 ? `
                <div class="detail-section">
                    <h4>Additional Services</h4>
                    <div class="service-tags">
                        ${services.map(service => `<span class="service-tag">${service}</span>`).join('')}
                    </div>
                </div>
                ` : ''}

                ${booking.specialRequests ? `
                <div class="detail-section">
                    <h4>Special Requests</h4>
                    <p>${booking.specialRequests}</p>
                </div>
                ` : ''}

                <div class="detail-section">
                    <h4>Payment Information</h4>
                    <div class="booking-details">
                        <div class="detail-item">
                            <span class="detail-label">Total Amount</span>
                            <span class="detail-value">₹${booking.totalAmount.toLocaleString()}</span>
                        </div>
                        ${booking.paymentMethod ? `
                        <div class="detail-item">
                            <span class="detail-label">Payment Method</span>
                            <span class="detail-value">${booking.paymentMethod}</span>
                        </div>
                        ` : ''}
                        ${booking.transactionId ? `
                        <div class="detail-item">
                            <span class="detail-label">Transaction ID</span>
                            <span class="detail-value">${booking.transactionId}</span>
                        </div>
                        ` : ''}
                        ${booking.paidAt ? `
                        <div class="detail-item">
                            <span class="detail-label">Paid At</span>
                            <span class="detail-value">${this.formatDateTime(booking.paidAt)}</span>
                        </div>
                        ` : ''}
                    </div>
                </div>
            </div>
        `;

        // Show action button if booking can be cancelled
        if (this.canCancelBooking(booking) && this.isUpcomingBooking(booking)) {
            actionBtn.style.display = 'block';
            actionBtn.textContent = 'Cancel Booking';
            actionBtn.onclick = () => {
                closeBookingModal();
                showCancelModal(booking.bookingId);
            };
        } else {
            actionBtn.style.display = 'none';
        }

        modal.classList.remove('hidden');
    }

    async cancelBooking(bookingId) {
        try {
            const response = await fetch(`/api/bookings/${bookingId}/cancel`, {
                method: 'PUT',
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                if (result.success) {
                    this.showSuccess('Booking cancelled successfully');
                    this.loadBookings(); // Reload bookings
                    closeCancelModal();
                } else {
                    this.showError(result.message);
                }
            } else {
                this.showError('Failed to cancel booking');
            }
        } catch (error) {
            console.error('Error cancelling booking:', error);
            this.showError('Network error cancelling booking');
        }
    }

    canCancelBooking(booking) {
        if (booking.bookingStatus === 'cancelled') return false;
        
        const bookingDateTime = new Date(`${booking.bookingDate}T${booking.startTime}`);
        const now = new Date();
        const hoursDifference = (bookingDateTime - now) / (1000 * 60 * 60);
        
        return hoursDifference >= 24; // Can cancel if more than 24 hours in advance
    }

    isUpcomingBooking(booking) {
        const bookingDate = new Date(booking.bookingDate);
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        
        return bookingDate >= today;
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

    closeModals() {
        document.getElementById('bookingModal').classList.add('hidden');
        document.getElementById('cancelModal').classList.add('hidden');
    }

    getEventTypeLabel(type) {
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

    getServiceLabel(service) {
        const services = {
            'decorations': 'Special Decorations',
            'beverages': 'Beverages Package',
            'photoshoot': 'Professional Photoshoot',
            'fogEffects': 'Fog Effects',
            'redCarpet': 'Red Carpet',
            'cake': 'Custom Cake'
        };
        return services[service] || service;
    }

    formatDate(dateString) {
        return new Date(dateString).toLocaleDateString('en-IN', {
            weekday: 'long',
            year: 'numeric',
            month: 'long',
            day: 'numeric'
        });
    }

    formatDateTime(dateString) {
        return new Date(dateString).toLocaleString('en-IN');
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
        window.location.href = 'login';
    }
}

// Global functions for HTML onclick handlers
function viewBookingDetails(bookingId) {
    window.bookingsManager.viewBookingDetails(bookingId);
}

function showCancelModal(bookingId) {
    const modal = document.getElementById('cancelModal');
    const details = document.getElementById('cancelDetails');
    const confirmBtn = document.getElementById('confirmCancelBtn');

    // Find booking details
    const booking = window.bookingsManager.bookings.find(b => b.bookingId === bookingId);
    if (booking) {
        details.innerHTML = `
            <div class="booking-details">
                <div class="detail-item">
                    <span class="detail-label">Booking ID:</span>
                    <span class="detail-value">${booking.bookingId}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">Event:</span>
                    <span class="detail-value">${window.bookingsManager.getEventTypeLabel(booking.eventType)}</span>
                </div>
                <div class="detail-item">
                    <span class="detail-label">Date:</span>
                    <span class="detail-value">${window.bookingsManager.formatDate(booking.bookingDate)}</span>
                </div>
            </div>
        `;
    }

    confirmBtn.onclick = () => window.bookingsManager.cancelBooking(bookingId);
    modal.classList.remove('hidden');
}

function closeBookingModal() {
    document.getElementById('bookingModal').classList.add('hidden');
}

function closeCancelModal() {
    document.getElementById('cancelModal').classList.add('hidden');
}

function searchBookings() {
    const searchTerm = document.getElementById('searchBookings').value;
    window.bookingsManager.filters.search = searchTerm;
    window.bookingsManager.applyFilters();
}

function downloadReceipt(bookingId) {
    // This would generate and download a PDF receipt
    alert(`Receipt for booking ${bookingId} would be downloaded`);
    // Implementation for PDF generation would go here
}

// Initialize bookings manager when page loads
document.addEventListener('DOMContentLoaded', () => {
    window.bookingsManager = new BookingsManager();
});