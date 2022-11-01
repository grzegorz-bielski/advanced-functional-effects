/** The full power of ZIO Streams is best observed in its rich support for
  * concurrent operations. Through a small number of operators, you can
  * construct highly concurrent streams with minimal latency and high
  * parallelism.
  */
package advancedfunctionaleffects.concurrentstreams

import zio._

import zio.stream._

import zio.test._
import zio.test.TestAspect._

object ConcurrencyOps extends ZIOSpecDefault {
  def spec =
    suite("ConcurrentOps") {

      /** EXERCISE
        *
        * Insert a `.timeout(10.millis)` at the appropriate place to timeout the
        * infinite stream.
        */
      test("timeout") {
        val stream = ZStream(1).forever

        Live.live(for {
          size <- stream.runCount.timeout(10.millis)
        } yield assertTrue(size.isEmpty))
      } +
        /** EXERCISE
          *
          * Use `.mapPar` to apply the mapping in parallel.
          */
        test("mapPar") {
          def fib(n: Int): Int =
            if (n <= 1) n else fib(n - 1) + fib(n - 2)

          val stream = ZStream.range(0, 10)

          for {
            fibs <- stream.mapZIOPar(4)(n => ZIO.succeed(fib(n))).runCollect
          } yield assertTrue(fibs == Chunk(0, 1, 1, 2, 3, 5, 8, 13, 21, 34))
        } +
        /** EXERCISE
          *
          * Use `.flatMapPar` to apply the flatMap in parallel.
          */
        test("flatMapPar") {
          def lookupAge(id: String) =
            ZStream.fromZIO(
              ZIO.fromOption(
                Map("Sherlock" -> 42, "John" -> 43, "Mycroft" -> 48).get(id)
              )
            )

          val stream = ZStream("Sherlock", "John", "Mycroft")

          for {
            ages <- stream
              .flatMapPar(n = 4, bufferSize = 16)(lookupAge)
              .runCollect
          } yield assertTrue(ages.toSet == Set(42, 43, 48))
        } +
        /** EXERCISE
          *
          * Find the right place to complete the promise that will interrupt the
          * provided infinite stream.
          */
        test("interruptWhen") {
          def makeStream(ref: Ref[Int]) =
            ZStream(1).tap(i => ref.update(_ + i)).forever

          Live.live(for {
            ref <- Ref.make(0)
            done <- Promise.make[Nothing, Unit]
            promise <- Promise.make[Nothing, Unit]
            _ <- makeStream(ref)
              .interruptWhen(promise)
              .ensuring(done.succeed(()))
              .runDrain
              .forkDaemon
            _ <- (ref.get <* ZIO.yieldNow).repeatUntil(
              _ > 0
            ) // bad practice: busy loop
            _ <- promise.succeed(())
            result <- done.await.disconnect.timeout(1.second)
          } yield assertTrue(result.isDefined))
        } +
        /** EXERCISE
          *
          * Using `.merge`, perform a concurrent merge of two streams.
          *
          * Fan in
          */
        test("merge") {
          val stream1 = ZStream("1").forever
          val stream2 = ZStream("2").forever

          // Finalizers should be run in the reverse of the order in which they were acquired

          for {
            values <- stream1.merge(stream2).take(100).runCollect
          } yield assertTrue(values.contains("1") && values.contains("2"))
        } @@ flaky +
        /** EXERCISE
          *
          * Use `broadcast` to send one stream to 10 consumers, each of which is
          * created with the provided `consumer` function.
          *
          * Fan out
          *
          * graph dsl in Akka Streams
          */
        test("broadcast") {
          val stream = ZStream(1, 1, 1, 1, 1, 1, 1, 1, 1, 1)

          def consumer(ref: Ref[Int], stream: ZStream[Any, Nothing, Int]) =
            stream.foreach(i => ref.update(_ + i))

          for {
            ref <- Ref.make(0)
            _ <- stream.broadcast(n = 10, maximumLag = 10).flatMap { streams =>
              ZIO.foreach(streams)(consumer(ref, _))
            }
            v <- ref.get
          } yield assertTrue(v == 100)
        } +
        /** EXERCISE
          *
          * balances efficiency with latency
          * 
          *
          * divide streams into two regions - upstream and downstream
          *
          * transduce -> on one fiber aggregateAsync -> two fibers - one
          * processes, one collects
          *
          * Use `aggregateAsync` on a sink created with `ZSink.foldUntil` that
          * sums up every pair of elements.
          */
        test("aggregateAsync(foldUntil(...))") {
          val stream = ZStream(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)

          def sink: ZSink[Any, Nothing, Int, Int, Int] =
            ZSink.foldUntil[Int, Int](0, 2)(_ + _)

          for {
            values <- stream.aggregateAsync(sink).runCollect
          } yield assertTrue(values == Chunk(1, 5, 9, 13, 17))
        } +
        /** EXERCISE
          *
          * Use `aggregateAsync` on a sink created with `ZSink.foldWeighted` to
          * group elements into chunks of size 2.
          */
        test("aggregateAsync(foldWeighted(...))") {
          val stream = ZStream(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

          def sink: ZSink[Any, Nothing, Int, Int, Chunk[Int]] =
            ZSink.foldWeighted[Int, Chunk[Int]](Chunk.empty[Int])((_, _) => 1L, 2L)(_ :+ _)

          for {
            values <- stream.aggregateAsync(sink).runCollect
          } yield assertTrue(
            values == Chunk(
              Chunk(0, 1),
              Chunk(2, 3),
              Chunk(4, 5),
              Chunk(6, 7),
              Chunk(8, 9),
              Chunk(10)
            )
          )
        } +
        /** EXERCISE
          *
          * Use `aggregateAsyncWithin` to group elements into chunks of up to
          * size 10, or 5 milliseconds, whichever comes sooner.
          */
        test("aggregateAsyncWithin") {
          val stream = ZStream(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10).schedule(
            Schedule.spaced(1.millis)
          )

          def sink: ZSink[Any, Nothing, Int, Int, Chunk[Int]] =
            ZSink.collectAllN(10)

          Live.live {
            for {
              values <- stream
                .aggregateAsyncWithin(sink, Schedule.fixed(5.millis))
                .runCollect
            } yield assertTrue(
              values == Chunk(
                Chunk(0, 1, 2, 3),
                Chunk(4, 5, 6, 7),
                Chunk(8, 9, 10)
              )
            )
          }
        } @@ flaky
    }
}
