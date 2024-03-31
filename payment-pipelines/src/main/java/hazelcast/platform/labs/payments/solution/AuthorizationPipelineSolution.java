package hazelcast.platform.labs.payments.solution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.datamodel.Tuple2;
import com.hazelcast.jet.kafka.KafkaSinks;
import com.hazelcast.jet.kafka.KafkaSources;
import com.hazelcast.jet.pipeline.*;
import hazelcast.platform.labs.payments.CardState;
import hazelcast.platform.labs.payments.domain.Card;
import hazelcast.platform.labs.payments.domain.CardEntryProcessor;
import hazelcast.platform.labs.payments.domain.Names;
import hazelcast.platform.labs.payments.domain.Transaction;

import java.util.Map;
import java.util.Properties;

public class AuthorizationPipelineSolution {

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
        StreamSource<Map.Entry<String, String>> source = KafkaSources.kafka(kafkaProperties, inputTopic);

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


        /*
         * Create a Sink for updating the cards map using an EntryProcessor
         */
        Sink<Transaction> cardMapSink = Sinks.mapWithEntryProcessor(
                Names.CARD_MAP_NAME,
                Transaction::getCardNumber,
                txn -> new CardEntryProcessor(txn.getAmount()));

        /*
         * Read a stream of Map.Entry<String,String> from the stream. entry.key is the card number and
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
         * Set the grouping key to the cardNumber ( txn.getCardNumber).  For transactions that have not yet been
         * declined (i.e. status is NEW), use mapUsingIMap to retrieve the Card from the "cards" map and verify it
         * is not locked.  If it is locked, set the status to DECLINED_LOCKED
         */
        StreamStage<Transaction> postLocked = postBigTxn.groupingKey(Transaction::getCardNumber)
                .<Card, Transaction>mapUsingIMap(Names.CARD_MAP_NAME, (txn, card) -> {
                    // don't run this check for a transaction that has already been declined
                    if (txn.getStatus() == Transaction.Status.NEW) {
                        if (card.getLocked()) txn.setStatus(Transaction.Status.DECLINED_LOCKED);
                    }
                    return txn;
                }).setName("check for locked card");

        /*
         * Again, set the grouping key to the card number, which creates a StreamStageWithKey and routes the
         * events to a specific node for each card number.  Use mapStateful to both check that there is
         * available credit ( txn.getAmount() + card.getAuthorizedDollars() <= card.getCreditLimitDollars() )
         * and to update the total dollars authorized so far. The state should be kept in a "CardState" object.
         *
         * Why does the stream need to be keyed ?
         *    With a non-keyed StreamStage, all mapStateful() stages will use the same state object.
         *    With a StreamStageWithKey, there will be one state object per key (cc#), which is what we want
         *
         * Why not just directly access the "cards" IMap ?
         *    IMaps don't participate in snapshotting but the state object used by mapStateful does.
         *    This means that, when the stream is re-deployed for any reason, the state in the IMap
         *    will not be in sync with the stream.  For example, the stream might revert to 1 minute ago
         *    but the IMap entry would include authorized dollars from more recent authorization events
         *    and a transaction that was previously approved could get declined during reprocessing.
         */
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
                        (mapper, txn) -> Tuple2.tuple2(txn.getCardNumber(), mapper.writeValueAsString(txn)))
                .setName("format response").writeTo(sink);

        /*
         * Also, if the transaction was approved, then update the total authorized dollars in the cards map.
         * If the transaction was not approved then the authorized dollar amount would not change.
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
        pipeline.setPreserveOrder(true);   // we need the credit check stage to be executed serially for a given card
        JobConfig jobConfig = new JobConfig();
        jobConfig.setName("Fraud Checker");
        HazelcastInstance hz = Hazelcast.bootstrappedInstance();
        hz.getJet().newJob(pipeline, jobConfig);
    }
}
