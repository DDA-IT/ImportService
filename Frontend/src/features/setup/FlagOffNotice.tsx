/**
 * NT-11c — de melding "deze functie staat op deze omgeving niet open" (404 zonder code bij een uitgeschakelde
 * `@ConditionalOnProperty`-controller): gewoon Nederlands vooraan, de naam van de serverinstelling klein onder
 * "Technische details (voor support)" (V7). Eén component voor het inrichtings- en het sjabloondeel.
 */

import { TechnicalDetails } from '../../terms/TechnicalDetails.tsx';
import { SETUP_API_FLAG_NAME } from '../templates/setupApiFlag.ts';

export type FlagOffNoticeProps = { message: string; className?: string };

export function FlagOffNotice({ message, className }: FlagOffNoticeProps) {
  return (
    <div role="alert" className={className}>
      {message}
      <TechnicalDetails items={[{ name: 'Instelling op de server', value: `${SETUP_API_FLAG_NAME}=false` }]} />
    </div>
  );
}
