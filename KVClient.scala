package transaction

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{Try, Success, Failure}

import akka.actor.{Actor, ActorSystem, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
//import akka.event.Logging

class AnyMap extends scala.collection.mutable.HashMap[BigInt, Any]

class KVClient (stores: Seq[ActorRef], lockServer: ActorRef, val clientId: Int) {
  private val cache = new AnyMap
  implicit val timeout = Timeout(10 seconds)

  var acquiredLocks = scala.collection.mutable.Set[BigInt]() // all the locks acquired for the current tx
 // var storeActors : Set[ActorRef] = Set() // actorRef's of the kvstore used for the tx
  var dirtySet = scala.collection.mutable.Map[ActorRef, scala.collection.mutable.Map[BigInt, Any]]() // the entries modified by the current tx, grouped with storeserver id

  import scala.concurrent.ExecutionContext.Implicits.global

  /** Cached read */
  def read(key: BigInt): Option[Any] = {
    var value = cache.get(key)
    if (value.isEmpty) {
      value = directRead(key)
      if (value.isDefined)
        cache.put(key, value.get)
    }
    //println(s"client ${clientId} read (" + key + ", " + value.get + ")")
    value
  }

  /** Cached write: place new value in the local cache, record the update in dirtyset. */
  def write(key: BigInt, value: Any) = {
    cache.put(key, value)
    val storeRef = route(key)
    if (!(dirtySet contains storeRef)) {
      dirtySet += (storeRef -> scala.collection.mutable.Map[BigInt, Any]())
    }
    var map = dirtySet(storeRef)
    map += (key -> value)
    dirtySet += (storeRef -> map)
    //println(s"client ${clientId} write (" + key + ", " + value + ")")

  }

  // /** Push a dirtyset of cached writes through to the server. */
  // def push(dirtyset: AnyMap) = {
  //   val futures = for ((key, v) <- dirtyset)
  //     directWrite(key, v)
  //   dirtyset.clear()
  // }

  /** Purge every value from the local cache.  Note that dirty data may be lost: the caller
    * should push them.
    */
  def purge() = {
    cache.clear()
  }

  /** Direct read, bypass the cache: always a synchronous read from the store, leaving the cache unchanged. */
  def directRead (key: BigInt): Option[Any] = {
    val future = ask(route(key), Get(key)).mapTo[Option[Any]]
    Await.result(future, timeout.duration)
  }

  /** Direct write, bypass the cache: always a synchronous write to the store, leaving the cache unchanged. */
  def directWrite(key: BigInt, value: Any) = {
    val future = ask(route(key), Put(key,value)).mapTo[Option[Any]]
    Await.result(future, timeout.duration)
  }

  def begin(locks: Set[BigInt]) : Boolean = {
    var sortedLocks = locks.toSeq.sorted
    for (lock <- sortedLocks) {
      var future = ask(lockServer, ServerAcquire(clientId, lock)).mapTo[BigInt]
      val res = Try(Await.result(future, timeout.duration))
      res match {
        case Success(lock) =>
          acquiredLocks += lock
        case Failure(e) =>
          releaseLocks()
          return false
      }
    }
    // for(store <- storeActors) {
    //   store ! StoreBegin(clientId)
    // }
    return true
  }

  private def releaseLocks() = {
    for (lock <- acquiredLocks) {
      lockServer ! ServerRelease(clientId, lock)
    }
    acquiredLocks.clear()
  }


  def commit() : Boolean= {
    // commit all writes in dirty cell (2PC)
    var count : Int = 0
    for ((key, value) <- dirtySet) {
      var future = ask(key, Attempt(clientId, value, clientId)).mapTo[Any]
      val res = Try(Await.result(future, timeout.duration))
      res match {
        case Success(value) =>
          if (value.asInstanceOf[Boolean]) {
           count+=1
          }
        case Failure(e) =>
          abort()
          return false
      } 
    }
   // println(s"count is ${count}")
    if (count == dirtySet.size) {
      for (store <- dirtySet.keySet) {
        store ! StoreCommit(clientId)
      }
    }
    else {
      for (store <- dirtySet.keySet) {
        store ! StoreAbort(clientId)
      }
    }
    releaseLocks()
    cache.clear()
    dirtySet.clear()
    return true
  }


  def abort() = {
    for (store <- dirtySet.keySet) {
      store ! StoreAbort(clientId)
    }
    releaseLocks()
    cache.clear()
    dirtySet.clear()
  }

  import java.security.MessageDigest

  /** Generates a convenient hash key for an object to be written to the store.  Each object is created
    * by a given client, which gives it a sequence number that is distinct from all other objects created
    * by that client.
    */
  def hashForKey(nodeID: Int, cellSeq: Int): BigInt = {
    val label = "Node" ++ nodeID.toString ++ "+Cell" ++ cellSeq.toString
    val md: MessageDigest = MessageDigest.getInstance("MD5")
    val digest: Array[Byte] = md.digest(label.getBytes)
    BigInt(1, digest)
  }

  /**
    * @param key A key
    * @return An ActorRef for a store server that stores the key's value.
    */
  private def route(key: BigInt): ActorRef = {
    stores((key % stores.length).toInt)
  }
}
