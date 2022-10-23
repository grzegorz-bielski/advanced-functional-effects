/** INTERRUPTION
  *
  * ZIO is a functional framework for building highly-scalable, resilient,
  * cloud-native applications. The concurrency of ZIO is based on fibers, which
  * are freely and safely interruptible (unlike threads). The interruptibility
  * of ZIO fibers means that ZIO applications can be globally efficient,
  * performing no wasted computations in the presence of errors, early
  * termination, and timeouts.
  *
  * Yet, interruption can be one of the trickest concepts to grasp in ZIO,
  * because in other programming models, developers don't have to worry about
  * pre-emptive cancellation of their own logic.
  *
  * In this section, you will explore the intricacies of interruption and learn
  * how to master interruption, even when writing tricky code that needs to
  * modify the default interruption behavior.
  */
package advancedfunctionaleffects.interruption

import zio._
import zio.test._
import zio.test.TestAspect._
import scala.annotation.tailrec

/** GUARANTEES
  *
  * ZIO can interrupt executing effects at any point in time, even in the middle
  * of a method. In order to write correct code in the presence of interruption,
  * ZIO provides a variety of operators that can be used to guarantee that
  * something happens, i.e. to disable interruption for some region of code.
  */
object InterruptGuarantees extends ZIOSpecDefault {
  def spec = suite("InterruptGuarantees") {
    test("ensuring") {

      /** EXERCISE
        *
        * Learn about the guarantees of `ensuring` by making this test pass.
        */
      for {
        ref <- Ref.make(0)
        latch <- Promise.make[Nothing, Unit]
        promise <- Promise.make[Nothing, Unit]
        fiber <- (latch.succeed(()) *> promise.await)
          .ensuring(ref.update(_ + 1))
          .forkDaemon
        _ <- latch.await // await until fiber starts before interrupting
        _ <- fiber.interrupt
        v <- ref.get
      } yield assertTrue(v == 1)
    } +
      test("onExit") {

        /** EXERCISE
          *
          * Learn about the guarantees of `onExit` by verifying the `Exit` value
          * is interrupted.
          */
        for {
          latch <- Promise.make[Nothing, Unit]
          promise <- Promise.make[Nothing, Exit[Nothing, Nothing]]
          fiber <- (latch.succeed(()) *> ZIO.never)
            .onExit(promise.succeed(_))
            .forkDaemon
          _ <- latch.await // await until fiber starts before interrupting
          _ <- fiber.interrupt
          exit <- promise.await
        } yield assertTrue(exit.isInterrupted)
      } +
      test("acquireRelease") {
        import java.net.Socket

        def acquireSocket: UIO[Socket] = ZIO.never
        def releaseSocket(socket: Socket): UIO[Any] =
          ZIO.attemptBlockingIO(socket.close()).orDie
        def useSocket(socket: Socket): UIO[Int] =
          ZIO.attemptBlockingIO(socket.getInputStream().read()).orDie

        /** EXERCISE
          *
          * Learn about the guarantees of `acquireRelease` by making this test
          * pass.
          *
          * acquireReleaseWith
          *   - acquire (get file handle) - uninterruptible
          *   - use (do sth with file) - interruptible
          *   - release (close a file handle) - uninterruptible
          */
        for {
          latch <- Promise.make[Nothing, Unit]
          // bracket / Resource in
          fiber <- ZIO
            .acquireReleaseWith(latch.succeed(()) *> acquireSocket)(
              releaseSocket(_)
            )(useSocket(_))
            .forkDaemon
          value <- latch.await *> Live.live(
            fiber.join.disconnect.timeout(1.second)
          ) // use actual clock, not the test clock
        } yield assertTrue(value == None)
      }
  }
}

// 1:54:48

object InterruptibilityRegions extends ZIOSpecDefault {
  def spec = suite("InterruptibilityRegions") {
    test("uninterruptible") {

      /** EXERCISE
        *
        * Find the right location to insert `ZIO.uninterruptible` to make the
        * test succeed.
        */
      for {
        ref <- Ref.make(0)
        latch <- Promise.make[Nothing, Unit]
        fiber <- (latch.succeed(()) *> (Live.live(ZIO.sleep(10.millis)) *> ref
          .update(_ + 1)).uninterruptible).forkDaemon
        _ <- latch.await *> fiber.interrupt
        value <- ref.get
      } yield assertTrue(value == 1)
    } +
      test("interruptible") {

        /** EXERCISE
          *
          * Find the right location to insert `ZIO.interruptible` to make the
          * test succeed.
          */
        for {

          ref <- Ref.make(0)
          latch <- Promise.make[Nothing, Unit]
          fiber <- ZIO
            .uninterruptible(latch.succeed(()) *> ZIO.never.interruptible)
            .ensuring(ref.update(_ + 1))
            .forkDaemon
          _ <- Live.live(
            latch.await *> fiber.interrupt.disconnect.timeout(1.second)
          )
          value <- ref.get
        } yield assertTrue(value == 1)
      }
  }
}

/** ZIO has resource-safe interruption, sometimes referred to as
  * "back-pressured" interruption. Interruption operators do not return until
  * whatever they are interrupting has been successfully interrupted. This
  * behavior minimizes the chance of leaking resources (including fibers), but
  * occassionally it is important to understand the implications of this
  * behavior and how to modify the default behavior.
  */
object Backpressuring extends ZIOSpecDefault {
  def spec =
    suite("Backpressuring") {

      /** EXERCISE
        *
        * This test looks like it should complete quickly. Discover what's
        * happening and change the condition until the test passes.
        */

      // TODO: sometimes flaky?
      test("zipPar") {
        Live.live(for {
          start <- Clock.instant
          latch <- Promise.make[Nothing, Unit]
          left = latch.succeed(()).ensuring(ZIO.sleep(1.seconds))
          right = latch.await *> ZIO.fail("Uh oh!")
          _ <- left.disconnect.zipPar(right).ignore
          end <- Clock.instant
          delta = end.getEpochSecond() - start.getEpochSecond()
          _ = println(delta)
        } yield assertTrue(delta < 1))
      } +
        /** EXERCISE
          *
          * Find the appropriate place to add the `disconnect` operator to
          * ensure that even if an effect refuses to be interrupted in a timely
          * fashion, the fiber can be detatched and will not delay interruption.
          */
        test("disconnect") {
          // Live.live(for {
          //   ref <- Ref.make(true)
          //   _   <- (ZIO.sleep(5.seconds) *> ref.set(false)).uninterruptible.timeout(10.millis)
          //   v   <- ref.get
          // } yield assertTrue(v))

          Live.live(for {
            ref <- Ref.make(true)
            _ <- (ZIO.sleep(5.seconds) *> ref.set(
              false
            )).uninterruptible.disconnect.raceAwait(ZIO.sleep(10.millis))
            v <- ref.get
          } yield assertTrue(v))
        }
    }
}

object RaceExample extends ZIOAppDefault {
  def run =
    // longer action gets interrupted
    ZIO
      .sleep(2.seconds)
      .as("left")
      .raceAwait(
        ZIO
          .sleep(3.seconds)
          .as("right")
          // CE 3 - will (always?) wait for cancel handler to finish -> hang, resource safety
          // CE 2 / Monix - don't wait -> avoid hanging, no resource safety
          .onExit(_ =>
            ZIO.debug("Interruption is taking a long time") *> ZIO
              .sleep(5.seconds) *> ZIO.debug("interruption done")
          )
          .disconnect
        // ZIO - (with .disconnect) -> interrupt in background, but return immediately
        /// .race already does it
      )
      .debug *> ZIO.sleep(10.seconds)
}

/** ZIO's multitude of operators that protect against interruption are not
  * necessarily intrinsic: they can be derived from operators that modify
  * interruptibility status and `foldCauseZIO` (or equivalent).
  */
object BasicDerived extends ZIOSpecDefault {
  def spec =
    suite("BasicDerived") {

      /** EXERCISE
        *
        * Using the operators you have learned about so far, reinvent a safe
        * version of `ensuring` in the method `withFinalizer`.
        */
      test("ensuring") {
        def withFinalizer[R, E, A](
            zio: ZIO[R, E, A]
        )(finalizer: UIO[Any]): ZIO[R, E, A] =
          ZIO.uninterruptible {
            // (!) could potentialy create a hole if `zio` was already uninterruptible. Actually it should be -> restore prior uninterruptibility if any
            ZIO
              .interruptible(zio)
              .foldCauseZIO(
                cause => finalizer *> ZIO.failCause(cause),
                value => finalizer *> ZIO.succeed(value)
              )
          }

        for {
          latch <- Promise.make[Nothing, Unit]
          promise <- Promise.make[Nothing, Unit]
          ref <- Ref.make(false)
          fiber <- withFinalizer(latch.succeed(()) *> promise.await)(
            ref.set(true)
          ).forkDaemon
          _ <- latch.await
          _ <- fiber.interrupt
          v <- ref.get
        } yield assertTrue(v)
      } +
        /** EXERCISE
          *
          * Using the operators you have learned about so far, reinvent a safe
          * version of `acquireReleaseWith` in the method `acquireReleaseWith`.
          */
        test("acquireRelease") {
          def acquireReleaseWith[R, E, A, B](
              acquire: ZIO[R, E, A]
          )(release: A => UIO[Any])(use: A => ZIO[R, E, B]): ZIO[R, E, B] =
            ZIO.uninterruptible {
              acquire.flatMap { a =>
                ZIO
                  .interruptible(use(a))
                  .foldCauseZIO(
                    cause => release(a) *> ZIO.failCause(cause),
                    value => release(a) *> ZIO.succeed(value)
                  )
              }
            }

          for {
            latch <- Promise.make[Nothing, Unit]
            promise <- Promise.make[Nothing, Unit]
            ref <- Ref.make(false)
            fiber <- acquireReleaseWith(
              latch.succeed(()) *> Live.live(ZIO.sleep(10.millis))
            )(_ => ref.set(true))(_ => promise.await).forkDaemon
            _ <- latch.await
            _ <- fiber.interrupt
            v <- ref.get
          } yield assertTrue(v)
        }
    }
}

/** Using just `ZIO.uninterruptible` and `ZIO.interruptible`, it is too easy to
  * create code that is interruptible (when it should not be interruptible), or
  * code that is uninterruptible (when it should be interruptible).
  */
object UninterruptibleMask extends ZIOSpecDefault {
  def spec =
    suite("UninterruptibleMask") {

      /** EXERCISE
        *
        * Identify the problem in this code and fix it with
        * `ZIO.uninterruptibleMask`, which restores the parent region status
        * rather than clobbering the child region.
        */
      test("overly interruptible") {
        def doWork[A](queue: Queue[A], worker: A => UIO[Any]) =
          ZIO.uninterruptibleMask { restore =>
            queue.take.flatMap(a => restore(worker(a)))
          }

        def worker(database: Ref[Chunk[Int]]): Int => UIO[Any] = {
          def fib(n: Int): UIO[Int] =
            ZIO.suspendSucceed {
              if (n <= 1) ZIO.succeed(n)
              else fib(n - 1).zipWith(fib(n - 2))(_ + _)
            }

          (i: Int) => fib(i).flatMap(num => database.update(_ :+ num))
        }

        for {
          queue <- Queue.bounded[Int](100)
          database <- Ref.make[Chunk[Int]](Chunk.empty)
          _ <- ZIO.foreach(0 to 1000000)(queue.offer(_)).fork
          fiber <- ZIO
            .uninterruptible(doWork(queue, worker(database)).repeatN(4))
            .fork
          _ <- fiber.interrupt
          data <- database.get
        } yield assertTrue(data.length == 5)
      }
    }
}

/** GRADUATION PROJECT
  *
  * To graduate from this section, you will choose and complete one of the
  * following two problems:
  *
  *   1. Derive a correct implementation of `ensuring` in terms of more
  *      primitive operators.
  *
  * 2. Derive a correct implementation of `acquireReleaseWith` in terms of more
  * primitive operators.
  */
object Graduation extends ZIOSpecDefault {
  def spec =
    suite("Graduation") {

      /** CHOICE 1
        *
        * Using `uninterruptibleMask`, implement a correct version of
        * `ensuring`.
        */
      test("ensuring") {
        def withFinalizer[R, E, A](
            zio: ZIO[R, E, A]
        )(finalizer: UIO[Any]): ZIO[R, E, A] =
          ZIO.uninterruptibleMask { restore =>
            restore(zio).foldCauseZIO(
              cause =>
                (
                  finalizer.foldCauseZIO(
                    cause2 => ZIO.failCause(cause && cause2),
                    _ => ZIO.refailCause(cause)
                  )
                ),
              value => finalizer *> ZIO.succeed(value)
            )
          }

        for {
          latch <- Promise.make[Nothing, Unit]
          promise <- Promise.make[Nothing, Unit]
          ref <- Ref.make(false)
          fiber <- withFinalizer(latch.succeed(()) *> promise.await)(
            ref.set(true)
          ).forkDaemon
          _ <- latch.await
          _ <- fiber.interrupt
          v <- ref.get
        } yield assertTrue(v)
      } +
        /** CHOICE 2
          *
          * Using `uninterruptibleMask`, implement a correct version of
          * `acquireReleaseWith`.
          */
        test("acquireRelease") {
          def acquireReleaseWith[R, E, A, B](
              acquire: ZIO[R, E, A]
          )(release: A => UIO[Any])(use: A => ZIO[R, E, B]): ZIO[R, E, B] =
            ZIO.uninterruptibleMask { restore =>
              acquire.flatMap { a =>
                restore(use(a)).foldCauseZIO(
                  cause => release(a) *> ZIO.failCause(cause),
                  value => release(a) *> ZIO.succeed(value)
                )
              }
            }

          for {
            latch <- Promise.make[Nothing, Unit]
            promise <- Promise.make[Nothing, Unit]
            ref <- Ref.make(false)
            fiber <- acquireReleaseWith(
              latch.succeed(()) *> Live.live(ZIO.sleep(10.millis))
            )(_ => ref.set(true))(_ => promise.await).forkDaemon
            _ <- latch.await
            _ <- fiber.interrupt
            v <- ref.get
          } yield assertTrue(v)
        }
    }
}

object ResourceExample extends ZIOAppDefault {
  val acquire1 = ZIO.debug("acquired1").as(42)
  val release1 = ZIO.debug("release1")
  val myResource1: ZIO[Scope, Nothing, Int] = 
    // replaces ZManaged from ZIO 1.0 - CE Resource alternative
    ZIO.acquireRelease(acquire1)(_ => release1)

  val acquire2 = ZIO.debug("acquired2").as(42)
  val release2 = ZIO.debug("release2")
  val myResource2: ZIO[Scope, Nothing, Int] = 
    ZIO.acquireRelease(acquire2)(_ => release2)

  val myResource3 = for {
    a <- myResource1
    b <- myResource2
  } yield (a, b)
  val run = ZIO.scoped { // like resource.use
    myResource3
  }
}