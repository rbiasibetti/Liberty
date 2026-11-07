import { Button, Center, Stack, Text, Title } from '@mantine/core';
import { useAuth } from '../auth/useAuth';

export function LandingPage() {
  const { login } = useAuth();
  return (
    <Center h="100vh" p="md">
      <Stack align="center" maw={420}>
        <Title order={1}>Liberty</Title>
        <Text ta="center" c="dimmed">
          Plataforma de integração orientada a metadados.
        </Text>
        <Button
          onClick={() => {
            void login(window.location.pathname);
          }}
        >
          Entrar
        </Button>
      </Stack>
    </Center>
  );
}
