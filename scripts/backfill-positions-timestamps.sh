#!/usr/bin/env bash
# Preenche createdat e updatedat de tc_positions (linhas anteriores ao changelog-6.19.0) com servertime.
#
# Roda FORA do Liquibase, em lotes por faixa de id, com um commit por lote (cada chamada do psql e uma
# transacao propria), para nao segurar lock longo nem gerar uma transacao gigante. E idempotente: so
# atualiza linhas com createdat IS NULL, entao pode ser interrompido e reexecutado.
#
# Uso:  PGHOST=... PGPORT=5432 PGUSER=... PGPASSWORD=... PGDATABASE=traccar \
#         bash scripts/backfill-positions-timestamps.sh
#
# Variaveis opcionais:
#   BATCH_SIZE  tamanho da faixa de ids por lote (padrao 50000)
#   SLEEP       pausa em segundos entre lotes, para aliviar o banco (padrao 0)
#
# Pre-requisito: changelog-6.19.0 aplicado (colunas createdat/updatedat existem e aceitam nulo).

set -euo pipefail

BATCH_SIZE="${BATCH_SIZE:-50000}"
SLEEP="${SLEEP:-0}"

psql_q() {
  psql -v ON_ERROR_STOP=1 -At -c "$1"
}

MIN_ID="$(psql_q "SELECT COALESCE(MIN(id), 0) FROM tc_positions WHERE createdat IS NULL")"
MAX_ID="$(psql_q "SELECT COALESCE(MAX(id), 0) FROM tc_positions WHERE createdat IS NULL")"

if [ "$MAX_ID" = "0" ]; then
  echo "Nada para preencher."
  exit 0
fi

echo "Faixa de ids a preencher: $MIN_ID .. $MAX_ID (lotes de $BATCH_SIZE)"

START="$MIN_ID"
TOTAL=0
while [ "$START" -le "$MAX_ID" ]; do
  END=$((START + BATCH_SIZE - 1))
  # servertime e NOT NULL em tc_positions; COALESCE e apenas defesa contra dados legados.
  COUNT="$(psql -v ON_ERROR_STOP=1 -At -c "
    WITH updated AS (
      UPDATE tc_positions
         SET createdat = COALESCE(servertime, now()),
             updatedat = COALESCE(servertime, now())
       WHERE id BETWEEN $START AND $END
         AND createdat IS NULL
      RETURNING 1
    )
    SELECT COUNT(*) FROM updated")"
  TOTAL=$((TOTAL + COUNT))
  echo "ids $START..$END: $COUNT linhas (acumulado: $TOTAL)"
  START=$((END + 1))
  if [ "$SLEEP" != "0" ]; then
    sleep "$SLEEP"
  fi
done

echo "Concluido: $TOTAL linhas atualizadas."
