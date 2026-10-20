import type { ReactNode, SVGProps } from 'react';

/** Iconen uit de Prodis-mock (inline SVG, geen icoonbibliotheek). Kleur via currentColor. */
export interface IconProps extends Omit<SVGProps<SVGSVGElement>, 'width' | 'height' | 'children'> {
  size?: number | string;
}

function icon(paths: ReactNode) {
  return function Icon({ size = 24, ...rest }: IconProps) {
    return (
      <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true" focusable="false" {...rest}>
        {paths}
      </svg>
    );
  };
}

export const QueueIcon = icon(<><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v13a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18.5z" opacity={0.32}/><path d="M8 8h8a1 1 0 1 1 0 2H8a1 1 0 1 1 0-2m0 4h8a1 1 0 1 1 0 2H8a1 1 0 1 1 0-2m0 4h5a1 1 0 1 1 0 2H8a1 1 0 1 1 0-2"/></>);

export const CaseIcon = icon(<><path d="M12 2 2 20.5h20z" opacity={0.32}/><path d="M11 9h2v6h-2zm0 7.5h2v2h-2z"/></>);

export const UploadIcon = icon(<><path d="M4 15h3.5l1.5 2h6l1.5-2H20v3.5a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 18.5z" opacity={0.32}/><path d="M12 3a1 1 0 0 1 .71.29l4 4a1 1 0 0 1-1.42 1.42L13 6.41V14a1 1 0 1 1-2 0V6.41L8.71 8.71a1 1 0 0 1-1.42-1.42l4-4A1 1 0 0 1 12 3"/></>);

export const BundleIcon = icon(<><path d="m12 2 9 4.5-9 4.5-9-4.5z"/><path d="m3 11 9 4.5 9-4.5v3l-9 4.5L3 14z" opacity={0.32}/><path d="m3 16 9 4.5 9-4.5v2l-9 4.5L3 18z" opacity={0.5}/></>);

export const SetupIcon = icon(<><path d="M3 4.5A1.5 1.5 0 0 1 4.5 3h6A1.5 1.5 0 0 1 12 4.5v6A1.5 1.5 0 0 1 10.5 12h-6A1.5 1.5 0 0 1 3 10.5zm9 9a1.5 1.5 0 0 1 1.5-1.5h6a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5h-6a1.5 1.5 0 0 1-1.5-1.5z"/><path d="M13.5 3h6A1.5 1.5 0 0 1 21 4.5v6a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 12 10.5v-6A1.5 1.5 0 0 1 13.5 3m-9 9h6a1.5 1.5 0 0 1 1.5 1.5v6a1.5 1.5 0 0 1-1.5 1.5h-6A1.5 1.5 0 0 1 3 19.5v-6A1.5 1.5 0 0 1 4.5 12" opacity={0.32}/></>);

export const SupplierIcon = icon(<><circle cx="10" cy="7" r="4"/><path d="M2 19c0-3.3 3.6-6 8-6s8 2.7 8 6v1H2z" opacity={0.32}/><path d="M19 8a1 1 0 0 1 1 1v2h2a1 1 0 1 1 0 2h-2v2a1 1 0 1 1-2 0v-2h-2a1 1 0 1 1 0-2h2V9a1 1 0 0 1 1-1"/></>);

export const TemplateIcon = icon(<><path d="M6 2h8l6 6v11.5a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2" opacity={0.32}/><path d="M14 2v4.5A1.5 1.5 0 0 0 15.5 8H20zM8 12h8a1 1 0 1 1 0 2H8a1 1 0 1 1 0-2m0 4h5a1 1 0 1 1 0 2H8a1 1 0 1 1 0-2"/></>);

export const ChevIcon = icon(<><path d="M13.83 19a1 1 0 0 1-.78-.37l-4.83-6a1 1 0 0 1 0-1.27l5-6a1 1 0 0 1 1.54 1.28L10.29 12l4.32 5.36a1 1 0 0 1-.78 1.64"/></>);

export const ChevRIcon = icon(<><path d="M10 19a1 1 0 0 1-.64-.23a1 1 0 0 1-.13-1.41L13.71 12L9.39 6.63a1 1 0 0 1 .15-1.41a1 1 0 0 1 1.46.15l4.83 6a1 1 0 0 1 0 1.27l-5 6A1 1 0 0 1 10 19"/></>);

export const ChevDIcon = icon(<><path d="M12 16a1 1 0 0 1-.64-.23l-6-5a1 1 0 1 1 1.28-1.54L12 13.71l5.36-4.32a1 1 0 0 1 1.41.15a1 1 0 0 1-.14 1.46l-6 4.83A1 1 0 0 1 12 16"/></>);

export const MenuIcon = icon(<><path d="M4 6h16a1 1 0 1 1 0 2H4a1 1 0 0 1 0-2m0 5h10a1 1 0 1 1 0 2H4a1 1 0 0 1 0-2m0 5h16a1 1 0 1 1 0 2H4a1 1 0 0 1 0-2"/></>);

export const SearchIcon = icon(<><path d="m20.71 19.29-3.4-3.39A7.92 7.92 0 0 0 19 11a8 8 0 1 0-8 8a7.92 7.92 0 0 0 4.9-1.69l3.39 3.4a1 1 0 0 0 1.42 0a1 1 0 0 0 0-1.42M5 11a6 6 0 1 1 6 6a6 6 0 0 1-6-6"/></>);

export const PlusIcon = icon(<><path d="M12 4a1 1 0 0 1 1 1v6h6a1 1 0 1 1 0 2h-6v6a1 1 0 1 1-2 0v-6H5a1 1 0 1 1 0-2h6V5a1 1 0 0 1 1-1"/></>);

export const MoonIcon = icon(<><path d="M12 22c5.5 0 10-4.5 10-10 0-.5-.6-.8-1-.5A6.5 6.5 0 0 1 12.5 3c.3-.4 0-1-.5-1C6.5 2 2 6.5 2 12s4.5 10 10 10"/></>);

export const UserIcon = icon(<><circle cx="12" cy="7" r="4"/><path d="M4 19c0-3.3 3.6-6 8-6s8 2.7 8 6v1H4z" opacity={0.4}/></>);

export const LogoutIcon = icon(<><path d="M14 3h3.5A2.5 2.5 0 0 1 20 5.5v13a2.5 2.5 0 0 1-2.5 2.5H14z" opacity={0.32}/><path d="M9.3 7.3a1 1 0 0 1 1.4 1.4L8.4 11H15a1 1 0 1 1 0 2H8.4l2.3 2.3a1 1 0 0 1-1.4 1.4l-4-4a1 1 0 0 1 0-1.4z"/></>);

export const FileIcon = icon(<><path d="M6 2h8l6 6v11.5a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2" opacity={0.32}/><path d="M14 2v4.5A1.5 1.5 0 0 0 15.5 8H20z"/></>);

export const ClockIcon = icon(<><circle cx="12" cy="12" r="10" opacity={0.32}/><path d="M12 6a1 1 0 0 1 1 1v4.6l3 1.8a1 1 0 1 1-1 1.7l-3.5-2.1A1 1 0 0 1 11 12V7a1 1 0 0 1 1-1"/></>);

export const LinkIcon = icon(<><path d="M10.6 13.4a1 1 0 0 1 0-1.4l3-3a1 1 0 1 1 1.4 1.4l-3 3a1 1 0 0 1-1.4 0"/><path d="M8.5 11 7 12.5a3.5 3.5 0 0 0 5 5l1.5-1.5a1 1 0 1 1 1.4 1.4l-1.5 1.5a5.5 5.5 0 0 1-7.8-7.8L7.1 9.6A1 1 0 0 1 8.5 11m7-2 1.5-1.5a3.5 3.5 0 0 0-5-5L10.5 4a1 1 0 0 1-1.4-1.4l1.5-1.5a5.5 5.5 0 0 1 7.8 7.8l-1.5 1.5A1 1 0 1 1 15.5 9" opacity={0.5}/></>);

export const WarnIcon = icon(<><path d="M12 2 1.5 21h21z" opacity={0.32}/><path d="M11 9h2v6h-2zm0 7.5h2v2h-2z"/></>);

export const InfoIcon = icon(<><circle cx="12" cy="12" r="10" opacity={0.32}/><path d="M11 10h2v7h-2zm0-3.5h2v2h-2z"/></>);

export const CheckIcon = icon(<><path d="M9.55 17.6a1 1 0 0 1-.71-.29l-4.13-4.13a1 1 0 1 1 1.42-1.42l3.42 3.43 7.87-7.88a1 1 0 1 1 1.42 1.42l-8.58 8.58a1 1 0 0 1-.71.29"/></>);

export const BackIcon = icon(<><path d="M13.83 19a1 1 0 0 1-.78-.37l-4.83-6a1 1 0 0 1 0-1.27l5-6a1 1 0 0 1 1.54 1.28L10.29 12l4.32 5.36a1 1 0 0 1-.78 1.64"/></>);
