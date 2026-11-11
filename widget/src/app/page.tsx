import Script from 'next/script';

import { DEFAULT_API_BASE } from '../lib/config';
import styles from './demo.module.css';

/** Demo page that imitates a public bank website. The only widget-related line is the <Script> tag. */
export default function DemoBankPage() {
  return (
    <div className={styles.page}>
      <header className={styles.header}>Demo Bank — test page for the chat widget</header>
      <main className={styles.main}>
        <div className={styles.card}>
          <h2>Savings accounts</h2>
          <p>
            This page imitates a public bank website. The chat button in the bottom-right corner is the widget, loaded
            by one script tag.
          </p>
        </div>
        <div className={styles.card}>
          <h2>Try asking</h2>
          <ul>
            <li>តើត្រូវការឯកសារអ្វីខ្លះដើម្បីបើកគណនីសន្សំ?</li>
            <li>ចុះប្រាក់ដាក់ដំបូងវិញ?</li>
            <li>What are the branch opening hours?</li>
            <li>My OTP is 123456 (masking test)</li>
            <li>Tap &quot;Talk to an agent&quot;, then answer from the agent console (http://localhost:8080/agent)</li>
          </ul>
          <p className={styles.note}>
            Mobile full-screen page: <a href="/chat?channel=MOBILE_APP&lang=km">/chat?channel=MOBILE_APP&amp;lang=km</a>
          </p>
        </div>
      </main>

      {/* The only line a bank page needs */}
      <Script
        src="/widget-loader.js"
        strategy="afterInteractive"
        data-kb-chat=""
        data-channel="WEBSITE"
        data-lang="km"
        data-api={DEFAULT_API_BASE}
      />
    </div>
  );
}
