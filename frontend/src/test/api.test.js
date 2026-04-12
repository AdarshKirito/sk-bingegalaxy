import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Mock react-toastify before importing api
vi.mock('react-toastify', () => ({
  toast: { error: vi.fn(), success: vi.fn() },
}));

// Mock axios at module level
const mockInterceptors = {
  request: { use: vi.fn() },
  response: { use: vi.fn() },
};
const mockCreate = vi.fn(() => ({
  interceptors: mockInterceptors,
  defaults: { headers: { common: {} } },
}));
vi.mock('axios', () => ({
  default: { create: mockCreate, post: vi.fn() },
  __esModule: true,
}));

describe('api module configuration', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
  });

  it('creates axios instance with correct defaults', async () => {
    // Re-import to trigger module execution
    vi.resetModules();
    // Re-setup mocks after resetModules
    vi.doMock('react-toastify', () => ({
      toast: { error: vi.fn(), success: vi.fn() },
    }));
    vi.doMock('axios', () => ({
      default: { create: mockCreate, post: vi.fn() },
      __esModule: true,
    }));
    await import('../services/api.js');

    expect(mockCreate).toHaveBeenCalledWith(
      expect.objectContaining({
        baseURL: '/api/v1',
        withCredentials: true,
        timeout: 15000,
      })
    );
  });

  it('registers request and response interceptors', async () => {
    vi.resetModules();
    vi.doMock('react-toastify', () => ({
      toast: { error: vi.fn(), success: vi.fn() },
    }));
    const reqUse = vi.fn();
    const resUse = vi.fn();
    vi.doMock('axios', () => ({
      default: {
        create: vi.fn(() => ({
          interceptors: {
            request: { use: reqUse },
            response: { use: resUse },
          },
          defaults: { headers: { common: {} } },
        })),
        post: vi.fn(),
      },
      __esModule: true,
    }));
    await import('../services/api.js');

    expect(reqUse).toHaveBeenCalledTimes(1);
    expect(resUse).toHaveBeenCalledTimes(1);
  });
});

describe('request interceptor - X-Binge-Id', () => {
  let requestInterceptor;

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    vi.doMock('react-toastify', () => ({
      toast: { error: vi.fn(), success: vi.fn() },
    }));
    const reqUse = vi.fn();
    vi.doMock('axios', () => ({
      default: {
        create: vi.fn(() => ({
          interceptors: {
            request: { use: reqUse },
            response: { use: vi.fn() },
          },
          defaults: { headers: { common: {} } },
        })),
        post: vi.fn(),
      },
      __esModule: true,
    }));
    await import('../services/api.js');
    requestInterceptor = reqUse.mock.calls[0][0];
  });

  it('attaches X-Binge-Id header when selectedBinge is in localStorage', async () => {
    localStorage.setItem('selectedBinge', JSON.stringify({ id: 5 }));
    const config = { headers: {}, url: '/bookings' };
    const result = await requestInterceptor(config);
    expect(result.headers['X-Binge-Id']).toBe(5);
  });

  it('does not attach header when no selectedBinge', async () => {
    const config = { headers: {}, url: '/bookings' };
    const result = await requestInterceptor(config);
    expect(result.headers['X-Binge-Id']).toBeUndefined();
  });

  it('ignores malformed selectedBinge JSON', async () => {
    localStorage.setItem('selectedBinge', 'not-json');
    const config = { headers: {}, url: '/bookings' };
    const result = await requestInterceptor(config);
    expect(result.headers['X-Binge-Id']).toBeUndefined();
  });
});

describe('response interceptor - error handling', () => {
  let responseErrorHandler;
  let toastMock;

  beforeEach(async () => {
    vi.resetModules();
    localStorage.clear();
    toastMock = { error: vi.fn(), success: vi.fn() };
    vi.doMock('react-toastify', () => ({ toast: toastMock }));
    const resUse = vi.fn();
    vi.doMock('axios', () => ({
      default: {
        create: vi.fn(() => ({
          interceptors: {
            request: { use: vi.fn() },
            response: { use: resUse },
          },
          defaults: { headers: { common: {} } },
        })),
        post: vi.fn().mockRejectedValue(new Error('refresh failed')),
      },
      __esModule: true,
    }));
    await import('../services/api.js');
    responseErrorHandler = resUse.mock.calls[0][1];
  });

  it('toasts on 403 forbidden', async () => {
    const err = { response: { status: 403 }, config: { url: '/bookings' } };
    await expect(responseErrorHandler(err)).rejects.toBe(err);
    expect(toastMock.error).toHaveBeenCalledWith(
      expect.stringContaining('permission')
    );
  });

  it('toasts on 429 rate limit', async () => {
    const err = { response: { status: 429 }, config: { url: '/bookings' } };
    await expect(responseErrorHandler(err)).rejects.toBe(err);
    expect(toastMock.error).toHaveBeenCalledWith(
      expect.stringContaining('many attempts')
    );
  });

  it('toasts on 5xx server error', async () => {
    const err = { response: { status: 500 }, config: { url: '/bookings' } };
    await expect(responseErrorHandler(err)).rejects.toBe(err);
    expect(toastMock.error).toHaveBeenCalledWith(
      expect.stringContaining('Server error')
    );
  });

  it('attaches userMessage to rejected error', async () => {
    const err = {
      response: { status: 400, data: { message: 'Invalid input' } },
      config: { url: '/bookings' },
    };
    try {
      await responseErrorHandler(err);
    } catch (e) {
      expect(e.userMessage).toBe('Invalid input');
    }
  });

  it('network error gets friendly message', async () => {
    const err = { config: { url: '/bookings' }, code: 'ERR_NETWORK' };
    try {
      await responseErrorHandler(err);
    } catch (e) {
      expect(e.userMessage).toContain('connect');
    }
  });
});
