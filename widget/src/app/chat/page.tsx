import type { Metadata } from 'next';

import { ChatPage } from '../../components/ChatPage';
import '../../styles/theme.css';

export const metadata: Metadata = {
  title: 'AI Assistant',
};

/** The chat itself: loaded in the website iframe and in the mobile app webview. */
export default function Page() {
  return <ChatPage />;
}
