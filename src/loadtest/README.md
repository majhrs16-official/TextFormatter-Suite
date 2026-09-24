# TextFormatter Suite - Load Testing Framework

## Overview

The load testing framework provides comprehensive performance testing capabilities for the TextFormatter Suite using:
- **JMH** (Java Microbenchmark Harness) for microbenchmarks
- **Gatling** for load testing with realistic scenarios
- Integration tests for end-to-end validation

## Components

### JMH Benchmarks (`MessageProcessingBenchmark.java`)
Microbenchmarks for core components:
- Template rendering (simple, complex, with translation)
- MiniMessage escaping
- iFlow routing (simple, permissions, rate limiting, complex rules)
- Rate limiter performance
- Message dispatcher (single/multiple recipients)
- Config loading (YAML parsing)
- Concurrent access patterns

### Gatling Load Tests (`SuiteLoadTestSimulation.scala`)
Load test scenarios:
- **Health checks** - Constant 10 users
- **Metrics endpoint** - 5 users constant
- **Message simulation** - Ramp up to target users
- **Debug dump** - Periodic state dumps
- **WebSocket connections** - Chat, events, sync, logs
- **Stress tests** - High concurrency bursts
- **WebSocket stress** - Sustained WebSocket connections

## Running Benchmarks

### JMH Microbenchmarks
```bash
cd suite/loadtest
./gradlew jmh
```

### Gatling Load Tests
```bash
# Set environment variables
export SUITE_BASE_URL=http://localhost:9090
export SUITE_DEBUG_URL=http://localhost:9091
export SUITE_WS_URL=ws://localhost:9092
export LOAD_USERS=100
export LOAD_DURATION=60
export RAMP_UP=10

cd suite/loadtest
./gradlew loadTest
```

### Integration Tests
```bash
./gradlew :suite:loadtest:test --tests "me.majhrs16.suite.loadtest.integration.SuiteIntegrationTest"
```

## Configuration

Environment variables:
| Variable | Default | Description |
|----------|---------|-------------|
| `SUITE_BASE_URL` | `http://localhost:9090` | Metrics/health base URL |
| `SUITE_DEBUG_URL` | `http://localhost:9091` | Debug endpoint base URL |
| `SUITE_WS_URL` | `ws://localhost:9092` | WebSocket server URL |
| `LOAD_USERS` | `100` | Target concurrent users |
| `LOAD_DURATION` | `60` | Test duration in seconds |
| `RAMP_UP` | `10` | Ramp-up duration in seconds |

## Benchmark Outputs

### JMH Results
- Average time per operation (microseconds/nanoseconds)
- Throughput (ops/second)
- Allocation rate
- GC impact

### Gatling Reports
HTML report generated in `build/reports/gatling/`
- Response time percentiles (p50, p95, p99)
- Throughput (requests/sec)
- Error rates
- Active users over time

## Running All Tests

```bash
# Run all load tests
./gradlew :suite:loadtest:test :suite:loadtest:jmh :suite:loadtest:loadTest

# Or run specific test types
./gradlew :suite:loadtest:test --tests "*Benchmark"
./gradlew :suite:loadtest:jmh
./gradlew :suite:loadtest:loadTest
```

## CI/CD Integration

Add to your CI pipeline:
```yaml
# GitHub Actions example
- name: Run Load Tests
  run: |
    export SUITE_BASE_URL=http://localhost:9090
    export SUITE_DEBUG_URL=http://localhost:9091
    export LOAD_USERS=50
    export LOAD_DURATION=120
    ./gradlew :suite:loadtest:loadTest
```

## Benchmark Targets (SLA)

| Component | Target (p99) | Target (Throughput) |
|-----------|-------------|---------------------|
| Template Rendering | < 5ms | > 1000 ops/sec |
| MiniMessage Escape | < 1µs | > 1M ops/sec |
| iFlow Routing | < 2ms | > 5000 ops/sec |
| Rate Limiter | < 10µs | > 100K ops/sec |
| Message Dispatch | < 5ms | > 2000 ops/sec |
| Config Load | < 10ms | > 100 ops/sec |

## Continuous Benchmarking

Results are stored in `build/reports/benchmarks/` and can be tracked over time to detect performance regressions.