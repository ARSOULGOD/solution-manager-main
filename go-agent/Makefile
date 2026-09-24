.PHONY: proto build test test-integration test-docker fake-gateway

proto:
	buf generate

build:
	go build -o bin/sm-agent ./cmd/sm-agent
	go build -o bin/fake-gateway ./cmd/fake-gateway

test:
	go vet ./...
	go test ./...

test-integration:
	go test -tags integration ./internal/agent/ -run Integration -v

test-docker:
	go test -tags docker ./internal/runtime/docker/ -run E2E -v

fake-gateway: build
	./bin/fake-gateway --listen 127.0.0.1:8443 --scenario internal/fakegw/scenarios/demo.yaml --enroll-token demo-token --ca-out bin/ca.pem
