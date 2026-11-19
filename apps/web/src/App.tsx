import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { TacticalWorkspace } from '@/pages/TacticalWorkspace';
import { CommunityPortal } from '@/pages/CommunityPortal';
import { VerifierPage } from '@/pages/VerifierPage';
import { AuthPage } from '@/pages/AuthPage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<TacticalWorkspace />} />
          <Route path="/community" element={<CommunityPortal />} />
          <Route path="/verify" element={<VerifierPage />} />
          <Route path="/verify/:packetId" element={<VerifierPage />} />
          <Route path="/login" element={<AuthPage mode="login" />} />
          <Route path="/register" element={<AuthPage mode="register" />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}

export default App;
