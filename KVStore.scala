package transaction

import akka.actor.{Actor, ActorRef, Props, Cancellable}

import scala.concurrent.duration._
import scala.concurrent.Await

import akka.pattern.ask
import akka.util.Timeout
import akka.event.Logging

sealed trait KVStoreAPI
case class Put(key: BigInt, value: Any) extends KVStoreAPI
case class Get(key: BigInt) extends KVStoreAPI
case class Attempt(txId: Int, entries: scala.collection.mutable.Map[BigInt, Any], clientId: Int) extends KVStoreAPI
case class StoreCommit(txId: Int) extends KVStoreAPI
case class StoreAbort(txId: Int) extends KVStoreAPI
case class StoreIgnore(clientId: Int) extends KVStoreAPI


/**
 * KVStore is a local key-value store based on actors.  Each store actor controls a portion of
 * the key space and maintains a hash of values for the keys in its portion.  The keys are 128 bits
 * (BigInt), and the values are of type Any.
 */

class KVStore extends Actor {
  import context._
  val generator = new scala.util.Random
  val abortProb : Int = 0
  implicit val timeout = Timeout(10 seconds)
  private val store = new scala.collection.mutable.HashMap[BigInt, Any]
  private val txCache = new scala.collection.mutable.HashMap[Int, scala.collection.mutable.Map[BigInt, Any]]
  var cancellables : Map[Int, Cancellable] = Map()
  val log = Logging(system, this)
  var ignoredClients : Set[Int] = Set()

  override def receive = {
    case Put(key, cell) =>
      sender ! store.put(key,cell)
    case Get(key) =>
      sender ! store.get(key)
    case Attempt(txId, entries, clientId) => 
      handleAttempt(txId, entries, sender, clientId)
    case StoreCommit(txId) =>
      handleCommit(txId)
    case StoreAbort(txId) =>
      handleAbort(txId)
    case StoreIgnore(clientId) =>
      ignoredClients += clientId

  }

  private def handleAttempt(txId: Int, entries: scala.collection.mutable.Map[BigInt, Any], sender: ActorRef, clientId: Int) = {
    //log.info(s"store server attempting tx ${txId}")
    if (!(ignoredClients contains clientId)) {
    var c = system.scheduler.scheduleOnce(timeout.duration) {
      txCache -= txId
    }
    cancellables += (txId -> c)
    val sample = generator.nextInt(100)
    if (sample < abortProb) {
      sender ! false
    } else {
      txCache.put(txId, entries)
      sender ! true
    }
  }
  }

  private def handleCommit(txId: Int) = { 
    if (!(ignoredClients contains txId)) {
      //log.info(s"store server committing tx ${txId}")
      for ((key, value) <- txCache(txId))
        store.put(key, value)
      if (cancellables contains txId) {
        cancellables(txId).cancel()
        cancellables -= txId
      }
      txCache -= txId
    }
  }

  private def handleAbort(txId: Int) = {
    if (!(ignoredClients contains txId)) {
     // log.info(s"store server aborting tx ${txId}")
      if (cancellables contains txId) {
        cancellables(txId).cancel()
        cancellables -= txId
      }
      if (txCache contains txId) {
        txCache -= txId
      }
   
    }
  }
}

object KVStore {
  def props(): Props = {
     Props(classOf[KVStore])
  }
}
