import { render } from '@testing-library/react';
import { useEffect } from 'react';
import { describe, expect, it } from 'vitest';
import { AuthContext } from '../auth/authContext';
import { ApiProvider } from './ApiProvider';
import { sessionToken } from './session';

/** Um filho que lê o token no próprio efeito de montagem, como as telas fazem ao carregar. */
function FirstCall({ seen }: { seen: (string | null)[] }) {
  useEffect(() => {
    seen.push(sessionToken());
  }, [seen]);
  return null;
}

describe('ApiProvider', () => {
  it('entrega o token atual já à primeira chamada dos filhos (sem voltar ao login)', () => {
    const seen: (string | null)[] = [];
    render(
      <AuthContext.Provider
        value={{
          status: 'authenticated',
          user: null,
          token: () => 'abc',
          login: () => Promise.resolve(),
          logout: () => Promise.resolve(),
        }}
      >
        <ApiProvider baseUrl="">
          <FirstCall seen={seen} />
        </ApiProvider>
      </AuthContext.Provider>,
    );

    expect(seen).toEqual(['abc']);
  });
});
