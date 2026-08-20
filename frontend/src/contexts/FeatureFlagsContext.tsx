import { createContext, useContext, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { api } from '../api/real';
import type { FeatureFlags } from '../types';

interface FeatureFlagsState extends FeatureFlags {
  loaded: boolean;
}

const FeatureFlagsContext = createContext<FeatureFlagsState>({
  innovationLabEnabled: false,
  loaded: false,
});

export function FeatureFlagsProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<FeatureFlagsState>({
    innovationLabEnabled: false,
    loaded: false,
  });

  useEffect(function loadFeatureFlags() {
    api.featureFlags()
      .then(function apply(flags) {
        setState({ ...flags, loaded: true });
      })
      .catch(function disableOptionalFeatures() {
        setState({ innovationLabEnabled: false, loaded: true });
      });
  }, []);

  return <FeatureFlagsContext.Provider value={state}>{children}</FeatureFlagsContext.Provider>;
}

export function useFeatureFlags() {
  return useContext(FeatureFlagsContext);
}
