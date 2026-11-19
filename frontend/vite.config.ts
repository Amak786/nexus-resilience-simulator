import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development, /api is proxied to the local Spring Boot server (override with API_PROXY=http://localhost:PORT).
// In production (Vercel), set VITE_API_BASE_URL to the public HTTPS URL of the API (e.g. on Render).
const target = process.env.API_PROXY ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': target, '/v3': target, '/swagger-ui': target },
  },
});
