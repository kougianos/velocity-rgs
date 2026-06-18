import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';

import { notifyRgsError } from '@/session/errorBus';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error) => notifyRgsError(error),
    }),
    mutationCache: new MutationCache({
      onError: (error) => notifyRgsError(error),
    }),
    defaultOptions: {
      queries: {
        retry: 0,
        refetchOnWindowFocus: false,
        staleTime: 0,
      },
      mutations: {
        retry: 0,
      },
    },
  });
}
