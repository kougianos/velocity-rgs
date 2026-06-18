import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';

import { App } from '@/app/App';
import { createQueryClient } from '@/app/queryClient';
import '@/env';
import { initWebVitals } from '@/observability/webVitals';
import '@/styles/global.css';

async function bootstrap(): Promise<void> {
  if (import.meta.env.VITE_ENABLE_MSW === 'true') {
    const { startMockServiceWorker } = await import('@/mocks/browser');
    await startMockServiceWorker();
  }

  const rootElement = document.getElementById('root');
  if (!rootElement) {
    throw new Error('Root element #root not found');
  }

  const queryClient = createQueryClient();

  createRoot(rootElement).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </StrictMode>,
  );

  initWebVitals();
}

void bootstrap();
