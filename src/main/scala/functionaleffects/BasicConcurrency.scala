package functionaleffects

import zio._

object BasicConcurrency {
    trait Ref[A] {
        def get: ZIO[Any, Nothing, A]
        def set(a: A): ZIO[Any, Nothing, Unit]
        def update(f: A => A): ZIO[Any, Nothing, Unit]
        def modify[B](f: A => (B, A)): ZIO[Any, Nothing, B]
    }

    // Promise / Deffered - box that starts empty and can only be filled once (with E or A)

    trait Promise[E, A] {
        def await: ZIO[Any, E, A]
        def succeed(a: A): ZIO[Any, Nothing, Unit]
        def fail(e: E): ZIO[Any, Nothing, Boolean]
    }
}