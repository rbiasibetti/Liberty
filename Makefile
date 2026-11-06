.PHONY: setup verify format mutation

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
