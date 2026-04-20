/** Mock for virtual:pwa-register/react — used by Vitest since the virtual module only exists at Vite build time. */
export function useRegisterSW() {
  return {
    needRefresh: [false, () => {}],
    offlineReady: [false, () => {}],
    updateServiceWorker: () => Promise.resolve(),
  };
}
