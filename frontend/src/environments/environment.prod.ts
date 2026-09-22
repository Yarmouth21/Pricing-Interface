export const environment = {
  production: true,
  // In production the app is served behind nginx, which reverse-proxies
  // /api/** to the backend container (see frontend/nginx.conf) — so the
  // frontend can just call relative URLs.
  apiBaseUrl: '',
};
