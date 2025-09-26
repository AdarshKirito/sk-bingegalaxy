// Dashboard functionality
class Dashboard {
    constructor() {
        this.user = null;
        this.token = null;
        this.init();
    }

    async init() {
        await this.checkAuthentication();
        this.loadUserData();
        this.loadDashboardData();
        this.setupEventListeners();
    }

    async checkAuthentication() {
        this.token = localStorage.getItem('token');
        this.user = JSON.parse(localStorage.getItem('user') || '{}');

        if (!this.token || !this.user.id) {
            window.location.href = 'login';
            return;
        }

        // Verify token is still valid
        try {
            const response = await fetch('/api/auth/profile', {
                headers: {
                    'Authorization': `Bearer ${this.token}`
                }
            });

            if (!response.ok) {
                throw new Error('Token invalid');
            }
        } catch (error) {
            this.logout();
        }
    }

    loadUserData() {
        if (this.user.name) {
            document.getElementById('userName').textContent = `Welcome, ${this.user.name}`;
        }
    }

    async loadDashboardData() {
        await this.loadStatistics();
        await this.loadUpcomingBookings();
        await this.loadRecentActivity();
    }

    async loadStatistics() {
        try {
            // In a real app, you'd fetch this from an API
            // For now, we'll simulate the data
            const stats = {
                totalBookings: 12,
                upcomingBookings: 3,
                amountSpent: 45600
            };

            document.getElementById('totalBookings').textContent = stats.totalBookings;
            document.getElementById('upcomingBookings').textContent = stats.upcomingBookings;
            document.getElementById('amountSpent').textContent = `₹${stats.amountSpent.toLocaleString()}`;
        } catch (error) {
            console.error('Error loading statistics:', error);
        }
    }

    async loadUpcomingBookings() {
        try {
            const response = await fetch('/api/bookings/upcoming', {
                headers: {
                    'Authorization': `Bearer ${this.token}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.displayUpcomingBookings(result.bookings);
            } else {
                this.displayUpcomingBookings([]);
            }
        } catch (error) {
            console.error('Error loading upcoming bookings:', error);
            this.displayUpcomingBookings([]);
        }
    }

    displayUpcomingBookings(bookings) {
        const container = document.getElementById('upcomingBookingsList');
        
        if (!bookings || bookings.length === 0) {
            container.innerHTML = `
                <div class="booking-card">
                    <p>No upcoming bookings found.</p>
                    <a href="booking" class="btn btn-primary">Book Your First Event</a>
                </div>
            `;
            return;
        }

        container.innerHTML = bookings.map(booking => `
            <div class="booking-card">
                <div class="booking-header">
                    <span class="booking-id">${booking.bookingId}</span>
                    <span class="booking-status status-${booking.bookingStatus}">
                        ${booking.bookingStatus.toUpperCase()}
                    </span>
                </div>
                <div class="booking-details">
                    <div class="detail-item">
                        <span class="detail-label">Event Type</span>
                        <span class="detail-value">${this.formatEventType(booking.eventType)}</span>
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
                        <span class="detail-label">Amount</span>
                        <span class="detail-value">₹${booking.totalAmount.toLocaleString()}</span>
                    </div>
                </div>
            </div>
        `).join('');
    }

    async loadRecentActivity() {
        try {
            const response = await fetch('/api/bookings/recent', {
                headers: {
                    'Authorization': `Bearer ${this.token}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.displayRecentActivity(result.activity);
            } else {
                this.displayRecentActivity([]);
            }
        } catch (error) {
            console.error('Error loading recent activity:', error);
            this.displayRecentActivity([]);
        }
    }

    displayRecentActivity(activities) {
        const container = document.getElementById('recentActivityList');
        
        if (!activities || activities.length === 0) {
            container.innerHTML = '<div class="activity-item"><p>No recent activity.</p></div>';
            return;
        }

        container.innerHTML = activities.map(activity => `
            <div class="activity-item">
                <div class="activity-content">
                    <strong>${activity.action}</strong> - ${activity.description}
                    <span class="activity-time">${this.formatTime(activity.timestamp)}</span>
                </div>
            </div>
        `).join('');
    }

    setupEventListeners() {
        // User dropdown
        document.getElementById('userMenuBtn').addEventListener('click', (e) => {
            e.stopPropagation();
            document.getElementById('userDropdown').classList.toggle('show');
        });

        // Close dropdown when clicking outside
        document.addEventListener('click', () => {
            document.getElementById('userDropdown').classList.remove('show');
        });

        // Logout
        document.getElementById('logoutBtn').addEventListener('click', (e) => {
            e.preventDefault();
            this.logout();
        });

        // Prevent dropdown close when clicking inside
        document.getElementById('userDropdown').addEventListener('click', (e) => {
            e.stopPropagation();
        });
    }

    logout() {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = 'login';
    }

    formatEventType(type) {
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

    formatDate(dateString) {
        const date = new Date(dateString);
        return date.toLocaleDateString('en-IN', {
            weekday: 'long',
            year: 'numeric',
            month: 'long',
            day: 'numeric'
        });
    }

    formatTime(timestamp) {
        return new Date(timestamp).toLocaleTimeString('en-IN', {
            hour: '2-digit',
            minute: '2-digit'
        });
    }
}

// Utility function for checking availability
function checkAvailability() {
    window.location.href = 'booking?check-availability=true';
}

// Initialize dashboard when page loads
document.addEventListener('DOMContentLoaded', () => {
    new Dashboard();
});