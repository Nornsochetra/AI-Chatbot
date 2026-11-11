import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // The chat page (/chat) is shown inside an iframe on the bank's pages, so it must not send
  // X-Frame-Options: DENY. For production, restrict who may frame it with
  // Content-Security-Policy: frame-ancestors <bank domains>.
};

export default nextConfig;
