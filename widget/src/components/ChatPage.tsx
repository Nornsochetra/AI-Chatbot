'use client';

import { useEffect, useState } from 'react';

import { readConfig, type WidgetConfig } from '../lib/config';
import { ChatApp } from './ChatApp';

/**
 * Reads the URL parameters and sessionStorage only in the browser, after the first render,
 * so the server-rendered HTML and the first client render match.
 */
export function ChatPage() {
  const [config, setConfig] = useState<WidgetConfig | null>(null);

  useEffect(() => {
    setConfig(readConfig());
  }, []);

  return config ? <ChatApp config={config} /> : null;
}
