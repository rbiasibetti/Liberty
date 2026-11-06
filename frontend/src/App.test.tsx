import { render, screen } from '@testing-library/react';
import { App } from './App';

describe('App', () => {
  it('renderiza o título da plataforma', () => {
    render(<App />);

    expect(screen.getByRole('heading', { name: 'Liberty' })).toBeTruthy();
  });
});
