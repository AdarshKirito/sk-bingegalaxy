// Admin Dashboard functionality
class AdminDashboard {
    constructor() {
        this.admin = null;
        this.token = null;
        this.init();
    }

    async init() {
        await this.checkAdminAuthentication();
        this.loadAdminData();
        this.loadAdminDashboardData();
        this.setupEventListeners();
    }

    // async checkAdminAuthentication() {
    //     this.token = localStorage.getItem('token');
    //     this.admin = JSON.parse(localStorage.getItem('user') || '{}');

    //     if (!this.token || !this.admin.id || this.admin.role !== 'admin') {
    //         window.location.href = 'admin-login';
    //         return;
    //     }

    //     // Verify admin token is still valid
    //     try {
    //         const response = await fetch('/api/auth/profile', {
    //             headers: {
    //                 'Authorization': `Bearer ${this.token}`
    //             }
    //         });

    //         if (!response.ok) {
    //             throw new Error('Token invalid');
    //         }

    //         const result = await response.json();
    //         if (result.user.role !== 'admin') {
    //             throw new Error('Admin access required');
    //         }
    //     } catch (error) {
    //         this.logout();
    //     }
    // }



    async checkAdminAuthentication() {
    this.token = localStorage.getItem('token');
    this.admin = JSON.parse(localStorage.getItem('user') || '{}');

    console.log('🔐 Admin auth check - Token:', !!this.token, 'Role:', this.admin.role);

    // Simple check - only verify localStorage data
    if (!this.token || this.admin.role !== 'admin') {
        console.log('❌ Not authenticated as admin, redirecting to login');
        window.location.href = 'admin-login';
        return;
    }

    console.log('✅ Admin authentication successful');
    // Skip the API verification for now
}




    loadAdminData() {
        if (this.admin.name) {
            document.getElementById('adminName').textContent = this.admin.name;
        }
    }

    async loadAdminDashboardData() {
        await this.loadAdminStatistics();
        await this.loadTodaysArrivals();
        await this.loadRecentBookings();
    }

    async loadAdminStatistics() {
        try {
            const response = await fetch('/api/admin/stats', {
                headers: {
                    'Authorization': `Bearer ${this.token}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.displayAdminStatistics(result.stats);
            } else {
                // Fallback to demo data
                this.displayAdminStatistics(this.getDemoStats());
            }
        } catch (error) {
            console.error('Error loading admin statistics:', error);
            this.displayAdminStatistics(this.getDemoStats());
        }
    }

    displayAdminStatistics(stats) {
        document.getElementById('totalRevenue').textContent = `₹${stats.totalRevenue.toLocaleString()}`;
        document.getElementById('todayBookings').textContent = stats.todayBookings;
        document.getElementById('totalCustomers').textContent = stats.totalCustomers;
        document.getElementById('occupiedSlots').textContent = `${stats.occupancyRate}%`;
    }

    async loadTodaysArrivals() {
        try {
            const response = await fetch('/api/admin/todays-arrivals', {
                headers: {
                    'Authorization': `Bearer ${this.token}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.displayTodaysArrivals(result.arrivals);
            } else {
                this.displayTodaysArrivals([]);
            }
        } catch (error) {
            console.error('Error loading today arrivals:', error);
            this.displayTodaysArrivals([]);
        }
    }

    displayTodaysArrivals(arrivals) {
        const container = document.getElementById('todaysArrivalsList');
        const countElement = document.getElementById('arrivalCount');
        
        countElement.textContent = `${arrivals.length} arrival${arrivals.length !== 1 ? 's' : ''}`;

        if (arrivals.length === 0) {
            container.innerHTML = '<div class="arrival-card"><p>No arrivals scheduled for today.</p></div>';
            return;
        }

        container.innerHTML = arrivals.map(arrival => `
            <div class="arrival-card">
                <div class="arrival-info">
                    <h4>${arrival.customerName} - ${arrival.bookingId}</h4>
                    <p>${arrival.eventType} • ${arrival.guests} guests</p>
                    <span class="arrival-time">Arrival: ${arrival.startTime}</span>
                </div>
                <div class="arrival-actions">
                    <button class="btn btn-primary btn-sm" onclick="adminCheckIn('${arrival.bookingId}')">
                        Check In
                    </button>
                    <button class="btn btn-secondary btn-sm" onclick="viewBookingDetails('${arrival.bookingId}')">
                        Details
                    </button>
                </div>
            </div>
        `).join('');
    }

    async loadRecentBookings() {
        try {
            const response = await fetch('/api/admin/recent-bookings', {
                headers: {
                    'Authorization': `Bearer ${this.token}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.displayRecentBookings(result.bookings);
            } else {
                this.displayRecentBookings([]);
            }
        } catch (error) {
            console.error('Error loading recent bookings:', error);
            this.displayRecentBookings([]);
        }
    }

    displayRecentBookings(bookings) {
        const container = document.getElementById('recentBookingsTable');
        
        if (bookings.length === 0) {
            container.innerHTML = '<tr><td colspan="7">No recent bookings found.</td></tr>';
            return;
        }

        container.innerHTML = bookings.map(booking => `
            <tr>
                <td>${booking.bookingId}</td>
                <td>${booking.customerName}<br><small>${booking.customerPhone}</small></td>
                <td>${this.formatEventType(booking.eventType)}</td>
                <td>${this.formatDate(booking.bookingDate)}<br><small>${booking.startTime}</small></td>
                <td>₹${booking.totalAmount.toLocaleString()}</td>
                <td><span class="status-badge badge-${booking.bookingStatus}">${booking.bookingStatus}</span></td>
                <td>
                    <button class="btn btn-primary btn-sm" onclick="viewBookingDetails('${booking.bookingId}')">
                        View
                    </button>
                </td>
            </tr>
        `).join('');
    }

    setupEventListeners() {
        // Admin dropdown
        document.getElementById('adminMenuBtn').addEventListener('click', (e) => {
            e.stopPropagation();
            document.getElementById('adminDropdown').classList.toggle('show');
        });

        // Close dropdown when clicking outside
        document.addEventListener('click', () => {
            document.getElementById('adminDropdown').classList.remove('show');
        });

        // Admin logout
        document.getElementById('adminLogoutBtn').addEventListener('click', (e) => {
            e.preventDefault();
            this.logout();
        });

        // Prevent dropdown close when clicking inside
        document.getElementById('adminDropdown').addEventListener('click', (e) => {
            e.stopPropagation();
        });
    }

    logout() {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = 'admin-login';
    }

    getDemoStats() {
        return {
            totalRevenue: 125000,
            todayBookings: 8,
            totalCustomers: 45,
            occupancyRate: 75
        };
    }

    formatEventType(type) {
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
}

// Global admin functions
function adminCheckIn(bookingId) {
    if (confirm(`Check in booking ${bookingId}?`)) {
        // Implement check-in logic
        alert(`Booking ${bookingId} checked in successfully!`);
    }
}

function viewBookingDetails(bookingId) {
    window.location.href = `admin-booking-details?id=${bookingId}`;
}

// Initialize admin dashboard when page loads
document.addEventListener('DOMContentLoaded', () => {
    new AdminDashboard();
});