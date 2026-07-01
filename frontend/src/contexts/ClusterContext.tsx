import React, { createContext, useContext, useEffect, useState } from 'react';

interface ClusterContextType {
  clusterId: string;
  clusterName: string;
  setClusterId: (id: string) => void;
}

const ClusterContext = createContext<ClusterContextType | null>(null);

export const ClusterProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [clusterId, setClusterIdState] = useState<string>('cluster-bj-01');

  const setClusterId = (id: string) => {
    setClusterIdState(id);
    localStorage.setItem('ACMP_CLUSTER_ID', id);
  };

  useEffect(() => {
    const saved = localStorage.getItem('ACMP_CLUSTER_ID');
    if (saved) setClusterIdState(saved);
  }, []);

  const clusterName = clusterId === 'cluster-bj-01' ? '北京生产 K8s 集群'
    : clusterId === 'cluster-sh-01' ? '上海测试 K8s 集群' : clusterId;

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
