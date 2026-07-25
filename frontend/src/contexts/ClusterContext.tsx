import React, { createContext, useContext, useEffect, useState } from 'react';

interface ClusterContextType {
  clusterId: string;
  clusterName: string;
  setClusterId: (id: string) => void;
}

const ClusterContext = createContext<ClusterContextType | null>(null);

export const ClusterProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [clusterId, setClusterIdState] = useState<string>('');

  const setClusterId = (id: string) => {
    setClusterIdState(id);
    localStorage.setItem('ACMP_CLUSTER_ID', id);
  };

  useEffect(() => {
    const saved = localStorage.getItem('ACMP_CLUSTER_ID');
    if (saved) setClusterIdState(saved);
  }, []);

  const clusterName = clusterId;

  return (
    <ClusterContext.Provider value={{ clusterId, clusterName, setClusterId }}>
      {children}
    </ClusterContext.Provider>
  );
};

export function useCluster() {
  const ctx = useContext(ClusterContext);
  if (!ctx) throw new Error('useCluster must be used within ClusterProvider');
  return ctx;
}
