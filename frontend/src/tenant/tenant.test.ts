import { describe, expect, it } from 'vitest';
import { hasRole } from './tenantContext';

describe('hasRole', () => {
  it('segue a hierarquia do backend (PUBLISHER > INTEGRATOR > VIEWER)', () => {
    expect(hasRole(['PUBLISHER'], 'VIEWER')).toBe(true);
    expect(hasRole(['PUBLISHER'], 'INTEGRATOR')).toBe(true);
    expect(hasRole(['INTEGRATOR'], 'PUBLISHER')).toBe(false);
    expect(hasRole(['VIEWER'], 'INTEGRATOR')).toBe(false);
    expect(hasRole([], 'VIEWER')).toBe(false);
  });
});
