// Signed-in state: the session token (kept on this device only) and the API calls that need it.
// Signing in is optional for the questionnaire; the AI guide, sharing with a helper and the
// helper portal need it.
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { apiUrl, hasApi } from './data.js';
import { store } from './i18n.js';

const TOKEN_KEY = 'jansathi.session';
const SessionContext = createContext(null);

export class ApiError extends Error {
  constructor(status, detail) {
    super(detail || `HTTP ${status}`);
    this.status = status;
  }
}

/** fetch for /api/v1 with JSON in and out; throws ApiError with the server's problem detail. */
export async function api(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(apiUrl(path), {
    method,
    headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let detail = '';
    try {
      detail = (await res.json()).detail;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, detail);
  }
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export function SessionProvider({ children }) {
  const [token, setToken] = useState(() => store.get(TOKEN_KEY));
  const [user, setUser] = useState(null);
  const [checked, setChecked] = useState(!token);

  const signOut = useCallback(async () => {
    const old = token;
    store.remove(TOKEN_KEY);
    setToken(null);
    setUser(null);
    if (old && hasApi()) await api('/auth/sign-out', { method: 'POST', token: old }).catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!token || !hasApi()) {
      setChecked(true);
      return;
    }
    api('/me', { token })
      .then(setUser)
      .catch((e) => {
        if (e.status === 401) {
          store.remove(TOKEN_KEY);
          setToken(null);
        }
      })
      .finally(() => setChecked(true));
  }, [token]);

  const value = useMemo(
    () => ({
      available: hasApi(),
      token,
      user,
      checked,
      signedIn: !!user,
      call: (path, opts = {}) => api(path, { ...opts, token }),
      signIn(newToken, newUser) {
        store.set(TOKEN_KEY, newToken);
        setToken(newToken);
        setUser(newUser);
      },
      signOut,
    }),
    [token, user, checked, signOut],
  );
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export const useSession = () => useContext(SessionContext);
