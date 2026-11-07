import { Center, Loader, MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import type { UserManager } from 'oidc-client-ts';
import { Route, Routes } from 'react-router';
import { ApiProvider } from './api/ApiProvider';
import { AuthenticatedApp } from './AuthenticatedApp';
import { AuthProvider } from './auth/AuthProvider';
import { CallbackPage } from './auth/CallbackPage';
import { useAuth } from './auth/useAuth';
import type { AppConfig } from './config';
import { ErrorBoundary } from './errors/ErrorBoundary';
import { LandingPage } from './pages/LandingPage';

function Protected({ config }: { config: AppConfig }) {
  const { status } = useAuth();
  if (status === 'loading') {
    return (
      <Center h="100vh">
        <Loader aria-label="Verificando a sessão" />
      </Center>
    );
  }
  if (status === 'anonymous') {
    return <LandingPage />;
  }
  return (
    <ApiProvider baseUrl={config.apiUrl}>
      <AuthenticatedApp />
    </ApiProvider>
  );
}

export function App({ manager, config }: { manager: UserManager; config: AppConfig }) {
  return (
    <MantineProvider defaultColorScheme="auto">
      <Notifications />
      <ErrorBoundary>
        <AuthProvider manager={manager}>
          <Routes>
            <Route path="/callback" element={<CallbackPage manager={manager} />} />
            <Route path="*" element={<Protected config={config} />} />
          </Routes>
        </AuthProvider>
      </ErrorBoundary>
    </MantineProvider>
  );
}
