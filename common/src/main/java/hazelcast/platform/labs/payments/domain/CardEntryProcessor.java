package hazelcast.platform.labs.payments.domain;

import com.hazelcast.map.EntryProcessor;

import java.util.Map;

/*
 * This EntryProcessor is used to safely update the total authorized dollars on entries
 * in the "cards" map.  EntryProcessors are a Hazelcast mechanism for performing read/update
 * operations safely, without the need for holding a lock or other concurrency management
 * techniques.  See https://docs.hazelcast.org/docs/5.3.5/javadoc/index.html?com/hazelcast/map/EntryProcessor.html
 * for more.
 */
public class CardEntryProcessor implements EntryProcessor<String, Card, Boolean> {
    public CardEntryProcessor(int amount) {
        this.amount = amount;
    }
    private int amount;
    @Override
    public Boolean process(Map.Entry<String, Card> entry) {
        Card card = entry.getValue();
        card.addAuthorizedDollars(amount);
        entry.setValue(card);
        return card.getAuthorizedDollars() <= card.getCreditLimitDollars();
    }
}
