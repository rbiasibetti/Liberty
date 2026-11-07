import { Alert, Center, Loader } from '@mantine/core';
import type { UserManager } from 'oidc-client-ts';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { takeReturnTo } from './authContext';

/**
 * O código de autorização só vale uma vez. No modo de desenvolvimento o React executa o efeito duas
 * vezes; a mesma troca em andamento é reaproveitada em vez de reenviar o código, que seria recusado.
 */
const exchanges = new WeakMap<object, Promise<unknown>>();

function exchangeOnce(manager: Pick<UserManager, 'signinRedirectCallback'>): Promise<unknown> {
  let pending = exchanges.get(manager);
  if (!pending) {
    pending = manager.signinRedirectCallback();
    exchanges.set(manager, pending);
  }
  return pending;
}

/** Conclui o login: troca o código por tokens e volta para onde o usuário estava. */
export function CallbackPage({
  manager,
}: {
  manager: Pick<UserManager, 'signinRedirectCallback'>;
}) {
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    exchangeOnce(manager).then(
      () => {
        if (active) {
          void navigate(takeReturnTo(), { replace: true });
        }
      },
      () => {
        if (active) {
          setFailed(true);
        }
      },
    );
    return () => {
      active = false;
    };
  }, [manager, navigate]);

  if (failed) {
    return (
      <Center h="100vh" p="md">
        <Alert color="red" title="Não foi possível entrar">
          O login não foi concluído. Volte à página inicial e tente de novo.
        </Alert>
      </Center>
    );
  }
  return (
    <Center h="100vh">
      <Loader aria-label="Concluindo o login" />
    </Center>
  );
}
