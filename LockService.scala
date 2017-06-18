package transaction

import akka.actor.{ActorSystem, ActorRef, Props}

sealed trait LockServiceAPI

case class ServerAcquire(clientId: Int, lock: BigInt) extends LockServiceAPI // Msg to coordinate server
case class ServerRelease(clientId: Int, lock: BigInt) extends LockServiceAPI
case class LockGrant(lock: BigInt) extends LockServiceAPI

case class Ignore(clientId: Int) extends LockServiceAPI
case class Recover(clientId: Int) extends LockServiceAPI

object LockService {

	def apply(system: ActorSystem): ActorRef = {
		val lockServer = system.actorOf(LockServer.props(), "LockServer")
		lockServer
	}
}