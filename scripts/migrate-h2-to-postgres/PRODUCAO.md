# Virada H2 -> Postgres em producao (EasyPanel)

Premissas: projeto EasyPanel `traccar`, volume real do H2 `traccar_traccar_traccar-data`,
Postgres como novo servico no proprio EasyPanel. Nomes marcados com (confirmar) devem ser checados na VPS.

O H2 nunca e alterado: o volume continua intacto e serve de rollback.

## A. Preparacao (sem parada)

1. **EasyPanel:** criar servico Postgres no projeto `traccar` (nome `postgres`, versao 17), senha forte,
   banco `traccar`. Host interno esperado: `traccar_postgres` (confirmar na aba do servico).
2. **Copiar o script para a VPS** (da sua maquina):
   ```bash
   scp scripts/migrate-h2-to-postgres/migrate.sh lucas.steffen@IP_DA_VPS:~/migrate.sh
   ```
3. **Na VPS**, descobrir imagem e rede (confirmar):
   ```bash
   sudo docker ps --filter name=traccar --format '{{.Names}}  {{.Image}}'
   sudo docker network ls
   ```
   Anote a imagem do servico do Traccar server e a rede (esperado: `easypanel`).
4. **Senha na sessao** (nao fica no historico):
   ```bash
   read -rs PG_PASSWORD; export PG_PASSWORD
   ```
5. **Testar conexao** ao Postgres:
   ```bash
   sudo docker run --rm --network easypanel -e PGPASSWORD="$PG_PASSWORD" postgres:17-alpine psql -h traccar_postgres -U postgres -d traccar -c 'select 1'
   ```
   Se falhar por rede, habilite temporariamente a porta externa do Postgres no EasyPanel e use o IP da VPS como `PG_HOST`.
6. **Criar o schema sem tocar na producao**: subir um container temporario com a imagem do server apontando
   para o Postgres, so para o Liquibase criar as tabelas.
   ```bash
   IMAGE=<imagem anotada>
   sudo docker run -d --name traccar-schema --network easypanel \
     -e CONFIG_USE_ENVIRONMENT_VARIABLES=true -e DATABASE_DRIVER=org.postgresql.Driver \
     -e DATABASE_URL=jdbc:postgresql://traccar_postgres:5432/traccar \
     -e DATABASE_USER=postgres -e DATABASE_PASSWORD="$PG_PASSWORD" "$IMAGE"
   sleep 40; sudo docker logs traccar-schema 2>&1 | tail -5
   sudo docker rm -f traccar-schema
   ```
   Esse container nao publica portas, entao nenhum rastreador conecta nele.

## B. Janela de parada

1. **EasyPanel:** parar o servico do Traccar server (Stop).
2. **Migrar** (na VPS):
   ```bash
   sudo env DATA_ONLY=1 CONFIRM_TRUNCATE=yes H2_VOLUME=traccar_traccar_traccar-data \
     IMAGE="$IMAGE" DOCKER_NETWORK=easypanel PG_HOST=traccar_postgres PG_PASSWORD="$PG_PASSWORD" \
     bash ~/migrate.sh
   ```
   O script faz backup do volume, exporta o H2 (somente leitura), importa, acerta sequences e compara
   contagens. So siga se terminar com `OK: todas as tabelas conferem.`
3. **EasyPanel:** no servico do Traccar server, aba Environment, adicionar:
   ```
   CONFIG_USE_ENVIRONMENT_VARIABLES=true
   DATABASE_DRIVER=org.postgresql.Driver
   DATABASE_URL=jdbc:postgresql://traccar_postgres:5432/traccar
   DATABASE_USER=postgres
   DATABASE_PASSWORD=<senha>
   ```
   Salvar e iniciar (Deploy/Start).
4. **Validar**
   - Logs do servico sem erro de banco.
   - Login no traccar-web, lista com os devices.
   - Posicoes novas chegando (a hora deve avancar):
     ```bash
     sudo docker run --rm --network easypanel -e PGPASSWORD="$PG_PASSWORD" postgres:17-alpine psql -h traccar_postgres -U postgres -d traccar -c 'select max(fixtime) from tc_positions'
     ```

## C. Rollback

Remover as 5 variaveis `DATABASE_*`/`CONFIG_*` do servico e reiniciar. Sem elas o Traccar volta ao H2 do
`traccar.xml`, com o volume exatamente como estava no momento da parada. Posicoes gravadas no Postgres
depois da virada nao voltam para o H2.

## D. Depois

- Manter o volume H2 e o backup por alguns dias.
- Apagar a pasta `work/` criada ao lado do `migrate.sh` na VPS (tem CSVs com todos os dados) e o `.tgz`.
- Configurar backup periodico do Postgres no EasyPanel.
