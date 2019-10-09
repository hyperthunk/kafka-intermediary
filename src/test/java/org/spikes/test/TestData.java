package org.spikes.test;

import org.jetbrains.annotations.NotNull;

import static java.lang.String.format;

class TestData {

    static final String TOPIC_ALL = "events_all";
    static final String TOPIC_101 = "events_101";

    static final String INTERMEDIARY_CLIENT_ID = "client1";
    static final String EXTERNAL_CLIENT_ID = "client2";

    static final String MOCK_VISIBLE_EVENTS = "mock:visibleEvents";
    static final String GROUPID_FILTER_EXPR = "$..*[?(@.groupID == 101)]";

    final static Integer KAFKA_PLAINTEXT_TEST_PORT = 9090;
    final static Integer KAFKA_SSL_TEST_PORT = 9092;

    @NotNull
    static String makeBody(final String appName, final String groupId) {
        return "{deployment: {appid:'"
                + appName
                + "', groupID: "
                + groupId
                + ", properties: []}}";
    }

    @NotNull
    static String producerURI(final String kafkaHost, final String clientId) {
        return producerURI(kafkaHost, clientId, "events_101");
    }

    @NotNull
    static String producerURI(final String kafkaHost, final String clientId, final String topicName) {
        final var builder = new StringBuilder();
        // kafka:events_101?brokers={{kafkaInitHost}}:{{kafkaInitPort}}
        return kafkaURI(kafkaHost, topicName, builder)
                .append(sslParamsFor(clientId, builder))
                .toString();
    }

    @NotNull
    private static StringBuilder kafkaURI(final String kafkaHost,
                                          final String topicName,
                                          final StringBuilder builder) {
        return builder
                .append("kafka:")
                .append(topicName)
                .append("?")
                .append(getBrokers(kafkaHost));
    }

    @NotNull
    static String consumerURI(final String kafkaHost, final String clientId) {
        return consumerURI(kafkaHost, clientId, "events_all");
    }

    @NotNull
    static String consumerURI(final String kafkaHost,
                              final String clientId, final String topicName) {
        final StringBuilder builder = new StringBuilder();
        return kafkaURI(kafkaHost, topicName, builder)
                .append("&groupId=Main&consumersCount=1")
                .append("&breakOnFirstError=true&autoOffsetReset=earliest")
                .append(sslParamsFor(clientId, builder))
                .toString();
    }

    static String sslParamsFor(final String clientId, final StringBuilder builder) {
        return builder
                .append("securityProtocol=SSL&")
                .append("sslKeyPassword=password&")
                .append(makeLocationParam("sslKeystoreLocation",
                        clientId, "keystore"))
                .append("sslKeystorePassword=password&")
                .append("sslKeystoreType=JKS&")
                .append(makeLocationParam("sslTruststoreLocation",
                        clientId, "truststore"))
                .toString();
    }

    @NotNull
    private static String makeLocationParam(final String param,
                                    final String identity, final String store) {
        return format("%s=scripts/security/kafka.%s.%s.jks&",
                param, identity, store);
    }

    private static String getBrokers(String kafkaHost) {
        return format("brokers=%s:%s",
                kafkaHost, KAFKA_SSL_TEST_PORT);
    }
}
