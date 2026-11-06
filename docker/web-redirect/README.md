# web-redirect

Página servida pelo traccar-server no lugar do front antigo embutido.
Avisa que a interface mudou para o traccar-web e redireciona mantendo o caminho.

- React + Tailwind CSS v4 + Motion + Lucide
- URL de destino: `VITE_WEB_PUBLIC_URL` (no Docker vem do `ARG WEB_PUBLIC_URL`; padrão em `vite.config.js`)
- `public/sw.js` remove o service worker do PWA antigo dos navegadores

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # gera dist/
```
