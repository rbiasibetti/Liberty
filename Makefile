.PHONY: setup verify format mutation local-up local-down local-reset migrate-core

setup:
	git config core.hooksPath .githooks

verify:
	set -a; [ -f .env.local ] && . ./.env.local; set +a; cd backend && ./mvnw -ntp verify
	cd frontend && npm ci && npm run verify

format:
	cd backend && ./mvnw -ntp spotless:apply
	cd frontend && npm run format

mutation:
	cd backend && ./mvnw -ntp -Pmutation verify

local-up:
	scripts/local-up.sh

local-down:
	scripts/local-down.sh

local-reset: local-down local-up

# Aplica as migrações no liberty_core local (usuário migrador criado por scripts/seed-sql.sh)
migrate-core:
	set -a; . ./.env.local; set +a; cd backend && ./mvnw -ntp -q -pl liberty-core-migrations flyway:migrate \
	  "-Dflyway.url=jdbc:sqlserver://$$LIBERTY_TEST_DB_HOST:$$LIBERTY_TEST_DB_PORT;databaseName=liberty_core;encrypt=true;trustServerCertificate=true" \
	  -Dflyway.user=liberty_core_migrator "-Dflyway.password=$$LIBERTY_CORE_MIGRATOR_PASSWORD"
