package transaction

import akka.actor.{Actor, ActorSystem, ActorRef, Props}

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.collection.mutable.Queue

import akka.event.Logging

import akka.pattern.ask
import akka.util.Timeout

class LockServer () extends Actor {

	import context._
	//var clientMap : Map[Int, ActorRef] = Map() // int clientid -> actorRef of client
	var clientTempMap: Map[Int, ActorRef] = Map() // int clientid -> actorRef of the temp actor for replying ask
	var leaseMap : Map[BigInt, Int] = Map() // string lock -> owner
	//var cancellables : Map[String, Cancellable] = Map() // string lock -> cancellable
	var waitQueue : Map[BigInt, Queue[Int]] = Map() // string lock -> queue of client id's
 	val log = Logging(system, this)
 	var ignoreMap : Map[Int, Boolean] = Map() // int clientId -> whether should be ignored

	def receive() = {
		case ServerAcquire(clientId, lock) =>
			handleAcquire(clientId, lock, sender)
		case ServerRelease(clientId, lock) =>
			handleRelease(clientId, lock)
		
		case Ignore(clientId) =>
			ignoreMap += (clientId -> true)
		case Recover(clientId) =>
			ignoreMap += (clientId -> false)

	}

	private def handleAcquire(clientId: Int, lock : BigInt, sender: ActorRef) : Unit= {
	//	log.info(s"server handling acquire from ${clientId} " + sender)
		//log.info(leaseMap.mkString)
		clientTempMap += (clientId -> sender)

		// don't handle the requests if this client should be ignored.
		if ((ignoreMap contains clientId) && (ignoreMap(clientId))) {
			return Unit
		}

		if (!(leaseMap contains lock) || leaseMap(lock) == -1) { // granting lock
			leaseMap += (lock -> clientId) // update ownermap
			clientTempMap(clientId) ! lock			
		}
		else {
			// lock is currently occupied- so add the client to the wait queue.
			if (!(waitQueue contains lock)) {
				waitQueue += (lock -> new Queue[Int])
			} 
			if (!(waitQueue(lock) contains clientId))
				waitQueue(lock).enqueue(clientId)
				//log.info("recalling lock from " + clientMap(leaseMap(lock)))
		}

	}

	private def handleRelease(clientId: Int, lock: BigInt) = {
		if (leaseMap contains lock) {
			leaseMap -= lock
		}

		if ((waitQueue contains lock) && (!(waitQueue(lock)).isEmpty)) {
			val nextClientId = waitQueue(lock).dequeue()
			leaseMap += (lock -> nextClientId)
			clientTempMap(nextClientId) ! lock
		}
	}
}

object LockServer {
  def props(): Props = {
    Props(classOf[LockServer])
  }
}