package transaction

import scala.io.Source
import akka.actor.{ActorSystem, ActorRef, Props}
import scala.concurrent.duration._
import scala.concurrent.Await

import akka.pattern.ask
import akka.util.Timeout

object TestApplication {
	val numClients : Int = 10
	val numNodes : Int = 10 // number of storage servers
	val system = ActorSystem("transactionService")

	val lockServer = LockService(system)
	implicit val timeout = Timeout(60 seconds)

	/** Storage tier: create K/V store servers */
    val stores = for (i <- 0 until numNodes)
      yield system.actorOf(KVStore.props(), "StoreActor" + i)

	val applicationActors = for (i <- 0 until numClients)
		yield system.actorOf(ApplicationActor.props(i, stores, lockServer), "ApplicationActor"+i)
	
	def main(args:Array[String]): Unit = run()

	def run() : Unit = {
		//testBasic()
		testRandomTransfers(10, 10, 100)
		system.shutdown
	}

	private def testBasic() = {
		// two application actors perform increments and decrements on the same resource.
		// check the value stored in store server after the tx's are done. 
		val numKeys = 2
		val numIterations = 3
		for(i <- 0 until numKeys) {
			val future = ask(stores(i), Put(i, 0)).mapTo[Any]
			Await.result(future, 60 seconds)
		}
		// This is to simulate network partition- tell the storage server 0 to ignore client 0
		// the tx should abort. 
		 stores(0) ! StoreIgnore(0)
		val future0 = ask(applicationActors(0), Burst(numIterations, numKeys, 1)).mapTo[Any]
		val future1 = ask(applicationActors(1), Burst(numIterations, numKeys, -1)).mapTo[Any]
		Await.result(future0, 60 seconds)
		Await.result(future1, 60 seconds)
		println("Done bursting, resulting storage entries: ")
		for(i <- 0 until numKeys) {
			val future = ask(stores(i), Get(i)).mapTo[Option[Any]]
    		val v = Await.result(future, 60 seconds)
    		if (v.isDefined) {
				println(s"${i} -> ${v.get}")
    		}
			
		}

	}

	private def testRandomTransfers(numActors: Int, numAccounts: Int, numTransfers: Int) = {
		// application actors perform random transfers betweeen bank accounts.
		// check the sum of the bank accounts at the end.
		println(s"starting random transfers, initial sum: ${numAccounts*100}")
		for(i <- 0 until numAccounts) {
			val future = ask(stores(i % stores.length), Put(i, 100)).mapTo[Any]
			Await.result(future, 60 seconds)
		}

		var futures =  for (i <- 0 until numActors)
			yield ask(applicationActors(i), RandomTransfer(numAccounts, numTransfers)).mapTo[Any]

		for(i <- 0 until numActors) {
			val v = Await.result(futures(i), 60 seconds)
		}
		var sum : Int = 0
		for(i <- 0 until numAccounts) {
			var future = ask(stores(i % stores.length), Get(i)).mapTo[Option[Any]]
			var v = Await.result(future, 60 seconds)
			if (v.isDefined) {
				sum = sum + v.get.asInstanceOf[Int]
			}
		}
		println(s"transactions done. final sum is ${sum}")
	}


}