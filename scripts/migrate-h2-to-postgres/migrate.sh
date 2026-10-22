#!/usr/bin/env bash
# Migra os dados do H2 (volume docker traccar-data) para o Postgres, preservando ids.
#
# Rodar no Git Bash, a partir de qualquer pasta:  bash scripts/migrate-h2-to-postgres/migrate.sh
#
# Etapas:
#   1. para o traccar-server e faz backup do volume do H2 (tgz em work/)
#   2. sobe o server contra o Postgres vazio so para o Liquibase criar o schema, e para de novo
#   3. exporta cada tabela tc_* do H2 (somente leitura) para CSV
#   4. limpa as tabelas tc_* do Postgres, importa os CSVs e acerta as sequences de id
#   5. compara a contagem de linhas H2 x Postgres (falha se divergir)
#
# O H2 nunca e alterado. ATENCAO: o passo 4 faz TRUNCATE nas tabelas tc_* do Postgres de destino.

set -euo pipefail
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_ONLY="${DATA_ONLY:-0}"
[ "$DATA_ONLY" = "1" ] || cd "$SCRIPT_DIR/../.."
WORK="$(cd "$SCRIPT_DIR" && (pwd -W 2>/dev/null || pwd))/work"
mkdir -p "$WORK/csv"

# Modo DATA_ONLY=1 (producao / VPS): nao usa docker compose. O schema ja deve existir no Postgres
# (criado pelo proprio traccar-server na primeira subida) e o traccar-server deve estar PARADO. Exige:
#   IMAGE=<imagem do traccar-server>  H2_VOLUME=<volume real>  PG_HOST/PG_PASSWORD/...
#   DOCKER_NETWORK=<rede do EasyPanel>  CONFIRM_TRUNCATE=yes
IMAGE="${IMAGE:-}"
NETARGS=()
[ -z "${DOCKER_NETWORK:-}" ] || NETARGS=(--network "$DOCKER_NETWORK")
H2_VOLUME="${H2_VOLUME:-traccar-data}"
PG_HOST="${PG_HOST:-host.docker.internal}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
PG_DB="${PG_DB:-traccar}"
PSQL_IMAGE="${PSQL_IMAGE:-postgres:17-alpine}"

COMPOSE=(docker compose -f docker-compose.local.yml -f docker-compose.postgres.yml)

psql_run() {
  docker run --rm -i ${NETARGS[@]+"${NETARGS[@]}"} -e PGPASSWORD="$PG_PASSWORD" -v "$WORK:/work" "$PSQL_IMAGE" \
    psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 "$@"
}

pg_tables() {
  psql_run -At -c "select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name ~ '^tc_' order by 1"
}

echo "==> [1/5] Parando traccar-server e fazendo backup do volume $H2_VOLUME"
docker volume inspect "$H2_VOLUME" >/dev/null
[ "$DATA_ONLY" = "1" ] || "${COMPOSE[@]}" stop traccar-server
if [ -n "$(docker ps -q --filter "volume=$H2_VOLUME")" ]; then
  echo "ERRO: ha container em execucao usando o volume $H2_VOLUME. Pare o traccar-server antes (o H2 precisa estar sem uso)."
  exit 1
fi
BACKUP="backup-h2-$(date +%Y%m%d-%H%M%S).tgz"
docker run --rm -v "$H2_VOLUME:/data:ro" -v "$WORK:/work" alpine tar czf "/work/$BACKUP" -C /data .
echo "    backup: $WORK/$BACKUP"

if [ "$DATA_ONLY" = "1" ]; then
  echo "==> [2/5] Conferindo schema existente no Postgres ($PG_HOST/$PG_DB)"
  [ "$(psql_run -At -c "select to_regclass('public.tc_devices') is not null")" = "t" ] || {
    echo "ERRO: schema nao existe no Postgres. Suba o traccar-server uma vez com DATABASE_* para o Liquibase criar, e pare de novo."
    exit 1
  }
  [ -n "$IMAGE" ] || { echo "ERRO: defina IMAGE=<imagem do traccar-server>"; exit 1; }
  [ "${CONFIRM_TRUNCATE:-}" = "yes" ] || {
    echo "ERRO: este passo da TRUNCATE nas tabelas tc_* de $PG_HOST/$PG_DB. Rode com CONFIRM_TRUNCATE=yes para confirmar."
    exit 1
  }
else
  echo "==> [2/5] Criando schema no Postgres via Liquibase"
  "${COMPOSE[@]}" up -d --build traccar-server
  for i in $(seq 1 90); do
    if [ "$(psql_run -At -c "select to_regclass('public.tc_devices') is not null" 2>/dev/null || true)" = "t" ]; then
      break
    fi
    [ "$i" -eq 90 ] && { echo "Schema nao foi criado em 3 min. Veja: docker logs traccar-server-local"; exit 1; }
    sleep 2
  done
  sleep 3
  "${COMPOSE[@]}" stop traccar-server
fi

TABLES=$(pg_tables | tr -d '\r')
[ -n "$TABLES" ] || { echo "Nenhuma tabela tc_* no Postgres"; exit 1; }

echo "==> [3/5] Exportando H2 para CSV (somente leitura)"
[ -n "$IMAGE" ] || IMAGE=$("${COMPOSE[@]}" config --images | grep traccar-server | head -1)
rm -f "$WORK"/csv/*.csv "$WORK/export.sql" "$WORK/h2_counts.csv"
# Colunas binarias (bytea) saem em hex ('\x...'), que o COPY do Postgres aceita; o H2 gravaria bytes crus no CSV.
BIN_TABLES=$(psql_run -At -c "select distinct table_name from information_schema.columns where table_schema='public' and data_type='bytea' and table_name ~ '^tc_'" | tr -d '\r')
export_select() {
  local t="$1"
  if ! echo "$BIN_TABLES" | grep -qx "$t"; then
    echo "SELECT * FROM $t"; return
  fi
  local list="" col type
  while IFS='|' read -r col type; do
    [ -z "$col" ] && continue
    [ -n "$list" ] && list="$list, "
    if [ "$type" = "bytea" ]; then
      list="${list}''\\x'' || RAWTOHEX($col) AS $col"
    else
      list="${list}$col"
    fi
  done < <(psql_run -At -F'|' -c "select column_name, data_type from information_schema.columns where table_schema='public' and table_name='$t' order by ordinal_position" | tr -d '\r')
  echo "SELECT $list FROM $t"
}
{
  for t in $TABLES; do
    echo "CALL CSVWRITE('/work/csv/$t.csv', '$(export_select "$t")', 'charset=UTF-8 nullString=~NULL~');"
  done
  Q=""
  for t in $TABLES; do
    [ -n "$Q" ] && Q="$Q UNION ALL "
    Q="${Q}SELECT ''$t'' AS t, COUNT(*) AS c FROM $t"
  done
  echo "CALL CSVWRITE('/work/h2_counts.csv', '$Q', 'charset=UTF-8');"
} > "$WORK/export.sql"
docker run --rm --entrypoint sh -v "$H2_VOLUME:/data" -v "$WORK:/work" "$IMAGE" -c \
  'H2=$(ls /opt/traccar/lib/h2-*.jar | head -1); /opt/traccar/jre/bin/java -cp "$H2" org.h2.tools.RunScript -url "jdbc:h2:/data/database;ACCESS_MODE_DATA=r;IFEXISTS=TRUE" -user sa -script /work/export.sql'

echo "==> [4/5] Importando no Postgres"
{
  echo "BEGIN;"
  echo "SET session_replication_role = replica;"
  echo "TRUNCATE $(echo $TABLES | sed 's/ /, /g');"
  for t in $TABLES; do
    cols=$(head -1 "$WORK/csv/$t.csv" | tr -d '\r"' | tr 'A-Z' 'a-z' | sed 's/,/","/g; s/^/"/; s/$/"/')
    echo "\\copy \"$t\" ($cols) FROM '/work/csv/$t.csv' WITH (FORMAT csv, HEADER true, NULL '~NULL~', ENCODING 'UTF8')"
  done
  cat <<'EOF'
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT table_name AS t FROM information_schema.columns
           WHERE table_schema = 'public' AND column_name = 'id' AND table_name ~ '^tc_' LOOP
    IF pg_get_serial_sequence(quote_ident(r.t), 'id') IS NOT NULL THEN
      EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), GREATEST(COALESCE((SELECT MAX(id) FROM %I), 0), 1), (SELECT MAX(id) FROM %I) IS NOT NULL)', r.t, r.t, r.t);
    END IF;
  END LOOP;
END $$;
COMMIT;
EOF
} > "$WORK/load.sql"
psql_run -f /work/load.sql

echo "==> [5/5] Comparando contagens H2 x Postgres"
Q=""
for t in $TABLES; do
  [ -n "$Q" ] && Q="$Q UNION ALL "
  Q="${Q}SELECT '$t' AS t, COUNT(*) AS c FROM $t"
done
psql_run -At -F, -c "$Q" | tr -d '\r' | sort > "$WORK/pg_counts.txt"
tail -n +2 "$WORK/h2_counts.csv" | tr -d '\r"' | sort > "$WORK/h2_counts.txt"
if diff "$WORK/h2_counts.txt" "$WORK/pg_counts.txt"; then
  echo "OK: todas as tabelas conferem."
  awk -F, '$2>0 {printf "  %-28s %s\n", $1, $2}' "$WORK/pg_counts.txt"
else
  echo "ERRO: contagens divergem (H2 < vs > Postgres)."; exit 1
fi

echo
if [ "$DATA_ONLY" = "1" ]; then
  echo "Pronto. Inicie o traccar-server (apontando para o Postgres) e confira login, devices e posicoes."
else
  echo "Pronto. Suba tudo:  ${COMPOSE[*]} up -d --build"
fi
