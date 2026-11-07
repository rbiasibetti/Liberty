import { Alert, Button, Center, Loader, Stack } from '@mantine/core';
import { Route, Routes } from 'react-router';
import { useApi } from './api/apiContext';
import { AppLayout } from './layout/AppLayout';
import { NotFoundPage } from './pages/NotFoundPage';
import { AuditPage } from './screens/AuditPage';
import { EditorPage } from './screens/EditorPage';
import { IntegrationsPage } from './screens/IntegrationsPage';
import { ReviewPage } from './screens/ReviewPage';
import { TenantAdminPage } from './screens/TenantAdminPage';
import { TenantProvider } from './tenant/TenantProvider';
import { useTenant } from './tenant/tenantContext';

function Gate() {
  const tenant = useTenant();
  if (tenant.state === 'loading') {
    return (
      <Center h="100vh">
        <Loader aria-label="Carregando seu acesso" />
      </Center>
    );
  }
  if (tenant.state === 'error') {
    return (
      <Center h="100vh" p="md">
        <Stack maw={420}>
          <Alert color="red" title="Não foi possível carregar seu acesso">
            {tenant.error}
          </Alert>
          <Button onClick={tenant.reload}>Tentar de novo</Button>
        </Stack>
      </Center>
    );
  }
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<IntegrationsPage />} />
        <Route path="integracoes/:key" element={<EditorPage />} />
        <Route path="integracoes/:key/versoes/:no" element={<ReviewPage />} />
        <Route path="auditoria" element={<AuditPage />} />
        <Route path="administracao" element={<TenantAdminPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

/** Parte da aplicação que só existe com sessão: carrega os tenants e monta as rotas. */
export function AuthenticatedApp() {
  const api = useApi();
  return (
    <TenantProvider api={api}>
      <Gate />
    </TenantProvider>
  );
}
