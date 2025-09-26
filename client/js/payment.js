// Payment System functionality
class PaymentSystem {
    constructor() {
        this.booking = null;
        this.paymentOrder = null;
        this.selectedMethod = 'upi';
        this.init();
    }

    async init() {
        await this.checkAuthentication();
        await this.loadBookingDetails();
        this.setupEventListeners();
        this.initializePayment();
    }

    async checkAuthentication() {
        const token = localStorage.getItem('token');
        const user = JSON.parse(localStorage.getItem('user') || '{}');

        if (!token || !user.id) {
            window.location.href = 'login.html';
            return;
        }

        document.getElementById('userName').textContent = `Welcome, ${user.name}`;
    }

    async loadBookingDetails() {
        const urlParams = new URLSearchParams(window.location.search);
        const bookingId = urlParams.get('bookingId');

        if (!bookingId) {
            window.location.href = 'dashboard.html';
            return;
        }

        try {
            const response = await fetch(`/api/bookings/${bookingId}`, {
                headers: {
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                }
            });

            if (response.ok) {
                const result = await response.json();
                this.booking = result.booking;
                this.displayBookingSummary();
            } else {
                this.showError('Booking not found');
            }
        } catch (error) {
            console.error('Error loading booking:', error);
            this.showError('Network error loading booking details');
        }
    }

    displayBookingSummary() {
        if (!this.booking) return;

        const summary = document.getElementById('bookingSummary');
        const breakdown = document.getElementById('amountBreakdown');

        // Calculate service amount
        const serviceAmount = Object.keys(this.booking.additionalServices || {})
            .filter(service => this.booking.additionalServices[service])
            .reduce((total, service) => {
                const prices = {
                    decorations: 1500, beverages: 800, photoshoot: 1200,
                    fogEffects: 1000, redCarpet: 500, cake: 600
                };
                return total + (prices[service] || 0);
            }, 0);

        const baseAmount = 2000 * this.booking.duration;

        summary.innerHTML = `
            <div class="booking-detail">
                <span>Booking ID:</span>
                <strong>${this.booking.bookingId}</strong>
            </div>
            <div class="booking-detail">
                <span>Event Type:</span>
                <span>${this.getEventTypeLabel(this.booking.eventType)}</span>
            </div>
            <div class="booking-detail">
                <span>Date & Time:</span>
                <span>${this.formatDate(this.booking.bookingDate)} at ${this.booking.startTime}</span>
            </div>
            <div class="booking-detail">
                <span>Duration:</span>
                <span>${this.booking.duration} hours</span>
            </div>
            <div class="booking-detail">
                <span>Guests:</span>
                <span>${this.booking.numberOfGuests} people</span>
            </div>
        `;

        breakdown.innerHTML = `
            <div class="breakdown-item">
                <span>Base Amount (${this.booking.duration} hrs)</span>
                <span>₹${baseAmount.toLocaleString()}</span>
            </div>
            <div class="breakdown-item">
                <span>Additional Services</span>
                <span>₹${serviceAmount.toLocaleString()}</span>
            </div>
            ${this.booking.numberOfGuests > 10 ? `
            <div class="breakdown-item">
                <span>Guest Surcharge</span>
                <span>₹${((this.booking.numberOfGuests - 10) * 100).toLocaleString()}</span>
            </div>
            ` : ''}
            <div class="breakdown-item total">
                <span><strong>Total Amount</strong></span>
                <span><strong>₹${this.booking.totalAmount.toLocaleString()}</strong></span>
            </div>
        `;

        // Update payment amounts
        document.querySelectorAll('#paymentAmount, #cardAmount').forEach(el => {
            el.textContent = this.booking.totalAmount.toLocaleString();
        });
    }

    setupEventListeners() {
        // Payment method tabs
        document.querySelectorAll('.tab-button').forEach(button => {
            button.addEventListener('click', (e) => {
                this.switchPaymentMethod(e.target.dataset.tab);
            });
        });

        // UPI options
        document.querySelectorAll('.upi-option').forEach(option => {
            option.addEventListener('click', (e) => {
                this.switchUpiMethod(e.currentTarget.dataset.type);
            });
        });

        // Card number formatting
        document.getElementById('cardNumber')?.addEventListener('input', (e) => {
            e.target.value = e.target.value.replace(/\s/g, '').replace(/(\d{4})/g, '$1 ').trim();
        });

        // Expiry date formatting
        document.getElementById('expiryDate')?.addEventListener('input', (e) => {
            e.target.value = e.target.value.replace(/\D/g, '').replace(/(\d{2})(\d)/, '$1/$2');
        });

        // Navigation
        this.setupNavigation();
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

    switchPaymentMethod(method) {
        this.selectedMethod = method;
        
        // Update tabs
        document.querySelectorAll('.tab-button').forEach(btn => {
            btn.classList.remove('active');
        });
        document.querySelector(`[data-tab="${method}"]`).classList.add('active');
        
        // Update content
        document.querySelectorAll('.payment-tab-content').forEach(content => {
            content.classList.remove('active');
        });
        document.getElementById(`${method}-tab`).classList.add('active');
    }

    switchUpiMethod(type) {
        document.querySelectorAll('.upi-option').forEach(opt => {
            opt.classList.remove('selected');
        });
        document.querySelector(`[data-type="${type}"]`).classList.add('selected');
        
        document.querySelectorAll('.upi-content').forEach(content => {
            content.classList.add('hidden');
        });
        document.getElementById(`upi${type.charAt(0).toUpperCase() + type.slice(1)}`).classList.remove('hidden');
    }

    async initializePayment() {
        if (!this.booking) return;

        try {
            const response = await fetch('/api/payments/create-order', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                body: JSON.stringify({
                    bookingId: this.booking.bookingId,
                    amount: this.booking.totalAmount
                })
            });

            if (response.ok) {
                const result = await response.json();
                this.paymentOrder = result.order;
                this.generateQRCode();
            }
        } catch (error) {
            console.error('Payment initialization error:', error);
        }
    }

    generateQRCode() {
        // Simple QR code simulation - in real app, use a QR library
        const qrContainer = document.getElementById('qrCode');
        if (qrContainer && this.paymentOrder) {
            qrContainer.innerHTML = `
                <div style="text-align: center;">
                    <div style="font-size: 3rem; margin-bottom: 1rem;">📱</div>
                    <strong>UPI Payment Ready</strong><br>
                    <small>Amount: ₹${this.booking.totalAmount.toLocaleString()}</small>
                </div>
            `;
        }
    }

    async processPayment() {
        if (!this.booking || !this.paymentOrder) {
            this.showError('Payment system not ready');
            return;
        }

        this.showPaymentModal('Initializing payment...');

        try {
            // Simulate payment processing
            await this.simulatePayment();

            // Verify payment
            const verification = await this.verifyPayment();

            if (verification.success) {
                this.showPaymentModal('Payment successful! Redirecting...');
                setTimeout(() => {
                    window.location.href = `confirmation.html?bookingId=${this.booking.bookingId}`;
                }, 2000);
            } else {
                this.hidePaymentModal();
                this.showError('Payment failed. Please try again.');
            }
        } catch (error) {
            this.hidePaymentModal();
            this.showError('Payment processing error');
        }
    }

    async simulatePayment() {
        return new Promise((resolve) => {
            setTimeout(() => {
                this.showPaymentModal('Processing transaction...');
                setTimeout(resolve, 2000);
            }, 1000);
        });
    }

    async verifyPayment() {
        try {
            const response = await fetch('/api/payments/verify', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                body: JSON.stringify({
                    paymentId: `mock_pay_${Date.now()}`,
                    orderId: this.paymentOrder.id,
                    signature: 'mock_signature',
                    bookingId: this.booking.bookingId
                })
            });

            return await response.json();
        } catch (error) {
            console.error('Payment verification error:', error);
            return { success: false };
        }
    }

    showPaymentModal(status = '') {
        const modal = document.getElementById('paymentModal');
        const statusEl = document.getElementById('processingStatus');
        
        modal.classList.remove('hidden');
        statusEl.textContent = status;
    }

    hidePaymentModal() {
        document.getElementById('paymentModal').classList.add('hidden');
    }

    showError(message) {
        alert(`Error: ${message}`);
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

    formatDate(dateString) {
        return new Date(dateString).toLocaleDateString('en-IN', {
            weekday: 'long',
            year: 'numeric',
            month: 'long',
            day: 'numeric'
        });
    }

    logout() {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = 'login.html';
    }
}

// Global functions for HTML onclick handlers
function processUpiPayment() {
    window.paymentSystem.processPayment();
}

function processCardPayment() {
    // Validate card details first
    const cardNumber = document.getElementById('cardNumber').value;
    const expiry = document.getElementById('expiryDate').value;
    const cvv = document.getElementById('cvv').value;
    const holder = document.getElementById('cardHolder').value;

    if (!cardNumber || !expiry || !cvv || !holder) {
        alert('Please fill all card details');
        return;
    }

    window.paymentSystem.processPayment();
}

function processNetBanking() {
    const selectedBank = document.querySelector('.bank-option:hover');
    if (!selectedBank) {
        alert('Please select a bank');
        return;
    }
    window.paymentSystem.processPayment();
}

function processWalletPayment() {
    const selectedWallet = document.querySelector('.wallet-option:hover');
    if (!selectedWallet) {
        alert('Please select a wallet');
        return;
    }
    window.paymentSystem.processPayment();
}

function initiateUpiPayment() {
    const upiId = document.getElementById('upiIdInput').value;
    if (!upiId || !upiId.includes('@')) {
        alert('Please enter a valid UPI ID');
        return;
    }
    window.paymentSystem.processPayment();
}

// Initialize payment system when page loads
document.addEventListener('DOMContentLoaded', () => {
    window.paymentSystem = new PaymentSystem();
});