.PHONY: setup verify format mutation local-up local-down local-reset

setup:
	git config core.hooksPath .githooks

verify:
	cd backend && ./mvnw -ntp verify
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
