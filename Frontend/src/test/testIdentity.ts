import { PERMISSIONS, type Permission } from '../api/types';

/**
 * Vaste geverifieerde identiteit voor tests (testnaad `identity` van `ActorProvider`, geen `/me`-fetch).
 * Standaard met ALLE drie de rechten, zodat bestaande tests ongewijzigd slagen; gebruik
 * {@link testIdentityWith} voor een subset (de effectieve set, zoals `/me` hem levert).
 */
export const TEST_IDENTITY = {
  username: 'An Beslisser',
  subject: 'test-sub',
  displayName: null,
  permissions: [...PERMISSIONS] as string[],
};

export function testIdentityWith(...permissions: Permission[]) {
  return { ...TEST_IDENTITY, permissions: [...permissions] as string[] };
}
