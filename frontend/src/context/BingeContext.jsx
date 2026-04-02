import { createContext, useContext, useState, useCallback } from 'react';

const BingeContext = createContext(null);

export function BingeProvider({ children }) {
  const [selectedBinge, setSelectedBinge] = useState(() => {
    const stored = localStorage.getItem('selectedBinge');
    return stored ? JSON.parse(stored) : null;
  });

  const selectBinge = useCallback((binge) => {
    localStorage.setItem('selectedBinge', JSON.stringify(binge));
    setSelectedBinge(binge);
  }, []);

  const clearBinge = useCallback(() => {
    localStorage.removeItem('selectedBinge');
    setSelectedBinge(null);
  }, []);

  return (
    <BingeContext.Provider value={{ selectedBinge, selectBinge, clearBinge }}>
      {children}
    </BingeContext.Provider>
  );
}

export const useBinge = () => {
  const ctx = useContext(BingeContext);
  if (!ctx) throw new Error('useBinge must be used within BingeProvider');
  return ctx;
};
