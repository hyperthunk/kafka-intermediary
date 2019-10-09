package org.spikes.test;

import org.apache.camel.LoggingLevel;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
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

import static org.spikes.test.TestData.makeBody;

public class KafkaIntermediaryIT extends CamelTestSupport {

    // because the implementation of 'getFirstMappedPort' in KafkaContainer
    // is broken in respect of docker implementations on some platforms... :/
    private static class TestKafkaContainer extends KafkaContainer {
        TestKafkaContainer(String confluentPlatformVersion) {
            super(confluentPlatformVersion);
        }

        Integer getBrokerPort() { return proxy.getFirstMappedPort(); }
    }

    private static final String CONFLUENT_PLATFORM_VERSION = "5.1.1";

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
        Logger.getLogger(KafkaIntermediaryIT.class).debug(
                "[IT] Kafka bootstrap brokers " + bootstrapServers);
        final String host = kafka.getContainerIpAddress();
        final Integer port = kafka.getBrokerPort();
        Logger.getLogger(KafkaIntermediaryIT.class).debug(
                "[IT] Kafka bootstrap addr=" + host + ", port=" + port.toString());
        SpringApplication application = new SpringApplication(MySpringBootApplication.class);
        Properties properties = new Properties();
        properties.put("kafkaInitHost", host);
        properties.put("kafkaInitPort", port);
        application.setDefaultProperties(properties);
        intermediary = application.run();
    }

    private static void createTopics() {
        createTopic(TestData.TOPIC_ALL);
        createTopic(TestData.TOPIC_101);
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
        return "kafka:" + TestData.TOPIC_ALL + "?brokers=" + kafka.getBootstrapServers();
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
                            .to(getAllEventsURI())
                            .end();

                    from(getEvents101URI() +
                            "&groupId=A1&consumersCount=1&breakOnFirstError=true&autoOffsetReset=earliest") // getKafkaConsumerURI()
                            .routeId("test-from-kafka")
                            .to(TestData.MOCK_VISIBLE_EVENTS)
                            .end();
                }
            };
    }

    @Test
    public void givenThreeInFivePrivateMessagesTwoAreVisible() throws InterruptedException {
        MockEndpoint visibleEvents = context().getEndpoint(TestData.MOCK_VISIBLE_EVENTS, MockEndpoint.class);

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

        // paying no attention to the order (as it's not the point of this test)
        // we assert the two visible objects we've seen

        Object[] matched = bodies.stream().takeWhile(s -> s.contains("groupID: 101")).toArray();
        visibleEvents.expectedBodiesReceivedInAnyOrder(matched);
        visibleEvents.assertIsSatisfied(60000);
    }

    // enable generic camel debugging output
    @Override
    public boolean isDumpRouteCoverage() { return true; }

}
