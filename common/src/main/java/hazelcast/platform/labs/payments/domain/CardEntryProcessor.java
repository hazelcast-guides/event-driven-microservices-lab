package hazelcast.platform.labs.payments.domain;

import com.hazelcast.map.EntryProcessor;

import java.util.Map;

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
