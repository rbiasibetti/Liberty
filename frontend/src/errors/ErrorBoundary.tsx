import { Alert, Button, Center, Stack } from '@mantine/core';
import { Component } from 'react';
import type { ReactNode } from 'react';

interface State {
  failed: boolean;
}

/** Última barreira: um erro de renderização mostra uma tela de recuperação em vez de página em branco. */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  override state: State = { failed: false };

  static getDerivedStateFromError(): State {
    return { failed: true };
  }

  override render() {
    if (!this.state.failed) {
      return this.props.children;
    }
    return (
      <Center h="100vh" p="md">
        <Stack maw={420}>
          <Alert color="red" title="Algo deu errado">
            A tela encontrou um erro inesperado. Seus dados no servidor não foram afetados.
          </Alert>
          <Button
            onClick={() => {
              window.location.assign('/');
            }}
          >
            Voltar ao início
          </Button>
        </Stack>
      </Center>
    );
  }
}
