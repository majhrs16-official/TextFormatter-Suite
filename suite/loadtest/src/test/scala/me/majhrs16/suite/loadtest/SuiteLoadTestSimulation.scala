package me.majhrs16.suite.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import io.gatling.core.structure.ScenarioBuilder
import scala.concurrent.duration._
import scala.util.Random

/**
 * Gatling load test simulation for TextFormatter Suite.
 * 
 * Tests the following endpoints:
 * - /metrics (Prometheus metrics)
 * - /health (Health check)
 * - /debug/simulate (Message simulation)
 * - /debug/dump (State dump)
 * - WebSocket connections
 */
class SuiteLoadTestSimulation extends Simulation {

  // Base configuration
  private val baseUrl = sys.env.getOrElse("SUITE_BASE_URL", "http://localhost:9090")
  private val debugBaseUrl = sys.env.getOrElse("SUITE_DEBUG_URL", "http://localhost:9091")
  private val wsUrl = sys.env.getOrElse("SUITE_WS_URL", "ws://localhost:9092")

  // Test parameters
  private val users = sys.env.getOrElse("LOAD_USERS", "100").toInt
  private val duration = sys.env.getOrElse("LOAD_DURATION", "60").toInt.seconds
  private val rampUp = sys.env.getOrElse("RAMP_UP", "10").toInt.seconds

  // HTTP protocol configuration
  private val httpProtocol = http
    .baseUrl(baseUrl)
    .acceptHeader("application/json")
    .userAgentHeader("TextFormatterSuite-LoadTest/1.0")
    .shareConnections

  private val debugProtocol = http
    .baseUrl(debugBaseUrl)
    .acceptHeader("application/json")
    .userAgentHeader("TextFormatterSuite-LoadTest/1.0")
    .shareConnections

  // ============================================================
  // Feeders
  // ============================================================

  private val messageFeeder = Iterator.continually(Map(
    "content" -> s"Test message ${Random.nextInt(10000)}",
    "sender" -> s"Player${Random.nextInt(1000)}",
    "channel" -> s"chat.global",
    "type" -> "CHAT"
  ))

  private val healthCheckFeeder = Iterator.continually(Map(
    "endpoint" -> "/health"
  ))

  private val simulateFeeder = Iterator.continually(Map(
    "type" -> "CHAT",
    "channel" -> "chat.global",
    "sender" -> s"TestPlayer${Random.nextInt(1000)}",
    "content" -> s"Load test message ${Random.nextInt(10000)}",
    "sourceLang" -> "auto",
    "targetLang" -> "en",
    "direction" -> "OTHERS"
  ))

  // ============================================================
  // Scenarios
  // ============================================================

  // Health check scenario - constant load
  private val healthCheckScenario: ScenarioBuilder = scenario("Health Check")
    .feed(healthCheckFeeder)
    .exec(
      http("Health Check")
        .get("/health")
        .check(status.is(200))
        .check(jsonPath("$.status").is("UP"))
    )
    .pause(1.second, 3.seconds)

  // Metrics endpoint scenario
  private val metricsScenario: ScenarioBuilder = scenario("Metrics Endpoint")
    .exec(
      http("Metrics")
        .get("/metrics")
        .check(status.is(200))
        .check(regex("textformatter_messages_total"))
    )
    .pause(5.seconds, 10.seconds)

  // Debug simulate scenario
  private val simulateScenario: ScenarioBuilder = scenario("Message Simulation")
    .feed(simulateFeeder)
    .exec(
      http("Simulate Message")
        .post("/debug/simulate")
        .header("Content-Type", "application/json")
        .body(StringBody(
          """{
            "type": "${type}",
            "channel": "${channel}",
            "sender": "${sender}",
            "content": "${content}",
            "sourceLang": "${sourceLang}",
            "targetLang": "${targetLang}",
            "direction": "${direction}"
          }"""
        )).asJson
        .check(status.is(200))
        .check(jsonPath("$.considered").exists)
    )
    .pause(100.millis, 500.millis)

  // Debug dump scenario
  private val dumpScenario: ScenarioBuilder = scenario("Debug Dump")
    .exec(
      http("Debug Dump")
        .get("/debug/dump")
        .check(status.is(200))
        .check(jsonPath("$.host").exists)
    )
    .pause(10.seconds, 30.seconds)

  // WebSocket scenario
  private val wsScenario: ScenarioBuilder = scenario("WebSocket Chat")
    .exec(
      ws("Connect WebSocket")
        .open(wsUrl + "/ws/chat?token=test-token")
        .onConnected { session =>
          session.set("ws", _)
        }
    )
    .pause(1.second)
    .exec(
      ws("Subscribe to chat")
        .sendText("""{"action": "subscribe", "path": "/ws/chat"}""")
        .await(5.seconds)(
          ws.checkTextMessage("Subscription confirmed")
            .check(jsonPath("$.type").is("subscribed"))
        )
    )
    .pause(1.second)
    .repeat(10) {
      exec(
        ws("Send chat message")
          .sendText("""{"action": "message", "content": "Load test message ${userId}", "channel": "chat.global"}""")
          .pause(100.millis, 1.second)
      )
    }
    .exec(
      ws("Close WebSocket")
        .close
    )

  // Stress test scenario - high concurrency
  private val stressScenario: ScenarioBuilder = scenario("Stress Test")
    .feed(messageFeeder)
    .exec(
      http("Stress Message")
        .post("/debug/simulate")
        .header("Content-Type", "application/json")
        .body(StringBody(
          """{
            "type": "CHAT",
            "channel": "chat.global",
            "sender": "${sender}",
            "content": "${content}",
            "sourceLang": "auto",
            "targetLang": "en",
            "direction": "OTHERS"
          }"""
        )).asJson
        .check(status.is(200))
    )
    .pause(10.millis, 50.millis)

  // WebSocket stress
  private val wsStressScenario: ScenarioBuilder = scenario("WebSocket Stress")
    .exec(
      ws("WS Connect")
        .open(wsUrl + "/ws/chat?token=test-token")
    )
    .pause(1.second)
    .repeat(50) {
      exec(
        ws("Send message")
          .sendText("""{"action": "message", "content": "Stress test ${userId}", "channel": "chat.global"}""")
          .pause(50.millis, 200.millis)
      )
    }
    .exec(
      ws("Close")
        .close
    )

  // ============================================================
  // Simulation Setup
  // ============================================================

  setUp(
    // Baseline health checks - constant 10 users
    healthCheckScenario.inject(
      constantUsersPerSec(10) during (duration)
    ).protocols(httpProtocol),

    // Metrics - 5 users constant
    metricsScenario.inject(
      constantUsersPerSec(5) during (duration)
    ).protocols(httpProtocol),

    // Normal load - ramp up to target users
    simulateScenario.inject(
      rampUsersPerSec(1) to (users / 2) during (rampUp),
      constantUsersPerSec(users / 2) during (duration - rampUp)
    ).protocols(debugProtocol),

    // Debug dump - periodic
    dumpScenario.inject(
      constantUsersPerSec(1) during (duration)
    ).protocols(debugProtocol),

    // WebSocket - moderate load
    wsScenario.inject(
      rampUsers(10) during (rampUp),
      constantUsersPerSec(10) during (duration - rampUp)
    ).protocols(debugProtocol),

    // Stress test - runs for shorter duration at higher intensity
    stressScenario.inject(
      nothingFor(30.seconds),
      rampUsersPerSec(5) to (users * 2) during (30.seconds),
      constantUsersPerSec(users * 2) during (30.seconds)
    ).protocols(debugProtocol),

    // WebSocket stress
    wsStressScenario.inject(
      nothingFor(60.seconds),
      rampUsers(5) to (users / 5) during (30.seconds),
      constantUsersPerSec(users / 5) during (30.seconds)
    ).protocols(debugProtocol)
  ).maxDuration(duration + 2.minutes)
    .assertions(
      global.responseTime.max.lt(5000),
      global.responseTime.mean.lt(500),
      global.responseTime.percentile3.lt(1000),
      global.responseTime.percentile4.lt(2000),
      global.successfulRequests.percent.gt(99),
      forAll.failedRequests.count.is(0)
    )
}