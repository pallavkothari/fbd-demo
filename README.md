# FoundationDb Demo 

Links 
- [HN post](https://news.ycombinator.com/item?id=16877395)

Intro blurb from one of the comments on HN: 

	
> voidmain 3 hours ago [-]
> 
> I'll try to give you a quick introduction. The architecture talk I recorded for new engineers working on the product ran to four or five hours, I think :-). In short, it is serializable optimistic MVCC concurrency.
> A FDB transaction roughly works like this, from the client's perspective:
> 
> 1. Ask the distributed database for an appropriate (externally consistent) read version for the transaction
> 
> 2. Do reads from a consistent MVCC snapshot at that read version. No matter what other activity is happening you see an unchanging snapshot of the database. Keep track of what (ranges of) data you have read
> 
> 3. Keep track of the writes you would like to do locally.
> 
> 4. If you read something that you have written in the same transaction, use the write to satisfy the read, providing the illusion of ordering within the transaction
> 
> 5. When and if you decide to commit the transaction, send the read version, a list of ranges read and writes that you would like to do to the distributed database.
> 
> 6. The distributed database assigns a write version to the transaction and determines if, between the read and write versions, any other transaction wrote anything that this transaction read. If so there is a conflict and this transaction is aborted (the writes are simply not performed). If not then all the writes happen atomically.
> 
> 7. When the transaction is sufficiently durable the database tells the client and the client can consider the transaction committed (from an external consistency standpoint)
> 
> The implementations of 1 and 6 are not trivial, of course :-)
> 
> So a sufficiently "slow client" doing a read write transaction in a database with lots of contention might wind up retrying its own transaction indefinitely, but it can't stop other readers or writers from making progress.
> 
> It's still the case that if you want great performance overall you want to minimize conflicts between transactions!

### Local Repo

As of 4/19/18, the latest jars were not available on maven central, but you could grab them from the downloads page :P so we'll just add it to a local maven repo. 
Grab the [jar](https://www.foundationdb.org/downloads/5.1.5/bindings/java/fdb-java-5.1.5.jar) and [javadoc](https://www.foundationdb.org/downloads/5.1.5/bindings/java/fdb-java-5.1.5-javadoc.jar) and do this:

```bash
mkdir repo
mvn deploy:deploy-file -Durl=file://$PWD/repo/ -Dfile=fdb-java-5.1.5.jar -Djavadoc=fdb-java-5.1.5-javadoc.jar -DgroupId=com.apple.cie.foundationdb -DartifactId=fdb-java -Dpackaging=jar -Dversion=5.1.5
mvn install -U
```

- Followed [this](https://sookocheff.com/post/java/local-maven-repository/) page