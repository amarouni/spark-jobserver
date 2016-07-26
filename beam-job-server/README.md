# Datastreams Beam Job Server
Apache Beam job server with Apache Spark backend to execute Datastreams jobs.
 
## Diff w.r.t Spark Job Server
DBJS uses the Spark Job Server base and adds the following features :

* Datastreams Beam Job Server project : Datastreams jobs & tests
* The ability to load multiple jars from a folder during Spark context creation
* The ability to choose between a local and a remote (yarn-client) Spark context when launched in yarn-client mode
* Fixes Spark version to 1.6.2 compiled with scala 2.11 
* Docker image build uses maven to fetch all of the dependencies of Datastreams, Beam & TCOMP and makes them available to Spark contexts   