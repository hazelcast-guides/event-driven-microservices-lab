# Overview 

A problem with Pipelines and class loaders.  

`Transaction` and `Card` are domain classes that is used by my Pipeline. The Pipeline deploys and runs initially.
Then, undeploy the Pipeline and deploy it again I get a "Transaction" can't be cast to "Transaction" error.
From the log, it appears that one of the class loaders involved is from the old job.

# Prerequisites 

You will need a development laptop with the following installed:
- Docker Desktop
- A functional Java IDE
- Maven
- __Hazelcast Command Line Client (CLC)__

To install Hazelcast CLC, see: https://docs.hazelcast.com/clc/latest/install-clc

# Overview of the Environment

Most of the components of this lab run within an isolated Docker environment. To allow 
you to interact with the components from outside the Docker environment some 
components are exposed on localhost ports.  For example, when you run CLC it will 
connect to one of the Hazelcast instances via `localhost:5701`.  The diagram below 
should help to clarify the situations.

![lab environment](resources/lab_env.png)

# Steps

```shell
mvn clean install
docker compose up -d

# create a config in CLC
clc config add docker cluster.name=dev cluster.address=localhost:5701
 
# pull up MC (http://localhost:8080 ), make sure that the Card map has been loaded (roughly 100k entries)
clc -c docker  job submit fraud-pipelines/target/fraud-pipelines-1.0-SNAPSHOT.jar redpanda:9092 transactions approvals --class hazelcast.platform.labs.payments.FraudPipeline

# let it deploy and process some events - then cancel it
clc -c docker job cancel xxxxxxxxx

# wait until it stops, then re-deploy the same job
clc -c docker  job submit fraud-pipelines/target/fraud-pipelines-1.0-SNAPSHOT.jar redpanda:9092 transactions approvals --class hazelcast.platform.labs.payments.FraudPipeline
```

The job fails with a message similar to the one below
```shell
com.hazelcast.jet.JetException: Exception in ProcessorTasklet{Fraud Checker/mapUsingPartitionedServiceAsync#9}: java.lang.ClassCastException: class hazelcast.platform.labs.payments.domain.Transaction cannot be cast to class hazelcast.platform.labs.payments.domain.Transaction (hazelcast.platform.labs.payments.domain.Transaction is in unnamed module of loader com.hazelcast.jet.impl.deployment.JetClassLoader @414f65c9; hazelcast.platform.labs.payments.domain.Transaction is in unnamed module of loader com.hazelcast.jet.impl.deployment.JetClassLoader @630b42b7)
```








