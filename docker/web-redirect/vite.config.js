import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// URL do traccar-web. No Docker vem do ARG WEB_PUBLIC_URL; este é o padrão local.
process.env.VITE_WEB_PUBLIC_URL ||= 'http://traccar-web.ouoljf.easypanel.host';

// Página servida pelo traccar-server no lugar do front antigo embutido.
// Os assets ficam em /redirect-assets para não colidir com nada do Traccar.
export default defineConfig({
  base: '/',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'dist',
    assetsDir: 'redirect-assets',
  },
});
