import '@testing-library/jest-dom';

// Initialize i18n so useTranslation works in test env without warnings
import '../services/i18n';

// Global test helpers

// Mock dialog methods not available in jsdom
HTMLDialogElement.prototype.showModal = HTMLDialogElement.prototype.showModal || function () {
  this.setAttribute('open', '');
};
HTMLDialogElement.prototype.close = HTMLDialogElement.prototype.close || function () {
  this.removeAttribute('open');
};

// Mock window.matchMedia
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(query => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

// Mock IntersectionObserver
global.IntersectionObserver = class IntersectionObserver {
  constructor() {}
  observe() {}
  unobserve() {}
  disconnect() {}
};

// Mock URL.createObjectURL / revokeObjectURL
if (!window.URL.createObjectURL) {
  window.URL.createObjectURL = vi.fn(() => 'blob:mock-url');
}
if (!window.URL.revokeObjectURL) {
  window.URL.revokeObjectURL = vi.fn();
}
