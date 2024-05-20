module common {
    requires com.hazelcast.core;
    requires javafaker;
    requires com.fasterxml.jackson.annotation;
    exports hazelcast.platform.labs.payments.domain;
}