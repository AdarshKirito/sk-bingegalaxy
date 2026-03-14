// Booking System functionality
class BookingSystem {
    constructor() {
        this.currentStep = 1;
        this.bookingData = {
            eventType: '',
            numberOfGuests: 10,
            specialRequests: '',
            bookingDate: '',
            duration: 3,
            startTime: '',
            additionalServices: {},
            totalAmount: 6000
        };
        this.availableSlots = [];
        this.init();
    }

    async init() {
        await this.checkAuthentication();
        this.setMinDate();
        this.setupEventListeners();
        this.updatePriceSummary();
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

    setMinDate() {
        const today = new Date().toISOString().split('T')[0];
        document.getElementById('bookingDate').min = today;
        
        // Set default date to tomorrow
        const tomorrow = new Date();
        tomorrow.setDate(tomorrow.getDate() + 1);
        document.getElementById('bookingDate').value = tomorrow.toISOString().split('T')[0];
    }

    setupEventListeners() {
        // Event details form
        document.getElementById('eventType').addEventListener('change', (e) => {
            this.bookingData.eventType = e.target.value;
        });

        document.getElementById('numberOfGuests').addEventListener('change', (e) => {
            this.bookingData.numberOfGuests = parseInt(e.target.value);
            this.updatePriceSummary();
        });

        document.getElementById('specialRequests').addEventListener('input', (e) => {
            this.bookingData.specialRequests = e.target.value;
            document.getElementById('charCount').textContent = e.target.value.length;
        });

        // Date and time
        document.getElementById('duration').addEventListener('change', (e) => {
            this.bookingData.duration = parseInt(e.target.value);
            this.updatePriceSummary();
            if (this.bookingData.bookingDate) {
                this.checkAvailability();
            }
        });

        document.getElementById('bookingDate').addEventListener('change', (e) => {
            this.bookingData.bookingDate = e.target.value;
            this.checkAvailability();
        });

        // Services
        document.querySelectorAll('.service-checkbox').forEach(checkbox => {
            checkbox.addEventListener('change', (e) => {
                const service = e.target.id;
                this.bookingData.additionalServices[service] = e.target.checked;
                this.updatePriceSummary();
            });
        });

        // Navigation
        this.setupNavigation();
    }

    setupNavigation() {
        // User dropdown (reusing from dashboard)
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

    async checkAvailability() {
        const date = this.bookingData.bookingDate;
        const duration = this.bookingData.duration;

        if (!date) {
            this.showMessage('Please select a date first', 'error');
            return;
        }

        this.showLoading('timeSlots', 'Checking availability...');

        try {
            const response = await fetch(`/api/bookings/availability?date=${date}&duration=${duration}`);
            const result = await response.json();

            if (result.success) {
                this.availableSlots = result.availableSlots;
                this.displayTimeSlots();
            } else {
                this.showMessage('Error checking availability', 'error');
            }
        } catch (error) {
            console.error('Availability check error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        }
    }

    displayTimeSlots() {
        const container = document.getElementById('timeSlots');
        
        if (this.availableSlots.length === 0) {
            container.innerHTML = '<div class="time-slot unavailable">No available slots for selected date</div>';
            return;
        }

        container.innerHTML = this.availableSlots.map(slot => `
            <div class="time-slot ${slot.available ? '' : 'unavailable'}" 
                 onclick="${slot.available ? `selectTimeSlot('${slot.startTime}')` : ''}">
                <div class="slot-time">${slot.startTime} - ${slot.endTime}</div>
                <div class="slot-status">${slot.available ? 'Available' : 'Unavailable'}</div>
            </div>
        `).join('');
    }

    async updatePriceSummary() {
        // Calculate price locally first for instant feedback
        const baseRate = 2000;
        let total = baseRate * this.bookingData.duration;
        
        // Add service charges
        Object.keys(this.bookingData.additionalServices).forEach(service => {
            if (this.bookingData.additionalServices[service]) {
                const prices = {
                    decorations: 1500,
                    beverages: 800,
                    photoshoot: 1200,
                    fogEffects: 1000,
                    redCarpet: 500,
                    cake: 600
                };
                total += prices[service] || 0;
            }
        });
        
        // Add guest surcharge
        if (this.bookingData.numberOfGuests > 10) {
            total += (this.bookingData.numberOfGuests - 10) * 100;
        }
        
        this.bookingData.totalAmount = total;
        
        // Update UI
        this.updatePriceDisplay(total);
        
        // Optional: Get precise calculation from server
        await this.calculateExactPrice();
    }

    updatePriceDisplay(total) {
        const baseAmount = 2000 * this.bookingData.duration;
        const serviceAmount = total - baseAmount;
        
        document.querySelector('.price-breakdown').innerHTML = `
            <div class="price-item">
                <span>Base Rate (${this.bookingData.duration} hours)</span>
                <span>₹${baseAmount.toLocaleString()}</span>
            </div>
            <div class="price-item">
                <span>Additional Services</span>
                <span>₹${serviceAmount.toLocaleString()}</span>
            </div>
            <div class="price-item total">
                <span><strong>Total Amount</strong></span>
                <span><strong>₹${total.toLocaleString()}</strong></span>
            </div>
        `;
    }

    async calculateExactPrice() {
        try {
            const response = await fetch('/api/bookings/calculate-price', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                body: JSON.stringify({
                    duration: this.bookingData.duration,
                    guests: this.bookingData.numberOfGuests,
                    services: this.bookingData.additionalServices
                })
            });

            if (response.ok) {
                const result = await response.json();
                if (result.success) {
                    this.bookingData.totalAmount = result.totalAmount;
                    this.updatePriceDisplay(result.totalAmount);
                }
            }
        } catch (error) {
            console.error('Price calculation error:', error);
        }
    }

    prepareReview() {
        // Event details
        document.getElementById('reviewEventDetails').innerHTML = `
            <div class="summary-detail">
                <span>Event Type:</span>
                <span>${this.getEventTypeLabel(this.bookingData.eventType)}</span>
            </div>
            <div class="summary-detail">
                <span>Number of Guests:</span>
                <span>${this.bookingData.numberOfGuests}</span>
            </div>
            ${this.bookingData.specialRequests ? `
            <div class="summary-detail">
                <span>Special Requests:</span>
                <span>${this.bookingData.specialRequests}</span>
            </div>
            ` : ''}
        `;

        // Date & Time
        document.getElementById('reviewDateTime').innerHTML = `
            <div class="summary-detail">
                <span>Date:</span>
                <span>${this.formatDate(this.bookingData.bookingDate)}</span>
            </div>
            <div class="summary-detail">
                <span>Time:</span>
                <span>${this.bookingData.startTime} (${this.bookingData.duration} hours)</span>
            </div>
        `;

        // Services
        const selectedServices = Object.keys(this.bookingData.additionalServices)
            .filter(service => this.bookingData.additionalServices[service])
            .map(service => this.getServiceLabel(service));

        document.getElementById('reviewServices').innerHTML = selectedServices.length > 0 ? 
            selectedServices.map(service => `<div class="summary-detail"><span>${service}</span></div>`).join('') :
            '<div class="summary-detail"><span>No additional services selected</span></div>';

        // Final amount
        document.getElementById('finalAmount').textContent = `₹${this.bookingData.totalAmount.toLocaleString()}`;
    }

    async confirmBooking() {
        // Validate all data
        if (!this.validateBookingData()) {
            this.showMessage('Please complete all required fields', 'error');
            return;
        }

        this.showLoading('confirmBookingBtn', 'Creating booking...');

        try {
            const response = await fetch('/api/bookings/create', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${localStorage.getItem('token')}`
                },
                body: JSON.stringify(this.bookingData)
            });

            const result = await response.json();

            if (result.success) {
                this.showMessage('Booking created successfully! Redirecting to payment...', 'success');
                setTimeout(() => {
                    window.location.href = `payment.html?bookingId=${result.booking.bookingId}`;
                }, 2000);
            } else {
                this.showMessage(result.message, 'error');
            }
        } catch (error) {
            console.error('Booking creation error:', error);
            this.showMessage('Network error. Please try again.', 'error');
        } finally {
            this.hideLoading('confirmBookingBtn', 'Confirm & Proceed to Payment');
        }
    }

    validateBookingData() {
        return this.bookingData.eventType && 
               this.bookingData.bookingDate && 
               this.bookingData.startTime;
    }

    getEventTypeLabel(type) {
        const types = {
            'birthday': 'Birthday Celebration',
            'anniversary': 'Anniversary',
            'surprise': 'Surprise Party',
            'proposal': 'Marriage Proposal',
            'screening': 'HD Movie Screening',
            'other': 'Other Event'
        };
        return types[type] || type;
    }

    getServiceLabel(service) {
        const services = {
            'decorations': 'Special Decorations',
            'beverages': 'Beverages Package',
            'photoshoot': 'Professional Photoshoot',
            'fogEffects': 'Fog & Special Effects',
            'redCarpet': 'Red Carpet Entry',
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

    showMessage(message, type) {
        // Create or show message element
        alert(`${type.toUpperCase()}: ${message}`); // Simple alert for now
    }

    showLoading(elementId, text) {
        const element = document.getElementById(elementId);
        element.disabled = true;
        element.innerHTML = text;
    }

    hideLoading(elementId, text) {
        const element = document.getElementById(elementId);
        element.disabled = false;
        element.innerHTML = text;
    }

    logout() {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        window.location.href = 'login';
    }
}

// Global functions for HTML onclick handlers
function nextStep(step) {
    const bookingSystem = window.bookingSystem;
    
    // Validate current step before proceeding
    if (step === 2 && !bookingSystem.bookingData.eventType) {
        alert('Please select an event type');
        return;
    }
    
    if (step === 3 && !bookingSystem.bookingData.startTime) {
        alert('Please select a time slot');
        return;
    }
    
    if (step === 4) {
        bookingSystem.prepareReview();
    }
    
    // Hide all steps
    document.querySelectorAll('.booking-step').forEach(step => {
        step.classList.remove('active');
    });
    
    // Show target step
    document.getElementById(`step${step}`).classList.add('active');
    
    // Update steps indicator
    document.querySelectorAll('.step').forEach(stepElem => {
        stepElem.classList.remove('active');
    });
    document.querySelector(`.step[data-step="${step}"]`).classList.add('active');
    
    bookingSystem.currentStep = step;
}

function prevStep(step) {
    nextStep(step);
}

function selectTimeSlot(startTime) {
    const bookingSystem = window.bookingSystem;
    bookingSystem.bookingData.startTime = startTime;
    
    // Update UI
    document.querySelectorAll('.time-slot').forEach(slot => {
        slot.classList.remove('selected');
    });
    
    event.target.closest('.time-slot').classList.add('selected');
    
    // Enable next button
    document.getElementById('nextStep2').disabled = false;
}

function checkAvailability() {
    window.bookingSystem.checkAvailability();
}

function confirmBooking() {
    window.bookingSystem.confirmBooking();
}

// Initialize booking system when page loads
document.addEventListener('DOMContentLoaded', () => {
    window.bookingSystem = new BookingSystem();
});