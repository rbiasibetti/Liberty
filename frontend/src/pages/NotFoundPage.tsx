import { Anchor, Stack, Text, Title } from '@mantine/core';
import { Link } from 'react-router';

export function NotFoundPage() {
  return (
    <Stack>
      <Title order={2}>Página não encontrada</Title>
      <Text c="dimmed">O endereço não existe nesta aplicação.</Text>
      <Anchor component={Link} to="/">
        Voltar ao início
      </Anchor>
    </Stack>
  );
}
