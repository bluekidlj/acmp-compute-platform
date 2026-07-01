import React, { createContext, useContext, useEffect, useState } from 'react';
import type { UserRole } from '../types';

interface User {
  username: string;
  role: UserRole;
}

interface AuthContextType {
  username: string;
  role: UserRole | null;
  isAdmin: boolean;
  isOrgAdmin: boolean;
  setUser: (u: User | null) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | null>(null);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUserState] = useState<User | null>(() => {
    const raw = localStorage.getItem('user');
    if (raw) try { return JSON.parse(raw); } catch { return null; }
    return null;
  });

  const setUser = (u: User | null) => {
    setUserState(u);
    if (u) localStorage.setItem('user', JSON.stringify(u));
    else localStorage.removeItem('user');
  };

  const logout = () => {
    setUser(null);
    localStorage.removeItem('token');
    localStorage.removeItem('user');
  };

  useEffect(() => {}, []);

  const role = user?.role ?? null;
  const isAdmin = role === 'PLATFORM_ADMIN';
  const isOrgAdmin = role === 'PLATFORM_ADMIN' || role === 'ORG_ADMIN';

  return (
    <AuthContext.Provider
      value={{
        username: user?.username ?? '',
        role,
        isAdmin,
        isOrgAdmin,
        setUser,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}