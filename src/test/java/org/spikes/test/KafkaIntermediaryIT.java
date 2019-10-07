package org.spikes.test;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultExchange;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.jboss.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.spikes.MySpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.KafkaContainer;

import java.util.List;
import java.util.Properties;

public class KafkaIntermediaryIT extends CamelTestSupport {

    // because the implementation of 'getFirstMappedPort' in KafkaContainer
    // is broken in respect of docker implementations on some platforms... :/
    private static class TestKafkaContainer extends KafkaContainer {
        public TestKafkaContainer(String confluentPlatformVersion) {
            super(confluentPlatformVersion);
        }

        public Integer getBrokerPort() { return proxy.getFirstMappedPort(); }
    }

    private static final String TOPIC_ALL = "events_all";
    private static final String TOPIC_101 = "events_101";
    private static final String CONFLUENT_PLATFORM_VERSION = "5.1.1";
    private static final String MOCK_VISIBLE_EVENTS = "mock:visibleEvents";

    private static final String INPUT_JSON =
            "{events: [" +
                    "{deployment: {" +
                          "appid: 'app1'" +
                        ", groupID: 519" +
                        ", properties: []}}" +
                    "{deployment: {" +
                          "appid:'app2'" +
                        ", groupID: 318" +
                        ", properties: []}}" +
                    "{deployment: {" +
                          "appid:'app3'" +
                        ", groupID: 101" +  // this one is valid for route 101
                        ", properties: []}}" +
                    "{deployment: {" +
                          "appid:'app4'" +
                        ", groupID: 101" +  // this one is valid for route 101
                        ", properties: []}}" +
                    "{deployment: {" +
                          "appid:'app5'" +
                        ", groupID: 362" +
                        ", properties: []}}" +
                    "]}";

    // kafka env
    private static TestKafkaContainer kafka;
    private static ConfigurableApplicationContext intermediary;

    // before the test runs, we spin up kafka in a docker container

    @BeforeClass
    public static void setup() {
        kafka = new TestKafkaContainer(CONFLUENT_PLATFORM_VERSION); //.withNetwork(network);
        kafka.start();
        assertTrue("Kafka Container Startup Failed", kafka.isRunning());
        createTopics();
        startIntermediary();
    }

    @AfterClass
    public static void teardown() { intermediary.stop(); }

    private static void startIntermediary() {
        final String bootstrapServers = kafka.getBootstrapServers();
        Logger.getLogger(KafkaIntermediaryIT.class).debug("[IT] Kafka bootstrap brokers " + bootstrapServers);
        final String host = kafka.getContainerIpAddress();
        final Integer port = kafka.getBrokerPort();
        Logger.getLogger(KafkaIntermediaryIT.class).debug("[IT] Kafka bootstrap addr=" + host +
                                                          ", port=" + port.toString());
        SpringApplication application = new SpringApplication(MySpringBootApplication.class);
        Properties properties = new Properties();
        properties.put("kafkaInitHost", host);
        properties.put("kafkaInitPort", port);
        application.setDefaultProperties(properties);
        intermediary = application.run();
    }

    private static void createTopics() {
        createTopic(TOPIC_ALL);
        createTopic(TOPIC_101);
    }

    private static void createTopic(final String topic) {
        // the test kafka container uses an embedded zookeeper
        // confluent platform and Kafka compatibility 5.1.x <-> kafka 2.1.x
        // kafka 2.1.x requires --zookeeper, whilst later versions use --bootstrap-servers
        String createTopic =
                String.format("/usr/bin/kafka-topics --create --zookeeper localhost:2181"
                            + " --replication-factor 1 --partitions 1 --topic %s",
                        topic);
        try {
            final Container.ExecResult execResult =
                    kafka.execInContainer("/bin/sh", "-c", createTopic);
            if (execResult.getExitCode() != 0) fail();
        } catch (Exception e) {
            e.printStackTrace();
            fail();
        }
    }

    @NotNull
    private String getEvents101URI() {
        return "kafka:events_101?brokers=" + kafka.getBootstrapServers();
    }

    @NotNull
    private String getAllEventsURI() {
        return "kafka:" + TOPIC_ALL + "?brokers=" + kafka.getBootstrapServers();
    }

    // we provide a camel route for this test, which will consume data from
    // the output topic on which we're expected to see our filtered json data appear

    @Override
    protected RoutesBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
                @Override
                public void configure() {
                    onException(Exception.class)
                            .handled(false)
                            .log(LoggingLevel.WARN, "${exception.message}");

                    from("direct:start")
                            .routeId("test-to-kafka")
                            // .setBody(constant(INPUT_JSON))
                            .to(getAllEventsURI())
                            // .to("log:org.spikes?level=DEBUG")
                            .end();

                    from(getEvents101URI() +
                            "&groupId=A1&consumersCount=1&breakOnFirstError=true&autoOffsetReset=earliest") // getKafkaConsumerURI()
                            .routeId("test-from-kafka")
                            .process(exchange -> {
                                log.info(dumpKafkaDetails(exchange));
                            })
                            .log("Message received from Kafka : ${body}")
                            .log("    on the topic ${headers[kafka.TOPIC]}")
                            .log("    on the partition ${headers[kafka.PARTITION]}")
                            .log("    with the offset ${headers[kafka.OFFSET]}")
                            .log("    with the key ${headers[kafka.KEY]}")
                            .to(MOCK_VISIBLE_EVENTS)
                            .end();
                }
            };
    }

    @NotNull
    private String makeBody(final String appName, final String groupId) {
        return "{deployment: {appid:'"
                + appName
                + "', groupID: "
                + groupId
                + ", properties: []}}";
    }

    private String dumpKafkaDetails(Exchange exchange) {
        StringBuilder sb = new StringBuilder();
        sb.append("Message Received from topic:").append(exchange.getIn().getHeader(KafkaConstants.TOPIC));
        sb.append("\r\n");
        sb.append("Message Received from partition:").append(exchange.getIn().getHeader(KafkaConstants.PARTITION));
        sb.append(" with partition key:").append(exchange.getIn().getHeader(KafkaConstants.PARTITION_KEY));
        sb.append("\r\n");
        sb.append("Message offset:").append(exchange.getIn().getHeader(KafkaConstants.OFFSET));
        sb.append("\r\n");
        sb.append("Message last record:").append(exchange.getIn().getHeader(KafkaConstants.LAST_RECORD_BEFORE_COMMIT));
        sb.append("\r\n");
        sb.append("Message Received:").append(exchange.getIn().getBody());
        sb.append("\r\n");

        return sb.toString();
    }

    @Test
    public void givenThreeInFivePrivateMessagesTwoAreVisible() throws InterruptedException {
        MockEndpoint visibleEvents = context().getEndpoint(MOCK_VISIBLE_EVENTS, MockEndpoint.class);

        final List<String> bodies =
                List.of(  makeBody("app1", "101")
                        , makeBody("app2", "352")
                        , makeBody("app3", "614")
                        , makeBody("app4", "101")
                        , makeBody("app5", "101"));

        bodies.forEach(b -> { template().sendBody("direct:start", b); });

        // we expect our mock endpoint to receive 2 message exchanges
        visibleEvents.expectedMessageCount(3);

                    /*.withProcessor(
                            exchange -> {
                                exchange.getOut().setHeader(KafkaConstants.KEY, "deployments");
                                exchange.getOut().setBody(INPUT_JSON);
                            })
                    .to(getKafkaProducerURI())
                    .send();*/

        // log.debug(deployments.toString());

        // paying no attention to the order (as it's not the point of this test)
        // we assert the two visible objects we've seen

        // visibleEvents.expectedBodiesReceivedInAnyOrder(v1, v2);
        //wait(10000);
        visibleEvents.assertIsSatisfied(60000);
    }

    // enable generic camel debugging output
    @Override
    public boolean isDumpRouteCoverage() { return true; }

}
