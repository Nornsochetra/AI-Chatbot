import type { Metadata, Viewport } from 'next';
import { Kantumruy_Pro } from 'next/font/google';
import type { ReactNode } from 'react';

// Khmer + Latin font, self-hosted by Next.js (no request to Google from the customer's browser)
const kantumruy = Kantumruy_Pro({
  subsets: ['khmer', 'latin'],
  weight: ['400', '500', '600'],
  variable: '--font-kantumruy',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'AI Assistant',
};

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  viewportFit: 'cover',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="km" className={kantumruy.variable}>
      <body>{children}</body>
    </html>
  );
}
