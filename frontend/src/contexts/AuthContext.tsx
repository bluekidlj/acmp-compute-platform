import React, { createContext, useContext, useState, useCallback, useEffect } from 'react';
import { authApi } from '../api/auth';
import type { LoginRequest, LoginResponse, UserRole } from '../types';

interface AuthState {
  token: string | null;
  username: string | null;
  role: UserRole | null;
  isAdmin: boolean;
  isAuthenticated: boolean;
}

interface AuthContextType extends AuthState {
  login: (data: LoginRequest) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [state, setState] = useState<AuthState>(() => {
    const token = localStorage.getItem('token');
    const username = localStorage.getItem('username');
    const role = localStorage.getItem('role') as UserRole | null;
    return {
      token,
      username,
      role,
      isAdmin: role === 'PLATFORM_ADMIN',
      isAuthenticated: !!token,
    };
  });

  const login = useCallback(async (data: LoginRequest) => {
    const res = await authApi.login(data);
    const { token, username, role } = res.data;
    localStorage.setItem('token', token);
    localStorage.setItem('username', username);
    localStorage.setItem('role', role);
    setState({
      token,
      username,
      role,
      isAdmin: role === 'PLATFORM_ADMIN',
      isAuthenticated: true,
    });
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('username');
    localStorage.removeItem('role');
    setState({
      token: null,
      username: null,
      role: null,
      isAdmin: false,
      isAuthenticated: false,
    });
  }, []);

  return (
    <AuthContext.Provider value={{ ...state, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
};
