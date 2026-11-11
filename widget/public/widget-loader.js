/**
 * Embed script for bank web pages (plain JavaScript, no build step, served as-is):
 *   <script src="https://<widget host>/widget-loader.js" data-kb-chat data-channel="WEBSITE" data-lang="km" data-api="https://api..."></script>
 * Draws the floating button and opens the chat page (/chat) in an iframe, isolated from the host page.
 */
(function () {
  'use strict';

  var LOADED_FLAG = '__kbChatLoaded';
  var PREFIX = 'kbchat';

  var LABELS = {
    km: { open: 'បើកការជជែក', close: 'បិទការជជែក', title: 'ជំនួយការ AI' },
    en: { open: 'Open chat', close: 'Close chat', title: 'AI Assistant' },
  };

  var CHAT_ICON =
    '<svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor" aria-hidden="true"><path d="M12 3C6.5 3 2 6.8 2 11.5c0 2.4 1.2 4.6 3.1 6.1L4 21l4.1-2c1.2.4 2.5.5 3.9.5 5.5 0 10-3.8 10-8.5S17.5 3 12 3z"/></svg>';
  var CLOSE_ICON =
    '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';

  var STYLES =
    '.' + PREFIX + '-launcher{position:fixed;right:20px;bottom:20px;z-index:2147483000;width:60px;height:60px;' +
    'border-radius:50%;border:0;cursor:pointer;background:#1f4e8c;color:#fff;display:grid;place-items:center;' +
    'box-shadow:0 6px 20px rgba(0,0,0,.22);transition:transform .15s ease}' +
    '.' + PREFIX + '-launcher:hover{transform:scale(1.06)}' +
    '.' + PREFIX + '-launcher:focus-visible{outline:3px solid #93b4e0;outline-offset:2px}' +
    '.' + PREFIX + '-frame{position:fixed;right:20px;bottom:92px;z-index:2147483000;width:380px;' +
    'height:min(620px,calc(100vh - 120px));border:0;border-radius:16px;overflow:hidden;background:#fff;' +
    'box-shadow:0 12px 40px rgba(0,0,0,.25);display:none}' +
    '.' + PREFIX + '-frame.' + PREFIX + '-open{display:block}' +
    '@media (max-width:480px){.' + PREFIX + '-frame{right:0;bottom:0;width:100vw;height:100%;border-radius:0}' +
    '.' + PREFIX + '-open~.' + PREFIX + '-launcher{display:none}}';

  function init() {
    if (window[LOADED_FLAG]) return;
    window[LOADED_FLAG] = true;

    var script = document.querySelector('script[data-kb-chat]');
    if (!script) {
      console.warn('[kb-chat] loader script tag with data-kb-chat not found');
      return;
    }

    var data = script.dataset;
    var lang = data.lang === 'en' ? 'en' : 'km';
    var labels = LABELS[lang];

    var chatUrl = new URL(data.chatUrl || '/chat', script.src);
    chatUrl.searchParams.set('channel', data.channel === 'MOBILE_APP' ? 'MOBILE_APP' : 'WEBSITE');
    chatUrl.searchParams.set('lang', lang);
    if (data.api) chatUrl.searchParams.set('api', data.api);

    var style = document.createElement('style');
    style.textContent = STYLES;
    document.head.appendChild(style);

    var frame = null;
    var open = false;

    var launcher = document.createElement('button');
    launcher.type = 'button';
    launcher.className = PREFIX + '-launcher';

    function render() {
      launcher.innerHTML = open ? CLOSE_ICON : CHAT_ICON;
      launcher.setAttribute('aria-label', open ? labels.close : labels.open);
      launcher.setAttribute('aria-expanded', String(open));
      if (frame) frame.classList.toggle(PREFIX + '-open', open);
    }

    function setOpen(value) {
      // create the iframe on first open only, so the bank page loads nothing extra until needed
      if (value && !frame) {
        frame = document.createElement('iframe');
        frame.className = PREFIX + '-frame';
        frame.src = chatUrl.toString();
        frame.title = labels.title;
        frame.allow = 'microphone';
        document.body.insertBefore(frame, launcher);
      }
      open = value;
      render();
    }

    launcher.addEventListener('click', function () {
      setOpen(!open);
    });

    // the close button inside the chat; only trust messages from the chat page's origin
    window.addEventListener('message', function (event) {
      if (event.origin !== chatUrl.origin) return;
      if (event.data && event.data.type === 'kb-chat:close') setOpen(false);
    });

    document.body.appendChild(launcher);
    render();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
