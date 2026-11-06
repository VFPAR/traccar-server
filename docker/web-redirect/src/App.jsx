import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'motion/react';
import { ArrowRight, Check, Copy, MapPin } from 'lucide-react';

const SECONDS = 5;
const BASE_URL = (import.meta.env.VITE_WEB_PUBLIC_URL || '').replace(/\/+$/, '');

const ease = [0.2, 0.8, 0.2, 1];
const fade = (delay = 0) => ({
  initial: { opacity: 0, y: 8 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.5, ease, delay },
});

// Ilustração própria: mapa com rota, veículo elétrico em movimento, ponto de recarga e destino.
// Cores da Mobilize-C: grafite (#272727 / #24252a / #2b2c31) e verde-limão (#b6ff22 / #85d73e).
const ROUTE = 'M90 240 H200 V160 H320 V80 H410';
const LIME = '#b6ff22';

function FleetMap() {
  return (
    <motion.svg
      viewBox="0 0 480 290"
      role="img"
      aria-label="Mapa com um veículo elétrico seguindo uma rota até o destino"
      className="mx-auto mb-8 block h-auto w-full max-w-[440px] select-none"
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.6, ease }}
    >
      <rect width="480" height="290" rx="20" fill="#1f2024" />
      {/* quadras */}
      <rect x="104" y="94" width="88" height="56" rx="6" fill="#26332a" />
      <g fill="#2b2c31">
        <rect x="214" y="94" width="92" height="56" rx="6" />
        <rect x="334" y="94" width="62" height="56" rx="6" />
        <rect x="24" y="94" width="52" height="56" rx="6" />
        <rect x="24" y="174" width="52" height="54" rx="6" />
        <rect x="104" y="174" width="82" height="54" rx="6" />
        <rect x="214" y="174" width="92" height="54" rx="6" />
        <rect x="334" y="174" width="110" height="54" rx="6" />
        <rect x="334" y="14" width="60" height="52" rx="6" />
        <rect x="214" y="14" width="92" height="52" rx="6" />
      </g>
      {/* ruas */}
      <g stroke="#34353b" strokeWidth="14" fill="none" strokeLinecap="round">
        <path d="M0 80 H480 M0 160 H480 M0 240 H480" />
        <path d="M90 0 V290 M200 0 V290 M320 0 V290 M410 0 V290" />
      </g>

      {/* rota */}
      <path d={ROUTE} fill="none" stroke={LIME} strokeOpacity="0.3" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" strokeDasharray="1 9" />
      <motion.path
        d={ROUTE}
        fill="none"
        stroke={LIME}
        strokeWidth="4"
        strokeLinecap="round"
        strokeLinejoin="round"
        initial={{ pathLength: 0 }}
        animate={{ pathLength: 1 }}
        transition={{ duration: 1.6, ease: 'easeInOut', delay: 0.4 }}
      />

      {/* origem */}
      <circle cx="90" cy="240" r="6" fill="#1f2024" stroke={LIME} strokeWidth="3" />

      {/* ponto de recarga */}
      <g transform="translate(232 196)">
        <rect x="-15" y="-15" width="30" height="30" rx="8" fill="#85d73e" />
        <path d="M1 -9 L-6 2 H-1 L-2 9 L6 -2 H1 Z" fill="#1f2024" />
      </g>

      {/* destino */}
      <ellipse cx="410" cy="82" rx="10" ry="3.5" fill="#000" opacity="0.4" />
      <path d="M410 80 C400 70 396 64 396 58 A14 14 0 0 1 424 58 C424 64 420 70 410 80 Z" fill={LIME} />
      <circle cx="410" cy="58" r="5" fill="#1f2024" />

      {/* veículo em movimento */}
      <g>
        <animateMotion dur="7s" begin="1.6s" repeatCount="indefinite" path={ROUTE} rotate="auto" calcMode="linear" />
        <circle r="14" fill={LIME} opacity="0.2">
          <animate attributeName="r" values="12;26" dur="1.8s" repeatCount="indefinite" />
          <animate attributeName="opacity" values="0.35;0" dur="1.8s" repeatCount="indefinite" />
        </circle>
        <rect x="-15" y="-8" width="30" height="16" rx="6" fill="#fafafa" />
        <rect x="-3" y="-6" width="9" height="12" rx="2.5" fill="#272727" />
        <rect x="7" y="-5" width="5" height="3.5" rx="1" fill={LIME} />
        <rect x="7" y="1.5" width="5" height="3.5" rx="1" fill={LIME} />
      </g>

      {/* telemetria */}
      <g transform="translate(22 20)">
        <rect width="156" height="56" rx="12" fill="#24252a" stroke="#3a3b41" />
        <circle cx="18" cy="19" r="4" fill={LIME}>
          <animate attributeName="opacity" values="1;0.25;1" dur="1.8s" repeatCount="indefinite" />
        </circle>
        <text x="30" y="23" fontSize="12" fontWeight="600" fill="#fafafa">Em movimento</text>
        <text x="14" y="43" fontSize="11" fill="#a1a1aa">62 km/h</text>
        <rect x="68" y="35" width="36" height="9" rx="3" fill="none" stroke="#a1a1aa" />
        <rect x="70" y="37" width="24" height="5" rx="1.5" fill={LIME} />
        <text x="110" y="43" fontSize="11" fill="#a1a1aa">78%</text>
      </g>
    </motion.svg>
  );
}

function Brand() {
  return (
    <motion.div {...fade(0)} className="mb-7 flex items-center justify-center gap-2.5">
      <span className="grid size-9 place-items-center rounded-lg bg-brand-neon text-app-background">
        <MapPin className="size-5" strokeWidth={2.5} />
      </span>
      <span className="text-xl font-bold tracking-tight">
        Localize<span className="text-brand-neon">-C</span>
      </span>
    </motion.div>
  );
}

function CopyButton({ text }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      const t = document.createElement('textarea');
      t.value = text;
      document.body.appendChild(t);
      t.select();
      document.execCommand('copy');
      t.remove();
    }
    setCopied(true);
    setTimeout(() => setCopied(false), 1600);
  };
  return (
    <button
      type="button"
      onClick={copy}
      className="inline-flex shrink-0 cursor-pointer items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-app-muted transition-colors hover:bg-app-surface-muted hover:text-brand-neon"
    >
      {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
      {copied ? 'Copiado' : 'Copiar'}
    </button>
  );
}

export default function App() {
  const target = useMemo(
    () => BASE_URL + window.location.pathname + window.location.search + window.location.hash,
    [],
  );
  const [left, setLeft] = useState(SECONDS);
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.getRegistrations()
        .then((regs) => regs.forEach((r) => r.unregister()))
        .catch(() => {});
    }
  }, []);

  useEffect(() => {
    if (paused) return undefined;
    if (left <= 0) {
      window.location.replace(target);
      return undefined;
    }
    const id = setTimeout(() => setLeft((n) => n - 1), 1000);
    return () => clearTimeout(id);
  }, [left, paused, target]);

  return (
    <div className="flex min-h-full flex-col items-center justify-center px-5 py-10">
      <main className="w-full min-w-0 max-w-md text-center">
        <Brand />
        <FleetMap />

        <motion.h1 {...fade(0.1)} className="mb-3 text-2xl font-semibold tracking-tight text-balance sm:text-[28px]">
          A Localize-C mudou de endereço
        </motion.h1>

        <motion.p {...fade(0.18)} className="mx-auto mb-7 max-w-sm text-[15px] leading-relaxed text-app-muted">
          A interface de rastreamento da frota agora roda em um serviço próprio. Seus veículos seguem online e o login continua o mesmo.
        </motion.p>

        <motion.div
          {...fade(0.26)}
          className="mb-6 flex min-w-0 items-center gap-2 rounded-lg border border-app-border bg-app-surface py-1.5 pr-1.5 pl-3 text-left font-mono text-[13px] text-app-foreground"
        >
          <span className="min-w-0 flex-1 truncate">{target}</span>
          <CopyButton text={`${BASE_URL}/`} />
        </motion.div>

        <motion.div {...fade(0.34)}>
          <a
            href={target}
            className="group inline-flex items-center gap-2 rounded-md bg-brand-lime px-5 py-3 text-[15px] font-bold text-app-background transition-colors hover:bg-brand-lime-hover"
          >
            Ir para o novo endereço
            <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" />
          </a>

          <p className="mt-4 h-5 text-[13px] text-app-muted">
            <AnimatePresence mode="wait" initial={false}>
              {paused ? (
                <motion.span key="p" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                  Redirecionamento cancelado.
                </motion.span>
              ) : (
                <motion.span key="r" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
                  Redirecionando em {left}s.{' '}
                  <button
                    type="button"
                    onClick={() => setPaused(true)}
                    className="cursor-pointer underline underline-offset-2 hover:text-brand-neon"
                  >
                    Cancelar
                  </button>
                </motion.span>
              )}
            </AnimatePresence>
          </p>
        </motion.div>

      </main>
    </div>
  );
}
