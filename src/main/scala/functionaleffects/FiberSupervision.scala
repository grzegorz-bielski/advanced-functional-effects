package functionaleffects

import zio._

object FiberSupervision extends ZIOAppDefault {
  val workflow =
    (ZIO.sleep(5.seconds) *> ZIO.debug("Time to wake up!")).fork.fork

  val run =
    for {
      _ <- workflow
      _ <- ZIO.sleep(10.seconds)
    } yield ()

  // Wake up is never printed!
  // ZIO has fiber supervision model by default - structured concurrency

  // - forkDaemon - forks a fiber without supervision
  // - (CE) IO.start -> .forkDaemon
  // CE supervision helpers - https://typelevel.org/cats-effect/docs/concepts#structured-concurrency

  // When a fiber completes execution before completing wind down it will interrupt all of its children
}
