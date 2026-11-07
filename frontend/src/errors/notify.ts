import { notifications } from '@mantine/notifications';
import { ApiError, describeError } from '../api/problem';

/** Mostra um erro para o usuário; com `requestId`, o suporte consegue achar a execução no servidor. */
export function notifyError(error: unknown, title = 'Não foi possível concluir') {
  const requestId = error instanceof ApiError ? error.requestId : undefined;
  notifications.show({
    color: 'red',
    title,
    message: requestId
      ? `${describeError(error)} (código ${requestId.slice(0, 8)})`
      : describeError(error),
  });
}
