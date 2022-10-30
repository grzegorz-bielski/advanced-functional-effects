// 00:42:50

/** CONCURRENCY
  *
  * ZIO has pervasive support for concurrency, including parallel versions of
  * operators like `zip`, `foreach`, and many others.
  *
  * More than just enabling your applications to be highly concurrent, ZIO gives
  * you lock-free, asynchronous structures like queues, hubs, and pools.
  * Sometimes you need more: you need the ability to concurrently update complex
  * shared state across many fibers.
  *
  * Whether you need the basics or advanced custom functionality, ZIO has the
  * toolset that enables you to solve any problem you encounter in the
  * development of highly-scalable, resilient, cloud-native applications.
  *
  * In this section, you will explore more advanced aspects of ZIO concurrency,
  * so you can learn to build applications that are low-latency, highly-
  * scalable, interruptible, and free from deadlocks and race conditions.
  */
package advancedfunctionaleffects.concurrency

import zio._
import zio.stm._
import zio.test._
import zio.test.TestAspect._

/** ZIO queues are high-performance, asynchronous, lock-free structures backed
  * by hand-optimized ring buffers. ZIO queues come in variations for bounded,
  * which are doubly-backpressured for producers and consumers, sliding (which
  * drops earlier elements when capacity is reached), dropping (which drops
  * later elements when capacity is reached), and unbounded.
  *
  * Queues work well for multiple producers and multiple consumers, where
  * consumers divide work between themselves.
  */
object QueueBasics extends ZIOSpecDefault {
  def spec =
    suite("QueueBasics") {

      /** EXERCISE
        *
        * Using `.take` and `.offer`, create two fibers, one which places 12
        * inside the queue, and one which takes an element from the queue and
        * stores it into the provided `ref`.
        */
      test("offer/take") {
        for {
          ref <- Ref.make(0)
          q <- Queue.bounded[Int](16)
          fiber <- q.take.flatMap(ref.set).fork
          _ <- q.offer(12).fork
          _ <- fiber.join
          v <- ref.get
        } yield assertTrue(v == 12)
      } +
        /** EXERCISE
          *
          * Create a consumer of this queue that adds each value taken from the
          * queue to the counter, so the unit test can pass.
          */
        test("consumer") {
          for {
            counter <- Ref.make(0)
            queue <- Queue.bounded[Int](100)
            _ <- ZIO.foreach(1 to 100)(v => queue.offer(v)).forkDaemon
            // _ <- ZIO.foreach(1 to 100)(_ => queue.take.flatMap(x => counter.update(_ + x)))
            _ <- queue.takeN(100).flatMap(xs => counter.update(_ + xs.sum))
            value <- counter.get
          } yield assertTrue(value == 5050)
        } +
        /** EXERCISE
          *
          * Queues are fully concurrent-safe on both producer and consumer side.
          * Choose the appropriate operator to parallelize the production side
          * so all values are produced in parallel.
          */
        test("multiple producers") {
          for {
            counter <- Ref.make(0)
            queue <- Queue.bounded[Int](100)
            _ <- ZIO.foreachPar(1 to 100)(v => queue.offer(v)).forkDaemon
            _ <- queue.take.flatMap(v => counter.update(_ + v)).repeatN(99)
            value <- counter.get
          } yield assertTrue(value == 5050)
        } +
        /** EXERCISE
          *
          * Choose the appropriate operator to parallelize the consumption side
          * so all values are consumed in parallel.
          */
        test("multiple consumers") {
          for {
            counter <- Ref.make(0)
            queue <- Queue.bounded[Int](100)
            _ <- ZIO.foreachPar(1 to 100)(v => queue.offer(v)).forkDaemon
            // _ <- queue.take.flatMap(v => counter.update(_ + v)).repeatN(99)
            _ <- ZIO.foreachPar(1 to 100)(_ =>
              queue.take.flatMap(x => counter.update(_ + x))
            )
            value <- counter.get
          } yield assertTrue(value == 5050)
        } +
        /** EXERCISE
          *
          * Shutdown the queue, which will cause its sole producer to be
          * interrupted, resulting in the test succeeding.
          */
        test("shutdown") {
          for {
            done <- Ref.make(false)
            latch <- Promise.make[Nothing, Unit]
            queue <- Queue.bounded[Int](100)
            _ <- (latch.succeed(()) *> queue.offer(1).forever)
              .ensuring(done.set(true))
              .fork
            _ <- latch.await
            _ <- queue.takeN(100)
            _ <- queue.shutdown // will interrupt any q.offer / q.take
            isDone <- Live.live(
              done.get.repeatWhile(_ == false).timeout(10.millis).some
            )
          } yield assertTrue(isDone)
        }
    }
}

/** * Ref
  *
  * \- can't wait for some condition / value
  * \- can't compose atomic updates between multiple refs
  * \- solution:
  * \- one ref -> one giant state
  * \- semaphore -> lock, only one can interact at the time
  */

object STMExample extends ZIOAppDefault {
  def transfer(from: Ref[Int], to: Ref[Int]): ZIO[Any, Nothing, Unit] =
    for {
      _ <- from.update(_ - 1)
      _ <- to.update(_ + 1)
    } yield ()

  def run =
    for {
      ref1 <- Ref.make(100)
      ref2 <- Ref.make(0)
      _ <- ZIO.collectAll(List.fill(100)(transfer(ref1, ref2)))
    } yield ()
}

// 01:53:38

/** ZIO's software transactional memory lets you create your own custom
  * lock-free, race-free, concurrent structures, for cases where there are no
  * alternatives in ZIO, or when you need to make coordinated changes across
  * many structures in a transactional way.
  *
  * STM trades of some perf for dev ux
  *
  * There could be multiple TRefs, STM impl will track transactions between them
  */
object StmBasics extends ZIOSpecDefault {
  def spec =
    suite("StmBasics") {
      test("latch") {

        /** EXERCISE
          *
          * Implement a simple concurrent latch.
          *
          * Promise (CE) / Deferred like
          *
          * type STM[E, A] = ZSTM[Any, E, A]
          */
        final case class Latch(ref: TRef[Boolean]) {
          def await: UIO[Any] = ref.get.flatMap { completed =>
            // not a busy loop - will only spin if ref has changed (using with async)
            if (completed) STM.unit else STM.retry
          }.commit // commit transaction atomically
          def trigger: UIO[Any] = ref.set(true).commit
        }

        def makeLatch: UIO[Latch] = TRef.make(false).map(Latch(_)).commit

        for {
          latch <- makeLatch
          waiter <- latch.await.fork
          _ <- Live.live(Clock.sleep(10.millis))
          first <- waiter.poll // inspect fiber status
          _ <- latch.trigger
          _ <- Live.live(Clock.sleep(10.millis))
          second <- waiter.poll
        } yield assertTrue(first.isEmpty && second.isDefined)
      } +
        test("countdown latch") {

          /** EXERCISE
            *
            * Implement a simple concurrent latch.
            */
          final case class CountdownLatch(ref: TRef[Int]) {
            def await: UIO[Any] = ref.get.flatMap { n =>
              if (n <= 0) STM.unit else STM.retry
            }.commit
            def countdown: UIO[Any] = ref.update(_ - 1).commit
          }

          def makeLatch(n: Int): UIO[CountdownLatch] =
            TRef.make(n).map(ref => CountdownLatch(ref)).commit

          for {
            latch <- makeLatch(10)
            _ <- latch.countdown.repeatN(8)
            waiter <- latch.await.fork
            _ <- Live.live(Clock.sleep(10.millis))
            first <- waiter.poll
            _ <- latch.countdown
            _ <- Live.live(Clock.sleep(10.millis))
            second <- waiter.poll
          } yield assertTrue(first.isEmpty && second.isDefined)
        } +
        test("permits") {

          /** EXERCISE
            *
            * Implement `acquire` and `release` in a fashion the test passes.
            *
            * semaphore
            */
          final case class Permits(ref: TRef[Int]) {
            // let `howMany` to acquire the process, and `release` it after
            def acquire(howMany: Int): UIO[Unit] =
              acquireSTM(howMany).commit

            def acquireSTM(howMany: Int): STM[Nothing, Unit] =
              ref.get.flatMap { available =>
                if (available >= howMany) ref.set(available - howMany)
                else STM.retry
              }

            def releaseSTM(howMany: Int): STM[Nothing, Unit] =
              ref.update(_ + howMany)

            def release(howMany: Int): UIO[Unit] =
              releaseSTM(howMany).commit

            def withPermits[R, E, A](
                howMany: Int
            )(zio: ZIO[R, E, A]): ZIO[R, E, A] =
              ZIO.acquireReleaseWith(acquire(howMany))(_ => release(howMany))(
                _ => zio
              )

            def withPermitsScoped[R, E, A](
                howMany: Int
            ): ZIO[Scope, Nothing, Unit] =
              ZIO.acquireRelease(acquire(howMany))(_ => release(howMany))
          }

          def makePermits(max: Int): UIO[Permits] =
            TRef.make(max).map(Permits(_)).commit

          for {
            counter <- Ref.make(0)
            permits <- makePermits(100)
            _ <- ZIO.foreachPar(1 to 1000)(_ =>
              Random
                .nextIntBetween(1, 2)
                .flatMap(n =>
                  permits.acquire(n) *>
                    // ZIO.debug("work") *>
                    permits.release(n)
                )
            )
            latch <- Promise.make[Nothing, Unit]

            // should not be able to acquire more than 100 permits
            fiber <- (latch.succeed(()) *> permits.acquire(101) *> counter.set(
              1
            )).forkDaemon
            _ <- latch.await
            _ <- Live.live(ZIO.sleep(1.second))
            _ <- fiber.interrupt
            count <- counter.get
            permits <- permits.ref.get.commit
          } yield assertTrue(count == 0 && permits == 100)
        }
    }
}

/** ZIO hubs are high-performance, asynchronous, lock-free structures backed by
  * hand-optimized ring buffers. Hubs are designed for broadcast scenarios where
  * multiple (potentially many) consumers need to access the same values being
  * published to the hub.
  *
  * multicast queues / Topic / Subject / EventEmitter (but without streams)
  */
object HubBasics extends ZIOSpecDefault {
  def spec =
    suite("HubBasics") {

      /** EXERCISE
        *
        * Use the `subscribe` method from 100 fibers to pull out the same values
        * from a hub, and use those values to increment `counter`.
        *
        * Take note of the synchronization logic. Why is this logic necessary?
        */
      test("subscribe") {
        for {
          counter <- Ref.make[Int](0)
          hub <- Hub.bounded[Int](
            100
          ) // will retain messages until they have been taken by all subscribers
          latch <- TRef.make(100).commit
          scount <- Ref.make[Int](0)
          _ <- (latch.get.retryUntil(_ <= 0).commit *> ZIO.foreach(1 to 100)(
            hub.publish(_)
          )).forkDaemon
          _ <- ZIO
            .foreachPar(1 to 100) { _ =>
              ZIO.scoped(hub.subscribe.flatMap { queue =>
                // can only take from Dequeue
                latch.update(_ - 1).commit *> queue.take
                  .flatMap(n => counter.update(_ + n))
                  .repeatN(99)
              })
            }
            .withParallelismUnbounded
          value <- counter.get
        } yield assertTrue(value == 505000)
      } @@ ignore
    }
}

/** GRADUATION PROJECT
  *
  * To graduate from this section, you will choose and complete one of the
  * following two problems:
  *
  *   1. Implement a bulkhead pattern, which provides rate limiting to a group
  *      of services to protect other services accessing a given resource.
  *
  * 2. Implement a CircuitBreaker, which triggers on too many failures, and
  * which (gradually?) resets after a certain amount of time.
  * 
  * TODO: make in CE
  */
object Graduation extends ZIOSpecDefault {
  def spec =
    suite("Graduation") {
      val myWebService = new SomeService {
        def get(id: Int): UIO[Int] = ZIO.succeed(47)
        def put(id: Int, value: String): UIO[Unit] =
          ZIO.succeed(throw new IllegalAccessError("I LIED")).unit
      }

      test("works fine") {
        for {
          circuitBreaker <- CircuitBreaker.make
          resilientWebService = new SomeService {
            def get(id: Int) = circuitBreaker(myWebService.get(id))
            def put(id: Int, value: String) =
              circuitBreaker(myWebService.put(id, value))
          }
        } yield assertTrue(true)
      }
    } @@ ignore
}

trait SomeService {
  def get(id: Int): UIO[Int]
  def put(id: Int, value: String): UIO[Unit]
}

trait CircuitBreaker {
  def apply[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A]
}

object CircuitBreaker {
  // What errors count ?
  // discrete states - open / closed / half-open OR continuos state (func)
  // error weights ?

  // https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker
  private trait State {
      def onSuccess[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] = ???
      def onFailure[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] = ???
  }

  private object State {
    case class Open(failures: Int) extends State
    case object HalfOpen extends State
    case object Closed extends State
  }

  def make: ZIO[Any, Nothing, CircuitBreaker] =
    Ref.make(State.Closed).map { ref =>
      new CircuitBreaker {
        def apply[R, E, A](zio: ZIO[R, E, A]) = 
          zio.foldCauseZIO(
            failure => ???,
            success => ???
          )
      }
    }
}

// 00:37