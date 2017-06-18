package transaction

import akka.actor.{Actor, ActorSystem, ActorRef, Props}
import akka.event.Logging
import java.math.BigInteger

sealed trait ApplicationAPI

case class Burst(numIterations: Int, numKeys: Int, delta: Int) extends ApplicationAPI
case class RandomTransfer(numAccounts: Int, numTransfers: Int) extends ApplicationAPI

class ApplicationActor(val id: Int, storeServers: Seq[ActorRef], lockServer: ActorRef) extends Actor {
	val kvClient = new KVClient(storeServers, lockServer, id)
	val log = Logging(context.system, this)
 	val generator = new scala.util.Random
	def receive() = {

		case Burst(numIterations, numKeys, delta) => 
			burst(numIterations, numKeys, delta)
		case RandomTransfer(numAccounts, numTransfers) =>
			randomTransfer(numAccounts, numTransfers)

	}

	private def burst(numIterations: Int, numKeys: Int, delta: Int) = {
		var locks : Set[BigInt] = Set()
			for (i <- 0 until numKeys) 
				locks += new BigInt(new BigInteger(i.toString))
			while(!kvClient.begin(locks)){
				// begin tx failed, retry...
			}	
			log.info(s"app actor ${id} acquired all locks!")
			for (i <- 0 until numIterations) {
				for (j <- 0 until numKeys) {
					var x : Int = kvClient.read(j).get.asInstanceOf[Int]
					x = x + delta
					kvClient.write(j, x)
				}
			}
			if (kvClient.commit()) {
				log.info(s"app actor ${id} committed successfully")
			}
			else {
				log.info(s"app actor ${id} failed to commit")
			}
			sender ! "done"
	}

	private def randomTransfer(numAccounts: Int, numTransfers: Int) = {

		for (i <- 0 until numTransfers) {
			val from = generator.nextInt(numAccounts)
			val to = generator.nextInt(numAccounts)
			val amount = generator.nextInt(100)
			while(!kvClient.begin(Set(from, to))) {
				// retry...
			}
			var x : Int = kvClient.read(from).get.asInstanceOf[Int]
			x = x - amount
			kvClient.write(from, x)
			var y : Int = kvClient.read(to).get.asInstanceOf[Int]
			y = y + amount
			kvClient.write(to, y)
			if (kvClient.commit()) {
				//log.info(s"app actor ${id} committed successfully")
			}
		}
		sender ! "done"
	}

}

object ApplicationActor {
  def props(id: Int, storeServers: Seq[ActorRef], lockServer: ActorRef): Props = {
    Props(classOf[ApplicationActor], id, storeServers, lockServer)
  }
}


