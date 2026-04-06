import { create } from 'zustand';
import type { Binge } from '../types';

interface BingeState {
  selectedBinge: Binge | null;
  selectBinge: (binge: Binge) => void;
  clearBinge: () => void;
}

const useBingeStore = create<BingeState>((set) => ({
  selectedBinge: (() => {
    try {
      const stored = localStorage.getItem('selectedBinge');
      return stored ? JSON.parse(stored) : null;
    } catch { return null; }
  })(),

  selectBinge: (binge) => {
    localStorage.setItem('selectedBinge', JSON.stringify(binge));
    set({ selectedBinge: binge });
  },

  clearBinge: () => {
    localStorage.removeItem('selectedBinge');
    set({ selectedBinge: null });
  },
}));

export default useBingeStore;
