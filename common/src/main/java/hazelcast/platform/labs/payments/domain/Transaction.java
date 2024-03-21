package hazelcast.platform.labs.payments.domain;

public class Transaction {
    private String cardNumber;
    private String transactionId;
    private int amount;
    private String merchantId;

    public String getCardNumber() {
        return cardNumber;
    }

    public void setCardNumber(String cardNumber) {
        this.cardNumber = cardNumber;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String merchantId) {
        this.merchantId = merchantId;
    }

    @Override
    public String toString() {
        return "Transaction{" +
                "cardNumber='" + cardNumber + '\'' +
                ", transactionId='" + transactionId + '\'' +
                ", amount=" + amount +
                ", merchantId='" + merchantId + '\'' +
                '}';
    }
}
