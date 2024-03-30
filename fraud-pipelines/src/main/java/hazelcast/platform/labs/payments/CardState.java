package hazelcast.platform.labs.payments;


import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import hazelcast.platform.labs.payments.domain.Card;
import hazelcast.platform.labs.payments.domain.Names;
import hazelcast.platform.labs.payments.domain.Transaction;

import java.io.Serializable;

/*
 * This is used as the state object in a mapStateful call to check the available credit
 *
 */
public class CardState implements Serializable{

    /*
     * Why not just use Card ?  Because it is not java.io.Serializable
     */
    int authorizedDollars = -1;
    int creditLimitDollars = -1;

    /*
     * Note that this limit may actually modify t
     */
    public Transaction checkCreditLimit(Transaction t){
        if (creditLimitDollars == -1){
            HazelcastInstance hz = Hazelcast.getAllHazelcastInstances().iterator().next();
            IMap<String, Card> cardMap = hz.getMap(Names.CARD_MAP_NAME);
            Card card = cardMap.get(t.getCardNumber());
            if (card == null){
                throw new RuntimeException("Could not retrieve credit limit for " + t.getCardNumber());
            }
            authorizedDollars = card.getAuthorizedDollars();
            creditLimitDollars = card.getCreditLimitDollars();
        }


        if (t.getAmount() + authorizedDollars <= creditLimitDollars){
            authorizedDollars += t.getAmount();
        } else {
            t.setStatus(Transaction.Status.DECLINED_OVER_AUTH_LIMIT);
        }

        return t;
    }
}
