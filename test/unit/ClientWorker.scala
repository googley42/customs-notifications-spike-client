package unit

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ActorLogging, FSM, Status}
import akka.pattern.pipe

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

// received events
final case object StartEvt
final case class LoopingInit(returnState: State2)
final case class FetchedEvt(var it: Iterator[ClientNotification], returnState: State2)
case object NextRequestEvt
case object NextResponseEvt
case class LookedUpDeclarantEvt(maybeDeclarant: Option[DeclarantDetails])
case class PushEvt(declarant: DeclarantDetails)
case object PushedEvt
case object PullSendEvt
case object PullSentEvt
case object DeleteEvt
case class DeletedEvt(deleted: Boolean)
case object LockReleaseEvt
case object LockReleasedEvt

// states
sealed trait State2
case object PushInit extends State2
case object Looping extends State2
case object PushLookupDeclarant extends State2
case object PushSend extends State2
case object PullSend extends State2
case object Delete extends State2
case object Exit extends State2

sealed trait Data2
case object Uninitialized2 extends Data2
//TODO create CursorAndReturnState/LoopingState
final case class Cursor(var it: Iterator[ClientNotification], returnState: State2) extends Data2
final case class PushProcessData(cursor: Cursor, cn: ClientNotification, refreshFailed: AtomicBoolean) extends Data2

case class ClientNotification(i: Int)
case class DeclarantDetails(i: Int)

class Repo {
  def fetch(csid: String): Future[List[ClientNotification]] = ???
  def delete(cn: ClientNotification): Future[Boolean] = ???
  def release(csid: String, lockOwnerId: String): Future[Unit] = ???
}

class Declarant {
  def fetch(csid: String): Future[Option[DeclarantDetails]] = ???
}

class Push {
  def send(d: DeclarantDetails, cn: ClientNotification): Future[Unit] = ???
}

class Pull {
  def send(cn: ClientNotification): Future[Unit] = ???
}

class ClientWorker(
  csid: String,
  lockOwnerId: String,
  repo: Repo,
  declarant: Declarant,
  push: Push,
  pull: Pull
) extends FSM[State2, Data2] with ActorLogging {

  val lockRefreshFailed = new AtomicBoolean(false)

  private def exit() = {
    self ! LockReleaseEvt
    goto(Exit)
  }

  private def info(state: State2, msg: String) = log.info(s"${stateFmt(state)}: $state $msg")
  private def stateFmt(state: State2) = {
    val zero = 0
    val four = 4
    state.toString.substring(zero, four).toUpperCase
  }

  startWith(PushInit, Uninitialized2)

  when(PushInit, stateTimeout = 1 second) {
    case Event(StartEvt, Uninitialized2) =>
      info(PushInit, "Init")
      self ! LoopingInit(PushLookupDeclarant)
      goto(Looping)
  }

  when(Looping, stateTimeout = 1 second) {
    case Event(LoopingInit(returnState), _) =>
      repo.fetch(csid).map(cnList => FetchedEvt(cnList.iterator, returnState)) pipeTo self
      stay
    case Event(FetchedEvt(it, returnState), _) =>
      info(returnState, s"Looping init fetched.hasNext=${it.hasNext}")
      if (it.hasNext) {
        self ! NextRequestEvt
        stay using Cursor(it, returnState)
      } else {
        info(returnState, s"Looping empty cursor - exiting")
        exit()
      }
    case Event(Status.Failure(e), Cursor(_, returnState)) =>
      info(returnState, s"$returnState ERROR!" + e.getMessage)
      exit()
    case Event(NextRequestEvt, c@Cursor(it, returnState)) =>
      info(returnState, s"Looping next" + c)
      if (it.hasNext) {
        info(returnState, "Looping has next")
        self ! NextResponseEvt
        goto(returnState) using(PushProcessData(c, it.next, lockRefreshFailed))
      } else {
        info(returnState, s"Looping end of cursor - about to do another fetch")
        self ! LoopingInit(returnState)
        stay using Uninitialized2
      }
    case Event(Status.Failure(e), _) => // TODO: find equivalent of NonFatal processing
      log.info(s"Looping fetch ERROR! " + e.getMessage)
      // TODO: confirm
      exit()
  }

  when(PushLookupDeclarant, stateTimeout = 1 second) {
    case Event(NextResponseEvt, PushProcessData(_, cn, refreshFailed)) =>
      info(PushLookupDeclarant, "PushDeclarantDetail:" + cn)
      if (refreshFailed.get) {
        info(PushLookupDeclarant, "refresh failed")
        goto(Exit)
      } else {
        declarant.fetch(csid).map(o => LookedUpDeclarantEvt(o)) pipeTo self
      }
      stay
    case Event(d@LookedUpDeclarantEvt(Some(declarant)), p@PushProcessData(_, cn, _)) =>
      info(PushLookupDeclarant, s"looked up declarant: $d")
      self ! PushEvt(declarant)
      goto(PushSend) using p
    case Event(LookedUpDeclarantEvt(None), p:PushProcessData) =>
      info(PushLookupDeclarant, s"looked up of declarant is None")
      goto(Exit) using p
    case Event(Status.Failure(e), _) =>
      info(PushLookupDeclarant, "ERROR!" + e.getMessage)
      exit()
  }

  when(PushSend, stateTimeout = 1 second) {
    case Event(PushEvt(declarant), p@PushProcessData(cursor, cn, _)) =>
      info(PushSend, s"Push requested for $cn")
      push.send(declarant, cn).map(_ => PushedEvt) pipeTo self
      stay
    case Event(PushedEvt, p@PushProcessData(cursor, _, _)) =>
      info(PushSend, s"Pushed OK")
      self ! DeleteEvt
      goto(Delete) using p
    case Event(Status.Failure(e), p@PushProcessData(_, cn, _)) => // TODO: find equivalent of NonFatal processing
      info(PushSend, s"Push send of $cn returned an ERROR: " + e.getMessage)
      self ! PullSendEvt
      goto(PullSend) using p
  }

  when(Delete, stateTimeout = 1 second) {
    case Event(DeleteEvt, p@PushProcessData(cursor, cn, _)) =>
      info(cursor.returnState, s"about to delete $cn")
      repo.delete(cn).map(deleted => DeletedEvt(deleted)) pipeTo self
      stay
    case Event(DeletedEvt(deleted), p@PushProcessData(cursor, cn, _)) =>
      info(cursor.returnState, s"deleted $cn OK")
      //TODO: ignore delete failures for now
      //TODO: when in PULL state ensure we stop pull loop and start again from PUSH state
      self ! NextRequestEvt
      goto(Looping) using cursor
  }

  when(PullSend, stateTimeout = 1 second) {
    case Event(PullSendEvt, p@PushProcessData(cursor, cn, _)) =>
      info(PullSend, s"About to Pull send $cn")
      pull.send(cn).map(_ => PullSentEvt) pipeTo self
      stay using p.copy(cursor.copy(returnState = PullSend))
    case Event(PullSentEvt, p@PushProcessData(cursor, cn, _)) =>
      info(PullSend, s"Pull sent OK for $cn")
      self ! DeleteEvt
      goto(Delete) using p
    case Event(Status.Failure(e), PushProcessData(_, cn, _)) => // TODO: find equivalent of NonFatal processing
      info(PullSend, s"Pull send ERROR! for $cn" + e.getMessage)
      // TODO: confirm
      exit()
  }

  when(Exit, stateTimeout = 1 second) {
    case Event(LockReleaseEvt, _) =>
      info(Exit, "EnterReleaseLockEvt")
      repo.release(csid, lockOwnerId).map(_ => LockReleasedEvt) pipeTo self
      // log stop
      stay
    case Event(LockReleasedEvt, _) =>
      info(Exit, "LockReleasedEvt")
      stop
    case Event(Status.Failure(e), _) =>
      info(Exit, "ERROR!" + e.getMessage)
      stop
  }

  initialize() // start timers etc here
}