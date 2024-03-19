package hazelcast.platform.labs.machineshop;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.ClientConnectionStrategyConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import hazelcast.platform.labs.payments.domain.Card;
import hazelcast.platform.labs.payments.domain.Names;
import hazelcast.platform.labs.viridian.ViridianConnection;

import java.util.HashMap;
import java.util.Map;

/**
 * Expects the following environment variables
 * <p>
 * HZ_SERVERS  A comma-separated list of Hazelcast servers in host:port format.  Port may be omitted.
 *             Any whitespace around the commas will be removed.  Required.
 * <p>
 * HZ_CLUSTER_NAME  The name of the Hazelcast cluster to connect.  Required.
 * <p>
 * CARD_COUNT The number of machines credit cards to load
 *
 */
public class RefdataLoader {
    private static final String HZ_SERVERS_PROP = "HZ_SERVERS";
    private static final String HZ_CLUSTER_NAME_PROP = "HZ_CLUSTER_NAME";

    private static final String CARD_COUNT_PROP = "CARD_COUNT";

    private static String []hzServers;
    private static String hzClusterName;

    private static int cardCount;

    private static String getRequiredProp(String propName){
        String prop = System.getenv(propName);
        if (prop == null){
            System.err.println("The " + propName + " property must be set");
            System.exit(1);
        }
        return prop;
    }

    private static void configure(){
        if (!ViridianConnection.viridianConfigPresent()) {
            String hzServersProp = getRequiredProp(HZ_SERVERS_PROP);
            hzServers = hzServersProp.split(",");
            for (int i = 0; i < hzServers.length; ++i) hzServers[i] = hzServers[i].trim();

            hzClusterName = getRequiredProp(HZ_CLUSTER_NAME_PROP);
        }

        String temp = getRequiredProp(CARD_COUNT_PROP);
        try {
            cardCount = Integer.parseInt(temp);
        } catch(NumberFormatException nfx){
            System.err.println("Could not parse " + temp + " as an integer");
            System.exit(1);
        }

        if (cardCount < 1 || cardCount > 10000000){
            System.err.println("Card count must be between 1 and 10,000,000 inclusive");
            System.exit(1);
        }
    }

    private static void doSQLMappings(HazelcastInstance hzClient){
//        hzClient.getSql().execute(PROFILE_MAPPING_SQL);
//        hzClient.getSql().execute(CONTROLS_MAPPING_SQL);
//        hzClient.getSql().execute(EVENT_MAPPING_SQL);
//        hzClient.getSql().execute(SYSTEM_ACTIVITIES_MAPPING_SQL);
//        hzClient.getSql().execute(MACHINE_STATUS_SUMMARY_MAPPING_SQL);
//        hzClient.getSql().execute(MACHINE_PROFILE_LOCATION_INDEX_SQL);
        System.out.println("Initialized SQL Mappings");
    }

    private static void configureMaps(HazelcastInstance hzClient){
        System.out.println("Initialized Map Configurations");
    }
    public static void main(String []args){
        configure();

        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName(hzClusterName);
        clientConfig.getNetworkConfig().addAddress(hzServers);
        clientConfig.getConnectionStrategyConfig().setAsyncStart(false);
        clientConfig.getConnectionStrategyConfig().setReconnectMode(ClientConnectionStrategyConfig.ReconnectMode.ON);

        HazelcastInstance hzClient = HazelcastClient.newHazelcastClient(clientConfig);

        doSQLMappings(hzClient);

        IMap<String, Card> cardMap = hzClient.getMap(Names.CARD_MAP_NAME);
        IMap<String, String> systemActivitiesMap = hzClient.getMap(Names.SYSTEM_ACTIVITIES_MAP_NAME);

        systemActivitiesMap.put("LOADER_STATUS","STARTED");

        int existingEntries = cardMap.size();
        int toLoad = cardCount - existingEntries;

        if (toLoad <= 0){
            System.out.println("" + existingEntries + " cards are already present");
        } else {
            Map<String, Card> batch = new HashMap<>();
            for(int i=0; i < toLoad; ++i){
                Card c = Card.fake();
                batch.put(c.getCardNumber(), c);
                int BATCH_SIZE = 1000;
                if (batch.size() == BATCH_SIZE){
                    cardMap.putAll(batch);
                    batch.clear();
                }
            }

            if (batch.size() > 0) cardMap.putAll(batch);

            if (cardCount == toLoad)
                System.out.println("Loaded " + cardCount + " cards");
            else
                System.out.println("Loaded " + toLoad + " cards bringing the total to " + cardCount);
        }
        systemActivitiesMap.put("LOADER_STATUS","FINISHED");
        hzClient.shutdown();
    }
}
