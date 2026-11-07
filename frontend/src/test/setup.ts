import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// O Mantine usa matchMedia, ResizeObserver e scrollIntoView, que o jsdom não traz.
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => false,
  }),
});

class ResizeObserverStub {
  observe() {
    return undefined;
  }
  unobserve() {
    return undefined;
  }
  disconnect() {
    return undefined;
  }
}
window.ResizeObserver = ResizeObserverStub;
// o Textarea com autosize do Mantine escuta o carregamento de fontes
Object.defineProperty(document, 'fonts', {
  value: {
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    ready: Promise.resolve(),
  },
});
window.HTMLElement.prototype.scrollIntoView = () => undefined;

afterEach(() => {
  cleanup();
  window.sessionStorage.clear();
});
