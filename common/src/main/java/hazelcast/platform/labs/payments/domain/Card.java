package hazelcast.platform.labs.payments.domain;

import com.github.javafaker.Faker;

public class Card {
    String cardNumber;

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    @Override
    public String toString() {
        return "Card{" +
                "cardNumber='" + cardNumber + '\'' +
                '}';
    }

    private static Faker faker = new Faker();
    public static Card fake(){
        Card result = new Card();
        result.setCardNumber(faker.finance().creditCard());
        return result;
    }

}
