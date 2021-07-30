# Makefile

MAKEFLAGS += -j2
-include .env
export

CURRENT_BRANCH := $(shell git rev-parse --abbrev-ref HEAD)
CURRENT_PATH := $(shell pwd)
AMM := ${HOME}/amm

.PHONY: gitRebase
gitRebase:
	git checkout develop && \
		git pull upstream develop && \
		git push origin develop && \
		git checkout $(CURRENT_BRANCH) && \
		git rebase develop

.PHONY: killJava
killJava:
	ps ax | grep java | grep -v 'grep' | cut -d '?' -f1 | xargs kill -9

.PHONY: dependencyTree
dependencyTree:
	sbt dependencyBrowseTree

.PHONY: amm
amm:
ifeq ("$(wildcard $(AMM))", "")
	@echo "Installing ammonite $(AMM)"
	sudo sh -c '(echo "#!/usr/bin/env sh" && \
		curl -L https://github.com/lihaoyi/Ammonite/releases/download/2.3.8/2.13-2.3.8) \
		> $(AMM) && \
		chmod +x $(AMM)'
endif
	$(AMM)

.PHONY: prepare
prepare:
	sbt prepare

.PHONY: startDocker
startDocker:
	docker-compose -f docker-compose.yml up -d

.PHONY: header
header:
	sbt headerCreate

.PHONY: testMockCreate
testMockCreate:
	curl -i --header "Content-Type: application/json" \
		--request POST \
		--data '{ "name": "Roger", "age": 21 }' \
		http://localhost:8080/mocks