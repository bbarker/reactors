package org.reactors



import org.scalacheck._
import org.scalacheck.Prop.forAllNoShrink
import org.scalacheck.Gen.choose
import org.scalatest.{FunSuite, Matchers}
import scala.annotation.unchecked
import scala.collection._
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.Success



class TestReactor extends Reactor[Unit] {
}


class SelfReactor(val p: Promise[Boolean]) extends Reactor[Int] {
  sysEvents onMatch {
    case ReactorStarted => p.success(this eq Reactor.self)
  }
}


class PiggyReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
  sysEvents onMatch {
    case ReactorStarted =>
      try {
        val piggy = ReactorSystem.Bundle.schedulers.piggyback
        system.spawn(Proto[SelfReactor].withScheduler(piggy))
      } catch {
        case e: IllegalStateException =>
          p.success(true)
      } finally {
        main.seal()
      }
  }
}


class PromiseReactor(val p: Promise[Unit]) extends Reactor[Unit] {
  p.success(())
}


class ReactorSelfReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
  if (Reactor.self[Reactor[_]] eq this) p.success(true)
  else p.success(false)
}


class ReactorStartedReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
  sysEvents onMatch {
    case ReactorStarted => p.success(true)
  }
}


class AfterFirstBatchReactor(val p: Promise[Boolean]) extends Reactor[String] {
  main.events onMatch {
    case "success" => p.success(true)
  }
}


class DuringFirstBatchReactor(val p: Promise[Boolean]) extends Reactor[String] {
  sysEvents onMatch {
    case ReactorStarted => main.channel ! "success"
  }
  main.events onMatch {
    case "success" => p.success(true)
  }
}


class DuringFirstEventReactor(val p: Promise[Boolean]) extends Reactor[String] {
  main.events onMatch {
    case "message" => main.channel ! "success"
    case "success" => p.success(true)
  }
}


class TwoDuringFirstReactor(val p: Promise[Boolean]) extends Reactor[String] {
  var countdown = 2
  main.events onMatch {
    case "start" =>
      main.channel ! "dec"
      main.channel ! "dec"
    case "dec" =>
      countdown -= 1
      if (countdown == 0) p.success(true)
  }
}


class CountdownReactor(val p: Promise[Boolean], var count: Int) extends Reactor[String] {
  main.events onMatch {
    case "dec" =>
      count -= 1
      if (count == 0) p.success(true)
  }
}


class AfterSealTerminateReactor(val p: Promise[Boolean]) extends Reactor[String] {
  main.events onMatch {
    case "seal" => main.seal()
  }
  sysEvents onMatch {
    case ReactorTerminated => p.success(true)
  }
}


class NewChannelReactor(val p: Promise[Boolean]) extends Reactor[String] {
  val secondary = system.channels.open[Boolean]
  sysEvents onMatch {
    case ReactorStarted =>
      main.channel ! "open"
    case ReactorTerminated =>
      p.success(true)
  }
  main.events onMatch {
    case "open" =>
      secondary.channel ! true
      main.seal()
  }
  secondary.events onEvent { v =>
    secondary.seal()
  }
}


class ReactorScheduledReactor(val p: Promise[Boolean]) extends Reactor[String] {
  var left = 5
  sysEvents onMatch {
    case ReactorScheduled =>
      left -= 1
      if (left == 0) main.seal()
    case ReactorTerminated =>
      p.success(true)
  }
}


class ReactorPreemptedReactor(val p: Promise[Boolean]) extends Reactor[String] {
  var left = 5
  sysEvents onMatch {
    case ReactorPreempted =>
      left -= 1
      if (left > 0) main.channel ! "dummy"
      else main.seal()
    case ReactorTerminated =>
      p.success(true)
  }
}


class CtorExceptionReactor(val p: Promise[(Boolean, Boolean)])
extends Reactor[Unit] {
  var excepted = false
  var terminated = false
  sysEvents onMatch {
    case ReactorDied(t) =>
      excepted = true
    case ReactorTerminated =>
      terminated = true
      p.success((excepted, terminated))
  }
  sys.error("Exception thrown in ctor!")
}


class TerminationExceptionReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
  sysEvents onMatch {
    case ReactorDied(t) => p.success(true)
    case ReactorPreempted => main.seal()
    case ReactorTerminated => sys.error("Exception thrown during termination!")
  }
}


class RunningExceptionReactor(val p: Promise[Throwable]) extends Reactor[String] {
  main.events onMatch {
    case "die" => sys.error("exception thrown")
  }
  sysEvents onMatch {
    case ReactorDied(t) => p.success(t)
  }
}


class EventSourceReactor(val p: Promise[Boolean]) extends Reactor[String] {
  val emitter = new Events.Emitter[Int]()
  emitter onDone {
    p.success(true)
  }
  sysEvents onMatch {
    case ReactorPreempted => main.seal()
  }
}


object Log {
  def apply(msg: String) = println(s"${Thread.currentThread.getName}: $msg")
}


class ManyReactor(p: Promise[Boolean], var n: Int) extends Reactor[String] {
  val sub = main.events onEvent { v =>
    n -= 1
    if (n <= 0) {
      p.success(true)
      main.seal()
    }
  }
}


class EvenOddReactor(p: Promise[Boolean], n: Int) extends Reactor[Int] {
  val rem = mutable.Set[Int]()
  for (i <- 0 until n) rem += i
  main.events onEvent { v =>
    if (v % 2 == 0) even.channel ! v
    else odd.channel ! v
  }
  val odd = system.channels.open[Int]
  odd.events onEvent { v =>
    rem -= v
    check()
  }
  val even = system.channels.open[Int]
  even.events onEvent { v =>
    rem -= v
    check()
  }
  def check() {
    if (rem.size == 0) {
      main.seal()
      odd.seal()
      even.seal()
      p.success(true)
    }
  }
}


class MultiChannelReactor(val p: Promise[Boolean], val n: Int) extends Reactor[Int] {
  var c = n
  val connectors = for (i <- 0 until n) yield {
    val conn = system.channels.open[Int]
    conn.events onEvent { j =>
      if (i == j) conn.seal()
    }
    conn
  }
  main.events onEvent {
    i => connectors(i).channel ! i
  }
  main.events.scanPast(n)((count, _) => count - 1) onEvent { i =>
    if (i == 0) main.seal()
  }
  sysEvents onMatch {
    case ReactorTerminated => p.success(true)
  }
}


class LooperReactor(val p: Promise[Boolean], var n: Int) extends Reactor[String] {
  sysEvents onMatch {
    case ReactorPreempted =>
      if (n > 0) main.channel ! "count"
      else {
        main.seal()
        p.success(true)
      }
  }
  main.events onMatch {
    case "count" => n -= 1
  }
}


class ParentReactor(val p: Promise[Boolean], val n: Int, val s: String)
extends Reactor[Unit] {
  val ch = system.spawn(Proto[ChildReactor](p, n).withScheduler(s))
  for (i <- 0 until n) ch ! i
  main.seal()
}


class ChildReactor(val p: Promise[Boolean], val n: Int) extends Reactor[Int] {
  var nextNumber = 0
  val sub = main.events onEvent { i =>
    if (nextNumber == i) nextNumber += 1
    if (nextNumber == n) {
      main.seal()
      p.success(true)
    }
  }
}


class PingReactor(val p: Promise[Boolean], var n: Int, val s: String) extends Reactor[String] {
  val pong = system.spawn(Proto[PongReactor](n, main.channel).withScheduler(s))
  val start = sysEvents onMatch {
    case ReactorStarted => pong ! "pong"
  }
  val sub = main.events onMatch {
    case "ping" =>
      n -= 1
      if (n > 0) pong ! "pong"
      else {
        main.seal()
        p.success(true)
      }
  }
}


class PongReactor(var n: Int, val ping: Channel[String]) extends Reactor[String] {
  main.events onMatch {
    case "pong" =>
      ping ! "ping"
      n -= 1
      if (n == 0) main.seal()
  }
}


class RingReactor(
  val index: Int,
  val num: Int,
  val sink: Either[Promise[Boolean], Channel[String]],
  val sched: String
) extends Reactor[String] {

  val next: Channel[String] = {
    if (index == 0) {
      val p = Proto[RingReactor](index + 1, num, Right(main.channel), sched)
        .withScheduler(sched)
      system.spawn(p)
    } else if (index < num) {
      val p = Proto[RingReactor](index + 1, num, sink, sched).withScheduler(sched)
      system.spawn(p)
    } else {
      sink match {
        case Right(first) => first
        case _ => sys.error("unexpected case")
      }
    }
  }

  main.events onMatch {
    case "start" =>
      next ! "ping"
    case "ping" =>
      next ! "ping"
      main.seal()
      if (index == 0) sink match {
        case Left(p) => p.success(true)
        case _ => sys.error("unexpected case")
      }
  }
}


class TerminatedReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
  sysEvents onMatch {
    case ReactorStarted =>
      main.seal()
    case ReactorTerminated =>
      // should still be different than null
      p.success(system.frames.forName("ephemo") != null)
  }
}


class LookupChannelReactor(val started: Promise[Boolean], val ended: Promise[Boolean])
extends Reactor[Unit] {
  sysEvents onMatch {
    case ReactorStarted =>
      val terminator = system.channels.daemon.named("terminator").open[String]
      terminator.events onMatch {
        case "end" =>
          main.seal()
          ended.success(true)
      }
      started.success(true)
  }
}


// class ChannelsAskReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
//   val answer = system.channels.daemon.open[Option[Channel[_]]]
//   system.iso.resolver ! (("chaki#main", answer.channel))
//   answer.events onMatch {
//     case Some(ch: Channel[Unit] @unchecked) => ch ! (())
//     case None => sys.error("chaki#main not found")
//   }
//   main.events on {
//     main.seal()
//     p.success(true)
//   }
// }


// class RequestReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
//   import patterns._
//   import scala.concurrent.duration._
//   val server = system.channels.daemon.open[(String, Channel[String])]
//   server.events onMatch {
//     case ("request", r) => r ! "reply"
//   }
//   sysEvents onMatch {
//     case ReactorStarted =>
//       server.channel.request("request", 2.seconds) onMatch {
//         case "reply" =>
//           main.seal()
//           p.success(true)
//       }
//   }
// }


// class TimeoutRequestReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
//   import patterns._
//   import scala.concurrent.duration._
//   val server = system.channels.daemon.open[(String, Channel[String])]
//   server.events onMatch {
//     case ("request", r) => // staying silent
//   }
//   sysEvents onMatch {
//     case ReactorStarted =>
//       server.channel.request("request", 1.seconds) onExcept {
//         case t =>
//           main.seal()
//           p.success(true)
//       }
//   }
// }


// class SecondRetryAfterTimeoutReactor(val p: Promise[Int]) extends Reactor[Unit] {
//   import patterns._
//   val requests = system.channels.daemon.open[(String, Channel[String])]
//   var firstRequest = true
//   var numAttempts = 0
//   def test(x: String) = {
//     numAttempts += 1
//     true
//   }
//   requests.events onMatch {
//     case ("try", answer) =>
//       if (firstRequest) firstRequest = false
//       else answer ! "yes"
//   }
//   sysEvents onMatch {
//     case ReactorStarted =>
//       requests.channel.retry("try", test, 4, 200.millis) onMatch {
//         case "yes" => p.success(numAttempts)
//       }
//   }
// }


// class SecondRetryAfterDropReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
//   import patterns._
//   val requests = system.channels.daemon.open[(String, Channel[String])]
//   var numReplies = 0
//   requests.events onMatch {
//     case ("try", answer) => answer ! "yes"
//   }
//   def test(x: String) = {
//     numReplies += 1
//     numReplies == 2
//   }
//   sysEvents onMatch {
//     case ReactorStarted =>
//       requests.channel.retry("try", test, 4, 200.millis) onMatch {
//         case "yes" => p.success(true)
//       }
//   }
// }


// class FailedRetryReactor(val p: Promise[Boolean]) extends Reactor[Unit] {
//   import patterns._
//   val requests = system.channels.daemon.open[(String, Channel[String])]
//   requests.events onMatch {
//     case ("try", answer) => answer ! "yes"
//   }
//   sysEvents onMatch {
//     case ReactorStarted =>
//       requests.channel.retry("try", _ => false, 4, 50.millis).onReaction(
//         new Reactor[String] {
//           def react(x: String) = p.success(false)
//           def except(t: Throwable) = t match {
//             case r: RuntimeException => p.success(true)
//             case _ => p.success(false)
//           }
//           def unreact() = {}
//         })
//   }
// }


// class NameFinderReactor extends Reactor[Unit] {
//   import patterns._
//   system.iso.resolver.retry("fluffy#main", _ != None, 20, 50.millis) onMatch {
//     case Some(ch: Channel[String] @unchecked) =>
//       ch ! "die"
//       main.seal()
//   }
// }


class NamedReactor(val p: Promise[Boolean]) extends Reactor[String] {
  main.events onMatch {
    case "die" =>
      main.seal()
      p.success(true)
  }
}


class SysEventsReactor(val p: Promise[Boolean]) extends Reactor[String] {
  val events = mutable.Buffer[SysEvent]()
  sysEvents onMatch {
    case ReactorTerminated =>
      p.trySuccess(events == Seq(ReactorStarted, ReactorScheduled, ReactorPreempted))
    case e =>
      events += e
      if (events.size == 3) main.seal()
  }
}


class TerminateEarlyReactor(
  val p: Promise[Boolean], val fail: Promise[Boolean], val max: Int
) extends Reactor[String] {
  var seen = 0
  var terminated = false
  main.events onEvent { s =>
    if (terminated) {
      fail.success(true)
    } else {
      seen += 1
      if (seen >= max) {
        terminated = true
        main.seal()
      }
    }
  }
  sysEvents onMatch {
    case ReactorTerminated =>
      p.success(true)
  }
}


abstract class BaseReactorSystemCheck(name: String) extends Properties(name) {

  val system = ReactorSystem.default("check-system")

  val scheduler: String

  // property("should send itself messages") = forAllNoShrink(choose(1, 1024)) { n =>
  //   val p = Promise[Boolean]()
  //   system.spawn(Proto[LooperReactor](p, n).withScheduler(scheduler))
  //   Await.result(p.future, 10.seconds)
  // }

}


abstract class ReactorSystemCheck(name: String) extends BaseReactorSystemCheck(name) {

  // property("should receive many events") = forAllNoShrink(choose(1, 1024)) { n =>
  //   val p = Promise[Boolean]()
  //   val ch = system.spawn(Proto[ManyReactor](p, n).withScheduler(scheduler))
  //   for (i <- 0 until n) ch ! "count"
  //   Await.result(p.future, 10.seconds)
  // }

  // property("should receive many events through different sources") =
  //   forAllNoShrink(choose(1, 1024)) { n =>
  //     val p = Promise[Boolean]()
  //     val ch = system.spawn(Proto[EvenOddReactor](p, n).withScheduler(scheduler))
  //     for (i <- 0 until n) ch ! i
  //     Await.result(p.future, 10.seconds)
  //   }

  // property("should be terminated after all its channels are sealed") =
  //   forAllNoShrink(choose(1, 128)) { n =>
  //     val p = Promise[Boolean]()
  //     val ch = system.spawn(Proto[MultiChannelReactor](p, n).withScheduler(scheduler))
  //     for (i <- 0 until n) {
  //       Thread.sleep(0)
  //       ch ! i
  //     }
  //     Await.result(p.future, 10.seconds)
  //   }

  // property("should create another isolate and send it messages") =
  //   forAllNoShrink(choose(1, 512)) { n =>
  //     val p = Promise[Boolean]()
  //     system.spawn(Proto[ParentReactor](p, n, scheduler).withScheduler(scheduler))
  //     Await.result(p.future, 10.seconds)
  //   }

  // property("should play ping-pong with another isolate") =
  //   forAllNoShrink(choose(1, 512)) { n =>
  //     val p = Promise[Boolean]()
  //     system.spawn(Proto[PingReactor](p, n, scheduler).withScheduler(scheduler))
  //     Await.result(p.future, 10.seconds)
  //   }

  // property("a ring of isolates should correctly propagate messages") =
  //   forAllNoShrink(choose(1, 64)) { n =>
  //     val p = Promise[Boolean]()
  //     val proto = Proto[RingReactor](0, n, Left(p), scheduler).withScheduler(scheduler)
  //     val ch = system.spawn(proto)
  //     ch ! "start"
  //     Await.result(p.future, 10.seconds)
  //   }

  // property("should receive all system events") =
  //   forAllNoShrink(choose(1, 128)) { n =>
  //     val p = Promise[Boolean]()
  //     val proto = Proto[SysEventsReactor](p).withScheduler(scheduler)
  //     system.spawn(proto)
  //     Await.result(p.future, 10.seconds)
  //   }

  // property("should not process any events after sealing") =
  //   forAllNoShrink(choose(1, 32000)) { n =>
  //     val total = 32000
  //     val p = Promise[Boolean]()
  //     val fail = Promise[Boolean]()
  //     val proto = Proto[TerminateEarlyReactor](p, fail, n).withScheduler(scheduler)
  //     val ch = system.spawn(proto)
  //     for (i <- 0 until total) ch ! "msg"
  //     Await.result(p.future, 10.seconds) && fail.future.value != Some(Success(true))
  //   }

}


object NewThreadReactorSystemCheck extends ReactorSystemCheck("NewThreadSystem") {
  val scheduler = ReactorSystem.Bundle.schedulers.newThread

  // property("shutdown system hack") = forAllNoShrink(choose(0, 0)) { x =>
  //   system.shutdown()
  //   true
  // }
}


object GlobalExecutionContextReactorSystemCheck extends ReactorSystemCheck("ECSystem") {
  val scheduler = ReactorSystem.Bundle.schedulers.globalExecutionContext

  // property("shutdown system hack") = forAllNoShrink(choose(0, 0)) { x =>
  //   system.shutdown()
  //   true
  // }
}


object DefaultSchedulerReactorSystemCheck extends ReactorSystemCheck("DefaultSchedulerSystem") {
  val scheduler = ReactorSystem.Bundle.schedulers.default

  // property("shutdown system hack") = forAllNoShrink(choose(0, 0)) { x =>
  //   system.shutdown()
  //   true
  // }
}


object PiggybackReactorSystemCheck extends BaseReactorSystemCheck("PiggybackSystem") {
  val scheduler = ReactorSystem.Bundle.schedulers.piggyback

  // property("shutdown system hack") = forAllNoShrink(choose(0, 0)) { x =>
  //   system.shutdown()
  //   true
  // }
}


class ReactorSystemTest extends FunSuite with Matchers {

  test("system should return without throwing") {
    val system = ReactorSystem.default("test")
    try {
      val proto = Proto[TestReactor]
      system.spawn(proto)
      assert(system.frames.forName("reactor-0") != null)
    } finally system.shutdown()
  }

  test("system should return without throwing and use custom name") {
    val system = ReactorSystem.default("test")
    try {
      val proto = Proto[TestReactor].withName("Izzy")
      system.spawn(proto)
      assert(system.frames.forName("Izzy") != null)
      assert(system.frames.forName("Izzy").name == "Izzy")
    } finally system.shutdown()
  }

  test("system should throw when attempting to reuse the same name") {
    val system = ReactorSystem.default("test")
    try {
      system.spawn(Proto[TestReactor].withName("Izzy"))
      intercept[IllegalArgumentException] {
        system.spawn(Proto[TestReactor].withName("Izzy"))
      }
    } finally system.shutdown()
  }

  test("system should create a default channel for the reactor") {
    val system = ReactorSystem.default("test")
    try {
      val channel = system.spawn(Proto[TestReactor].withName("Izzy"))
      assert(channel != null)
      val conn = system.frames.forName("Izzy").connectors.forName("main")
      assert(conn != null)
      assert(conn.channel eq channel)
      assert(!conn.isDaemon)
    } finally system.shutdown()
  }

  test("system should create a system channel for the reactor") {
    val system = ReactorSystem.default("test")
    try {
      system.spawn(Proto[TestReactor].withName("Izzy"))
      val conn = system.frames.forName("Izzy").connectors.forName("system")
      assert(conn != null)
      assert(conn.isDaemon)
    } finally system.shutdown()
  }

  test("system should schedule reactor's ctor for execution") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Unit]()
      system.spawn(Proto[PromiseReactor](p))
      Await.result(p.future, 10.seconds)
    } finally system.shutdown()
  }

  test("system should invoke the ctor with the Reactor.self set") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[ReactorSelfReactor](p))
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should ensure the ReactorStarted event") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[ReactorStartedReactor](p))
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should process an event that arrives after the first batch") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      val ch = system.spawn(Proto[AfterFirstBatchReactor](p))
      Thread.sleep(250)
      ch ! "success"
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should process an event that arrives during the first batch") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      val ch = system.spawn(Proto[DuringFirstBatchReactor](p))
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should process an event that arrives during the first event") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      val ch = system.spawn(Proto[DuringFirstEventReactor](p))
      ch ! "message"
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should process two events that arrive during the first event") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      val ch = system.spawn(Proto[TwoDuringFirstReactor](p))
      ch ! "start"
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should process 100 incoming events") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      val ch = system.spawn(Proto[CountdownReactor](p, 100))
      Thread.sleep(250)
      for (i <- 0 until 100) ch ! "dec"
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should terminate after sealing its channel") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      val ch = system.spawn(Proto[AfterSealTerminateReactor](p))
      ch ! "seal"
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should be able to open a new channel") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[NewChannelReactor](p))
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should get ReactorScheduled events") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      val ch = system.spawn(Proto[ReactorScheduledReactor](p))
      for (i <- 0 until 5) {
        Thread.sleep(60)
        ch ! "dummy"
      }
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should get ReactorPreempted events") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[ReactorPreemptedReactor](p))
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("reactor should terminate on ctor exception") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[(Boolean, Boolean)]()
      system.spawn(Proto[CtorExceptionReactor](p))
      assert(Await.result(p.future, 10.seconds) == (true, true))
    } finally system.shutdown()
  }

  test("reactor does not raise ReactorDied events after ReactorTerminated") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[TerminationExceptionReactor](p))
      Thread.sleep(100)
      assert(p.future.value == None)
    } finally system.shutdown()
  }

  test("reactor should terminate on exceptions while running") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Throwable]()
      val ch = system.spawn(Proto[RunningExceptionReactor](p))
      ch ! "die"
      assert(Await.result(p.future, 10.seconds).getMessage == "exception thrown")
    } finally system.shutdown()
  }

  test("Reactor.self should be correctly set") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[SelfReactor](p))
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("piggyback scheduler should throw an exception if called from a reactor") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[PiggyReactor](p))
      assert(Await.result(p.future, 10.seconds))
    } finally system.shutdown()
  }

  test("after termination and before ReactorTerminated reactor name must be released") {
    val system = ReactorSystem.default("test")
    try {
      val p = Promise[Boolean]()
      system.spawn(Proto[TerminatedReactor](p).withName("ephemo"))
      assert(Await.result(p.future, 10.seconds))
      Thread.sleep(1200)
      assert(system.frames.forName("ephemo") == null)
    } finally system.shutdown()
  }

  test("after the reactor starts, its channel should be looked up") {
    val system = ReactorSystem.default("test")
    try {
      val started = Promise[Boolean]()
      val ended = Promise[Boolean]()
      val channel = system.spawn(Proto[LookupChannelReactor](started, ended).withName("pi"))
      assert(Await.result(started.future, 10.seconds))
      system.channels.find[String]("pi#terminator") match {
        case Some(ch) => ch ! "end"
        case None => sys.error("channel not found")
      }
      assert(Await.result(ended.future, 10.seconds))
    } finally system.shutdown()
  }

  // test("channels reactor should look up channels when asked") {
  //   val system = ReactorSystem.default("test")
  //   try {
  //     val p = Promise[Boolean]
  //     system.spawn(Proto[ChannelsAskReactor](p).withName("chaki"))
  //     assert(Await.result(p.future, 10.seconds))
  //   } finally system.shutdown()
  // }

  // test("request should return the result once") {
  //   val system = ReactorSystem.default("test")
  //   try {
  //     val p = Promise[Boolean]
  //     system.spawn(Proto[RequestReactor](p))
  //     assert(Await.result(p.future, 10.seconds))
  //   } finally system.shutdown()
  // }

  // test("request should timeout once") {
  //   val system = ReactorSystem.default("test")
  //   try {
  //     val p = Promise[Boolean]
  //     system.spawn(Proto[TimeoutRequestReactor](p))
  //     assert(Await.result(p.future, 10.seconds))
  //   } finally system.shutdown()
  // }

  // test("retry should succeed the second time after timeout") {
  //   val system = ReactorSystem.default("test")
  //   try {
  //     val p = Promise[Int]
  //     system.spawn(Proto[SecondRetryAfterTimeoutReactor](p))
  //     assert(Await.result(p.future, 10.seconds) == 1)
  //   } finally system.shutdown()
  // }

  // test("retry should succeed the second time after dropping reply") {
  //   val system = ReactorSystem.default("test")
  //   try {
  //     val p = Promise[Boolean]
  //     system.spawn(Proto[SecondRetryAfterDropReactor](p))
  //     assert(Await.result(p.future, 10.seconds))
  //   } finally system.shutdown()
  // }

  // test("retry should fail after 5 retries") {
  //   val system = ReactorSystem.default("test")
  //   try {
  //     val p = Promise[Boolean]
  //     system.spawn(Proto[FailedRetryReactor](p))
  //     assert(Await.result(p.future, 10.seconds))
  //   } finally system.shutdown()
  // }

  // test("should resolve name before failing") {
  //   val system = ReactorSystem.default("test")
  //   try {
  //     val p = Promise[Boolean]
  //     system.spawn(Proto[NameFinderReactor])
  //     Thread.sleep(100)
  //     system.spawn(Proto[NamedReactor](p).withName("fluffy"))
  //     assert(Await.result(p.future, 10.seconds))
  //   } finally system.shutdown()
  // }

}