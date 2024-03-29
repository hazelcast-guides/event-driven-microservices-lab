package hazelcast.platform.labs.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.kafka.KafkaSinks;
import com.hazelcast.jet.kafka.KafkaSources;
import com.hazelcast.jet.pipeline.*;
import hazelcast.platform.labs.payments.domain.Card;
import hazelcast.platform.labs.payments.domain.CardEntryProcessor;
import hazelcast.platform.labs.payments.domain.Names;
import hazelcast.platform.labs.payments.domain.Transaction;

import java.util.Map;
import java.util.Properties;

public class AuthorizationPipeline {

    private static Properties kafkaProperties(String bootstrapServers){
        Properties kafkaConnectionProps = new Properties();
        kafkaConnectionProps.setProperty("bootstrap.servers", bootstrapServers);
        kafkaConnectionProps.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaConnectionProps.setProperty("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaConnectionProps.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        kafkaConnectionProps.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        kafkaConnectionProps.setProperty("auto.offset.reset", "latest");
        return kafkaConnectionProps;
    }

    public static Pipeline createPipeline(String kafkaBootstrapServers, String inputTopic, String outputTopic){
        Pipeline pipeline = Pipeline.create();

        Properties kafkaProperties = kafkaProperties(kafkaBootstrapServers);

        /*
         * Create a Source to read from the input topic
         */
        StreamSource<Map.Entry<String, String>> source =
                KafkaSources.kafka(kafkaProperties, inputTopic);

        /*
         * Create a Sink to write to the output topic. This sink will expect a Map.Entry<K,V> with the
         * entry key specifying the message key and the entry value specifying the message value
         */
        Sink<Map.Entry<String, String>> sink = KafkaSinks.kafka(kafkaProperties, outputTopic);

        /*
         * We need to parse and format JSON. We use Jackson for that.  We don't want to create a new
         * instance of ObjectMapper every time an event is processed. Instead, we create a "service"
         * which Hazelcast will instantiate once (per node) and re-use during event processing.
         */
        ServiceFactory<?, ObjectMapper> jsonService = ServiceFactories.sharedService(ctx -> new ObjectMapper());


        Sink<Transaction> cardMapSink = Sinks.mapWithEntryProcessor(
                Names.CARD_MAP_NAME,
                Transaction::getCardNumber,
                txn -> new CardEntryProcessor(txn.getAmount()));

        /*
         * Read a stream of Map.Entry<String,String> from the stream. entry.key is the cc# and
         * entry.value is a json string similar to the one shown below
         *
         * {
         *   "card_number": "6771-8952-0704-5425",
         *   "transaction_id": "1710969754",
         *   "amount": 42,
         *   "merchant_id": "8222",
         *   "status": "NEW"
         * }
         *
         */
        StreamStage<Map.Entry<String, String>> cardTransactions =
                pipeline.readFrom(source).withIngestionTimestamps().setName("read topic");

        /*
         * Use the json service to parse the JSON message into an instance
         * of Transaction.
         */

        StreamStage<Transaction> transactions = cardTransactions.mapUsingService(
                jsonService,
                (svc, entry) -> svc.readValue(entry.getValue(), Transaction.class)).setName("parse");


        /*
         * Decline the transaction if the amount exceeds 5,000, set the status to DECLINED_BIG_TXN
         */
        StreamStage<Transaction> postBigTxn =
                transactions.map(txn -> {
                    if (txn.getAmount() > 5000) txn.setStatus(Transaction.Status.DECLINED_BIG_TXN);
                    return txn;
                }).setName("check for big transactions");

        /*
         * For transactions that have not yet been declined, set the grouping key to the card number
         * and then use mapUsingIMap to retrieve the Card from the "cards" map and verify it is not locked
         */
        StreamStage<Transaction> postLocked = postBigTxn.groupingKey(Transaction::getCardNumber)
                .<Card, Transaction>mapUsingIMap(Names.CARD_MAP_NAME, (txn, card) -> {
                    // don't run this check for a transaction that has already been declined
                    if (txn.getStatus() == Transaction.Status.NEW) {
                        if (card.getLocked()) txn.setStatus(Transaction.Status.DECLINED_LOCKED);
                    }
                    return txn;
                }).setName("check for locked card");


        StreamStage<Transaction> postAuthorized = postLocked.groupingKey(Transaction::getCardNumber)
                .mapStateful(
                        CardState::new,
                        (cardState, key, txn) ->
                                txn.getStatus() == Transaction.Status.NEW ? cardState.checkCreditLimit(txn) : txn
                );

        /*
         * If the event is still in the NEW state, change the status to APPROVED
         */
        StreamStage<Transaction> finalTransactions = postAuthorized.map(txn -> {
            if (txn.getStatus() == Transaction.Status.NEW) txn.setStatus(Transaction.Status.APPROVED);
            return txn;
        }).setName("final approval");


        /*
         * For each transaction, create a Map.Entry where the key is the credit card number and the value is
         * the JSON serialized transaction.  Write the resulting value to the Kafka sink
         *
         * Note:the Hazelcast Tuple2 class implements Map.Entry
         */

        finalTransactions.mapUsingService(
                        jsonService,
                        (mapper, txn) -> Tuple2.tuple2(txn.getCardNumber(), mapper.writeValueAsString(txn))
                        ).setName("format response").writeTo(sink);

        /*
         * Also, if the transaction was approved, then update the total authorized dollars in the cards map
         */
        finalTransactions.filter( txn -> txn.getStatus() == Transaction.Status.APPROVED).writeTo(cardMapSink);

        return pipeline;
    }


    // expects arguments: kafka bootstrap servers, input kafka topic, output kafka topic
    public static void main(String []args){
        if (args.length != 3){
            System.err.println("Please provide 3 arguments: kafka bootstrap servers, input kafka topic and output kafka topic");
            System.exit(1);
        }

        Pipeline pipeline = createPipeline(args[0], args[1], args[2]);
        pipeline.setPreserveOrder(true);   // this keeps transactions with the same cc# from running the same step concurrently
        pipeline.setPreserveOrder(false);   // nothing in here requires order
        JobConfig jobConfig = new JobConfig();
        jobConfig.setName("Fraud Checker");
        HazelcastInstance hz = Hazelcast.bootstrappedInstance();
        hz.getJet().newJob(pipeline, jobConfig);
    }
}
