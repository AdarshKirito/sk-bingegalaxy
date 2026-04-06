import { createContext, useContext } from 'react';
import useAuthStore from '../stores/authStore';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const store = useAuthStore();
  return (
    <AuthContext.Provider value={store}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = () => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
};
