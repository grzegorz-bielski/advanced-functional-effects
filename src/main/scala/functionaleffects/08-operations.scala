/** ZIO provides features specifically designed to improve your experience
  * deploying, scaling, monitoring, and troubleshooting ZIO applications. These
  * features include async stack traces, fiber dumps, logging hooks, and
  * integrated metrics and monitoring.
  *
  * In this section, you get to explore the operational side of ZIO.
  */
package advancedfunctionaleffects.ops

import zio._

import zio.test._
import zio.test.TestAspect._
import zio.metrics.Metric

object AsyncTraces extends ZIOSpecDefault {
  def spec =
    suite("AsyncTraces") {

      /** EXERCISE
        *
        * Pull out the `traces` associated with the following sandboxed failure,
        * and verify there is at least one trace element.
        */
      test("traces") {
        def async =
          for {
            _ <- ZIO.sleep(1.millis)
            _ <- ZIO.fail("Uh oh!")
          } yield ()

        def traces(cause: Cause[String]): List[StackTrace] =
          cause.traces

        Live.live(for {
          cause <- async.sandbox.flip
          ts = traces(cause)
        } yield assertTrue(ts(0).stackTrace.length > 0))
      }
    }
}

// show fibers and theirs status
object FiberDumps extends ZIOSpecDefault {
  def spec =
    suite("FiberDumps") {

      /** EXERCISE
        *
        * Compute and print out all fiber dumps of the fibers running in this
        * test.
        */
      test("dump") {
        val example =
          for {
            promise <- Promise.make[Nothing, Unit]
            blocked <- promise.await.forkDaemon
            child1 <- ZIO.foreach(1 to 100000)(_ => ZIO.unit).forkDaemon
          } yield ()

        for {
          supervisor <- Supervisor.track(false)
          _ <- example.supervised(supervisor)
          children <- supervisor.value
          _ <- ZIO.foreach(children)(child =>
            child.dump.flatMap(d => ZIO.debug(d.prettyPrint))
          )
        } yield assertTrue(children.length == 2)
      } @@ flaky
    }
}

object ExampleApp extends ZIOAppDefault {
  // override val bootstrap: ZLayer[ZIOAppArgs & Scope, Any, Any] = ???
  // logging backends here - prom loki, file, aws .etc

  // logging frontend
  val run =
    ZIO.logAnnotate("key", "value") {
      ZIO.logSpan("my span") {
        ZIO.logLevel(LogLevel.Warning) {
          ZIO.logInfo("kek") *> ZIO.logError("kek") *> ZIO.log(
            "take level from context"
          )
        }
      }
    }
}

object Logging extends ZIOSpecDefault {
  def spec =
    suite("Logging")()
}

object MetricsApp extends ZIOAppDefault {
  // backend - ZIO metrics connectors - prom, data dog

  // frontend -> common language:
  // Counter - increment
  // Gauge - increment and decrement
  // Histogram - frequency chart
  // Summary - rolling summary
  // Frequency / SetCount ->

  val metric = Metric.counter("my-counter")
  val metric2 = Metric.counter("my-counter") // the same metric

  // logging frontend
  val run = for {
    _ <- metric.increment
    state <- metric.value
    _ <- ZIO.debug(state)
  } yield ()

}

object Metrics extends ZIOSpecDefault {

  def spec =
    suite("Metrics")()
}
