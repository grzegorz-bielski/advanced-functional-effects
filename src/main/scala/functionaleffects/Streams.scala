package functionaleffects

import zio._
import zio.stream.ZPipeline

object Streaming {
  // streams vs functional effects

  // ZIO[R, E, A] -> will produce exactly one A
  // ZIO[R, E, Chunk[A]] -> will produce at once Chunk[A]

  // Functional effects types are binary
  // IO is either 'running' or 'running'

  // ZStream[R, E, A] -> Will produce 0, 1, or infinite amount of A

  // Input (source) >>> Processing (pipes) >>> Outputs (sinks)

  // Streams as an effectual iterator

  // streams - nice if whole operation won't fit in memory

  trait Iterable[+A] {
    def iterator: Iterator[A]
  }

  // not pure fp interface
  // not properly handle asynchrony
  // doesn't handle resource safety
  trait Iterator[+A] {
    def hasNext: Boolean
    def next(): A // could be blocking
  }

  // LazyList -> only good for math problems

  // Three cases
  // 1. value of A - succeed with A
  // 2. error E - fail with Some(E)
  // 3. End of stream -> fail with None
  trait ZIterator[-R, +E, +A] {
    def next(): ZIO[R, Option[E], A]
  }

  object ZIterator {
    def make[R, E, A]: ZIO[Scope, Nothing, ZIterator[R, E, A]] = ???
  }

  // ZIO 1 stream
  // outer zio -> opening / closing the stream
  // inner zio -> pulling ntext value

  // "Chunking" -> A -> Chunk[A], getting a chunk of data for every pull

  final case class ZStream[R, E, A](
      pull: ZIO[Scope, Nothing, ZIO[R, Option[E], Chunk[A]]]
  ) {
    def map[B](fn: A => B): ZStream[R, E, B] =
      ZStream(pull.map(_.map(_.map(fn))))
  }

}

// fake stream - doesn't produce values incrementally
object FakeStreams {
  import zio.stream._

  lazy val stream: ZStream[Any, Nothing, Int] = ???

  val stream2: ZStream[Any, Nothing, Int] =
    ZStream.fromZIO(stream.runFold(0)(_ + _))

  // `ZStream.fromZIO`
  // this won't terminate if the original stream is infinite
  // this won't produce any values until the original stream produced all of its elements
}

object ZIOStreams {
  import zio.stream._

  trait NewTransaction
  trait AnalysisResult

  lazy val infoFromPartners: ZStream[Any, Nothing, NewTransaction] =
    ???

  lazy val analysisPipeline
      : ZPipeline[Any, Nothing, NewTransaction, AnalysisResult] = ???

  lazy val dbWriter: ZSink[Any, Nothing, AnalysisResult, Nothing, Unit] = ???

  lazy val loggingSink: ZSink[Any, Nothing, AnalysisResult, Nothing, Unit] = ???

  val flow: ZIO[Any, Nothing, Unit] =
    infoFromPartners >>> analysisPipeline >>> (dbWriter zipPar loggingSink)

}

// ZIO          -> Akka streams  -> FS2                          -> Rx(JS, push-based / FRP)    -> Conceptually
// ZStream      -> Source        -> Stream                       -> Observable                  -> Beginning
// ZPipeline    -> Flow          -> Pipe (stream => stream func) -> operator (obs => obs func)  -> Middle
// ZSink        -> Sink          -> Pipe .compile                -> observer / subscriber       -> End

// FS2
// Stream[F[_], A] -> produce value(s) A using F[_]
// type Pipe[F[_], -I, +O] = Stream[F, I] => Stream[F, O]


// (ZIO 2 stream -> backed by ZChannel)

trait ZChannel[-Env, -InErr, -InElem, -InDone, +OutErr, +OutElem, +OutDone]

object Examples {
    type ZStream[-R, +E, +A] = ZChannel[R, Any, Any, Any, E, Chunk[A], Any]
    type ZIO[-R, +E, +A]     = ZChannel[R, Any, Any, Any, E, Nothing, A]
}
