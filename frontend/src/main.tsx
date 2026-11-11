import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';
import { createUserManager } from './auth/userManager';
import { loadRuntimeConfig } from './config';

const container = document.getElementById('root');
if (!container) {
  throw new Error('Elemento #root não encontrado');
}

const root = createRoot(container);

void loadRuntimeConfig().then((config) => {
  const manager = createUserManager(config);
  root.render(
    <StrictMode>
      <BrowserRouter>
        <App manager={manager} config={config} />
      </BrowserRouter>
    </StrictMode>,
  );
});
