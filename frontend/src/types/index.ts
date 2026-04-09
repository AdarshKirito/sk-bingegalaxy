/* Shared TypeScript types for the frontend */

export interface User {
  id: number;
  firstName: string;
  lastName?: string;
  email: string;
  phone?: string;
  preferredExperience?: string;
  vibePreference?: string;
  reminderLeadDays?: number;
  birthdayMonth?: string;
  birthdayDay?: number;
  anniversaryMonth?: string;
  anniversaryDay?: number;
  notificationChannel?: 'EMAIL' | 'CALLBACK';
  receivesOffers?: boolean;
  weekendAlerts?: boolean;
  conciergeSupport?: boolean;
  role: 'USER' | 'ADMIN' | 'SUPER_ADMIN';
  active?: boolean;
}

export interface SupportContact {
  email: string;
  phoneDisplay: string;
  phoneRaw: string;
  whatsappRaw: string;
  hours: string;
}

export interface AuthResponse {
  token: string;
  refreshToken: string;
  user: User;
}

export interface Binge {
  id: number;
  name: string;
  location?: string;
  description?: string;
  active?: boolean;
}

export interface EventType {
  id: number;
  name: string;
  description?: string;
  basePrice: number;
  hourlyRate: number;
  pricePerGuest?: number;
  imageUrls?: string[];
  active?: boolean;
}

export interface AddOn {
  id: number;
  name: string;
  description?: string;
  price: number;
  imageUrls?: string[];
  active?: boolean;
}

export interface BookingAddOn {
  addOnId: number;
  quantity: number;
  price?: number;
  name?: string;
}

export interface Booking {
  id?: number;
  bookingRef: string;
  eventTypeId: number;
  eventType?: EventType;
  bookingDate: string;
  startTime: string;
  durationMinutes: number;
  durationHours?: number;
  numberOfGuests: number;
  addOns: BookingAddOn[];
  specialNotes?: string;
  adminNotes?: string;
  customerId?: number;
  customerName?: string;
  customerEmail?: string;
  customerPhone?: string;
  paymentMethod?: 'CASH' | 'UPI' | 'CARD' | 'BANK_TRANSFER' | 'WALLET';
  paymentStatus?: 'PENDING' | 'SUCCESS' | 'FAILED' | 'PARTIALLY_REFUNDED';
  status?: 'PENDING' | 'CONFIRMED' | 'CHECKED_IN' | 'COMPLETED' | 'CANCELLED';
  totalAmount?: number;
}

export interface BookingFormData {
  eventTypeId: number | '';
  bookingDate: string;
  startTime: number | '';
  durationMinutes: number;
  numberOfGuests: number;
  addOns: BookingAddOn[];
  specialNotes: string;
  adminNotes: string;
  paymentMethod: string;
}

export interface AvailableDate {
  date: string;
  fullyBlocked: boolean;
}

export interface TimeSlot {
  startMinute?: number;
  startHour?: number;
  available: boolean;
}

export interface BookedSlot {
  bookingRef?: string;
  startMinute?: number;
  startHour?: number;
  durationMinutes?: number;
  durationHours?: number;
}

export interface ResolvedPricing {
  pricingSource?: 'DEFAULT' | 'RATE_CODE' | 'CUSTOM';
  rateCodeName?: string;
  eventPricings?: Array<{
    eventTypeId: number;
    basePrice: number;
    hourlyRate: number;
    pricePerGuest: number;
    source?: string;
  }>;
  addonPricings?: Array<{
    addOnId: number;
    price: number;
    source?: string;
  }>;
}

export interface RateCode {
  id: number;
  name: string;
  description?: string;
  active?: boolean;
}

export interface ApiResponse<T> {
  data: T;
  message?: string;
  success?: boolean;
}
