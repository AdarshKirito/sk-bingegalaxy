import { createContext, useContext } from 'react';
import useBingeStore from '../stores/bingeStore';

const BingeContext = createContext(null);

export function BingeProvider({ children }) {
  const store = useBingeStore();
  return (
    <BingeContext.Provider value={store}>
      {children}
    </BingeContext.Provider>
  );
}

export const useBinge = () => {
  const ctx = useContext(BingeContext);
  if (!ctx) throw new Error('useBinge must be used within BingeProvider');
  return ctx;
};
