package unit

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ActorLogging, ActorRef, FSM, Status}
import akka.pattern.pipe

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

// received events
final case object StartEvt
case class LockReleasedEvt(maybeRelease: Option[Boolean] = None)
final case class FetchedEvt(var it: Iterator[ClientNotification])
case object NextRequestEvt
case object NextResponseEvt
case object NextClientNotificationEvt
case class LookedUpDeclarantEvt(maybeDeclarant: Option[DeclarantDetails])
case object PushedEvt
case object PulledEvt
case object EnterReleaseLockEvt
case object LockReleasedEvt


// states
sealed trait State2
case object PushInit extends State2
case object PushFetched extends State2
case object PushLooping extends State2
//case object PushProcessRecord extends State2
case object PushDeclarantDetail extends State2
case object PushSent extends State2
case object PullSent extends State2
case object ReleaseLock extends State2
case object Exit extends State2

sealed trait Data2
case object Uninitialized2 extends Data2
final case class Cursor(var it: Iterator[ClientNotification]) extends Data2
final case class PushProcessData(cursor: Cursor, cn: ClientNotification, refreshFailed: AtomicBoolean) extends Data2

case class ClientNotification(i: Int)
case class DeclarantDetails(i: Int)

class Repo {
  def fetch(csid: String): Future[List[ClientNotification]] = ???
  def release(csid: String, lockOwnerId: String): Future[Unit] = ???
}

class Declarant {
  def fetch(csid: String): Future[Option[DeclarantDetails]] = ???
}

class Push {
  def send(d: DeclarantDetails, cn: ClientNotification): Future[Unit] = ???
}


/*
 PushInit
 PushFetch
 CheckingLock
 FetchDeclarantDetails
 CallingPush
 Exit
 PullFetch
 PullDelete
 */
class ClientWorker(
  csid: String,
  lockOwnerId: String,
  repo: Repo, declarant:
  Declarant, push: Push
) extends FSM[State2, Data2] with ActorLogging {

  val lockRefreshFailed = new AtomicBoolean(false)

  private def exit() = {
    self ! EnterReleaseLockEvt
    goto(Exit)
  }

  startWith(PushInit, Uninitialized2)

  when(PushInit, stateTimeout = 1 second) {
    case Event(StartEvt, Uninitialized2) =>
      log.info("PushInit")
      repo.fetch(csid).map(cnList => FetchedEvt(cnList.iterator)) pipeTo self
      goto(PushLooping)
  }

  when(PushLooping, stateTimeout = 1 second) {
    case Event(FetchedEvt(it), _) =>
      log.info(s"PushLooping init fetched.hasNext=${it.hasNext}")
      if (it.hasNext) {
        self ! NextRequestEvt
        stay using Cursor(it)
      } else {
        log.info("PushLooping empty cursor - exiting")
        exit()
      }
    case Event(Status.Failure(e), _) =>
      log.info("ERROR!" + e.getMessage)
      exit()
    case Event(NextRequestEvt, c@Cursor(it)) =>
      log.info("PushLooping next" + Cursor(it))
      if (it.hasNext) {
        log.info("PushLooping has next")
        self ! NextResponseEvt
        goto(PushDeclarantDetail) using(PushProcessData(c, it.next, lockRefreshFailed))
      } else {
        log.info("PushLooping end of cursor - about to do another fetch")
        repo.fetch(csid).map(cnList => FetchedEvt(cnList.iterator)) pipeTo self
        stay using Uninitialized2
      }
  }

  when(PushDeclarantDetail, stateTimeout = 1 second) {
    case Event(NextResponseEvt, PushProcessData(_, cn, refreshFailed)) =>
      log.info("PushProcessRecord:" + cn)
      if (refreshFailed.get) {
        goto(Exit)
      } else {
        declarant.fetch(csid).map(o => LookedUpDeclarantEvt(o)) pipeTo self
      }
      stay
    case Event(d@LookedUpDeclarantEvt(Some(declarant)), p@PushProcessData(_, cn, _)) =>
      log.info(s"looked up declarant: $d")
      push.send(declarant, cn).map(_ => PushedEvt) pipeTo self
      goto(PushSent)
    case Event(LookedUpDeclarantEvt(None), p:PushProcessData) =>
      log.info(s"looked up of declarant is None")
      goto(Exit) using p
    case Event(Status.Failure(e), _) =>
      log.info("ERROR!" + e.getMessage)
      exit()
  }

  when(PushSent, stateTimeout = 1 second) {
    case Event(PushedEvt, PushProcessData(cursor, _, _)) =>
      log.info(s"Pushed OK")
      self ! NextRequestEvt
      goto(PushLooping) using cursor
    case Event(Status.Failure(e), _) => // TODO: find equivelent of NonFatal processing
      log.info("ERROR!" + e.getMessage)
      // TODO: goto PULL processing
      exit()
  }

  when(PullSent, stateTimeout = 1 second) {
    case Event(PulledEvt, PushProcessData(cursor, _, _)) =>
      log.info(s"Pushed OK")
      self ! NextRequestEvt
      goto(PushLooping) using cursor
    case Event(Status.Failure(e), _) => // TODO: find equivelent of NonFatal processing
      log.info("ERROR!" + e.getMessage)
      // TODO: goto PULL processing
      exit()
  }

  when(Exit, stateTimeout = 1 second) {
    case Event(EnterReleaseLockEvt, _) =>
      log.info("EnterReleaseLockEvt")
      repo.release(csid, lockOwnerId).map(_ => LockReleasedEvt) pipeTo self
      // log stop
      stay
    case Event(LockReleasedEvt, _) =>
      log.info("LockReleasedEvt")
      stop
    case Event(Status.Failure(e), _) =>
      log.info("ERROR!" + e.getMessage)
      stop
  }

  initialize() // start timers etc here
}
