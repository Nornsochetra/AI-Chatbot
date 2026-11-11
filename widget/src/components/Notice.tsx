import type { ReactNode } from 'react';

interface Props {
  kind: 'info' | 'error';
  children: ReactNode;
}

export function Notice({ kind, children }: Props) {
  return (
    <div className={`kb-notice kb-notice--${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  );
}
