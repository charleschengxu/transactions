**Archetecture**:

**ApplicationActor**: holds KVClient, and initiates transactions based on the messages it receives. Application message interface (mainly for testing purposes):
* `Burst(numIterations, numKeys, delta)`: carry out the below transaction: increment the value corresponding to all the keys from 0 to `numKeys` by `delta`, and repeat `numIteration` times. log the result (success or fail) as well as the final values in the storage servers.
* `RandomTransfer(numAccounts, numTransers)`: simulate balance transfering between bank accounts. Upon receiving this message, the application actor would carry out `numTransfers` random transfers. Each transfer is between two randomly chosen accounts (with key between 0 to `numAccounts`). The transfer amount is also randomly generated.


**LockServer**: Similar to the implementation from the last project- it provides the message interface `Acquire(lock, clientId)` and `Release(lock, clientId)`. See README for project2 for detailed explanations.  
 
**kvClient**: exposes `begin(locks)`, `read(key)`, `write(key, value)`, `commit()`, `abort()` API to the application actor. It also exposes directRead and directWrite for testing purposes. KVClient keeps a write-back cache of entries read or write by the application. To ensure cache consistency between the client and store servers, this cache is cleared after each transaction. In other words, as long as the entry is in the cache, the client is guaranteed to have exclusive right to the data entry, because it must have obtained the lock for it. And once the transaction is done, client releases all the locks, and therefore the cache should be cleared. All clients talk to a centralized lock server to acquire and release locks. Client also keeps a map `dirtySet` from KVStore (ActorRef) to the data modified by that transaction; This is the data "pushed" to the storage servers upon commit.  
* `begin(locks)`: The growing phase of the two-phase locking. Try to acquire all the locks needed for this transaction and return the result (true if success false otherwise) to application. 
Each `ask` has a fixed timeout in order to detect network partitioning, after which the method would return false to the application, and release all the locks it has acquired so far. 
In order to prevent deadlock, all clients should acquire the locks in the same order (in this case increasing order of keys). 
Upon receiving a failure message from client, the applicaiton would retry the `begin` until successful. This makes it correct to use a fixed time-out (to detect network failure) rather than using the leased lock with retry as in project 2. Though this approach may sacrifice fairness, because upon timeout (due to partitioning or lock being used by others for a long time), the client would release all the locks it has acquired so far. But this also means that other clients that are trying to acquire these locks can now make progress, whereas if the client keep renewing these locks while waiting for the needed lock, no one in need of those acquired locks would be able to make progress- and thus would be more efficient. 

* `read(key)`: A cached read for the key. If cache doesn't contain the key, `directRead` from kvStore and store the value to cache. 
* `write(key, value)` : add the new entry to the cache, and record the update in `dirtySet`.
* `commit()` : Two phase commit. First, send `Attempt(txId)` messages to all the storage servers and collect their votes. If all servers vote okay to commit, then send `StoreCommit(txId)` messages to the servers and return true (success) to the application. If due to network failure or store server vote unable to commit, the vote is not unaminous, the client then send `StoreAbort(txId)` messages to all the store servers, and return false (failure) to the application. The client also releases all the locks acquired for this transaction (shrinking phase of 2PL) and clear the cache here. 
* `abort()` : send `StoreAbort(txId)` messages to all relevant storage servers, clear the cache and release all the locks held for this transaction.

**kvStore**: the kv store based on actors. The message interface include `Put(key, value)`, `Get(key)`, `Attempt(txId, entries, clientId)`, `StoreCommit(txId)`, `StoreAbort(txId)`, `StoreIgnore(clientId)`. It also keeps a cache of "dirty" entries, which is not visible to clients. This is used for two-phase commit: upon receiving `Attempt` message, it puts the tentative entries to its cache, (with key being transactionId, and value beging the entries), and reply the vote to the client. Then upon receiving `StoreCommit(txId)` message, it writes the corresponding entries for the transaction into permanent storage. Or upon receiving `StoreAbort(txId)`, it removes the txId from the cache.
It detects network failure by `scheduleOnce` a timer to remove the cache entry for the transaction. This is scheduled upon receiving the `Attempt` message- If the server doesn't hear back from the client about the result (`StoreCommit` or `StoreAbort`) in some fixed amount of time, it assumes partitioning has happened and force abort the transaction by removing the cached entries.   
* `Put(key, value)`, `Get(key)`: same as in the ringService implementation, read/write from the permanant storage (not the cache).
* `Attempt(txId, entries, clientId)`: as described above, this is the 'prepared' phase of 2PC. The method would vote for abort with possibility `abortProb`. 
* `StoreCommit(txId)`, `StoreAbort(txId)`: The second phse of 2PC. Either write data from cache to permanent storage, or clear the cache entry for the transaction. The scheduled abort is cancelled here.
* `StoreIgnore(clientId)`: Add the clientId to the ignoredClients set. This is to simulate network partitioning between the storage server and client.

---------------------------------------------------
** handling failure cases:
* simulate network partition: This is done by sending `StoreIgnore(clientId)` message to the KVStore server, which would cause the client and store servers to time out and abort the tx. For the client, since it cound't receive unanimous votes from all stores, it sends Abort msgs to all the associociated store servers, which would remove the relevant data in the tx cache. For the store servers disconnected from the client, the timer (which is set upon receiving the Attempt() msg from the client to collect the vote) times out and it would clear the cache data associated with this tx. This way, the client and servers's view would remain consistent.   
* simulate vote to abort (maybe due to full cache of the storage)- This is done by setting the `abortProb` parameter in KVStore. This would cause the tx to force abort, and commit would return false to the application. 

---------------------------------------------------
Testing: 
TestApplication contains the functions that simulate the following scenarios. The parameters of the functions could be adjusted to simulate different workloads. See testcases.txt for the outputs of the tests.

Test cases:
1. basic: two application actors begin two transactions (involving the same keys) concurrently. One increment the value by `delta` and write back, and the other decrements the value and writes back. They both repeat `numIteration` times for all keys, and then commit. At the end of the transactions, check that the final stored values for the keys should be equal to the net sum of the two transactions. This tests for the isolation property of transaction, and that two-phase locking is implemented correctly.

2. basic transactions with network partition between kvstore and the client. This would force the tx to abort, as explained above. At the end of the transactions, check that the final stored values should be equal to the effect of only one transaction. This tests for the atomic property of transaction, and that two-phase commit is implemented correctly. 

3. simulate large amounts of random transfers between bank accounts, and test that the sum of all the bank accounts after all the transactions finish is the same as the original sum. The parameters could be picked to simulate heavy workloads. 


