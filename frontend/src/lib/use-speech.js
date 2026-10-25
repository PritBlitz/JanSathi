// React hooks around speech.js: read aloud in the current language, with a notice when the
// device has no voice for it (never reads text in another language's voice).
import { useCallback, useEffect, useState } from 'react';
import { canSpeak, onSpeakingChange, speak } from './speech.js';
import { useI18n } from './i18n-context.jsx';
import { useApp } from './app-state.jsx';

export function useSay() {
  const { t, speech, langName } = useI18n();
  const { showNotice } = useApp();
  return useCallback(
    (text) => {
      if (!canSpeak()) {
        showNotice(t('speech.unsupported'));
        return false;
      }
      if (!speak(text, speech)) {
        showNotice(t('speech.no_voice', { language: langName }));
        return false;
      }
      return true;
    },
    [t, speech, langName, showNotice],
  );
}

export function useSpeaking() {
  const [on, setOn] = useState(false);
  useEffect(() => onSpeakingChange(setOn), []);
  return on;
}
