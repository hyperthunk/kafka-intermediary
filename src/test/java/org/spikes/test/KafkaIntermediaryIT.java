package org.spikes.test;

import org.apache.camel.Exchange;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.KafkaContainer;

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

    static final String TOPIC = "events_all";
    static final String CONFLUENT_PLATFORM_VERSION = "5.1.1";

    static final String INPUT_JSON =
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
        assertTrue("Kafka Container Startup Failed",
                    kafka.isRunning());
        /* unfortunately not implemented
        assertTrue("Kafka Container Health Check Failed",
                    kafka.isHealthy());
        */
        createTopic();
        startIntermediary();
    }

    @AfterClass
    public static void teardown() {
        intermediary.stop();
    }

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

    private static void createTopic() {
        // the test kafka container uses an embedded zookeeper
        // confluent platform and Kafka compatibility 5.1.x <-> kafka 2.1.x
        // kafka 2.1.x requires --zookeeper, whilst later versions use --bootstrap-servers
        String createTopic =
                String.format("/usr/bin/kafka-topics --create --zookeeper localhost:2181"
                            + " --replication-factor 1 --partitions 1 --topic %s",
                        TOPIC);
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
    private String getKafkaConsumerURI() {
        return "kafka:events_101?brokers=" + kafka.getBootstrapServers();
    }

    @NotNull
    private String getKafkaProducerURI() {
        return "kafka:" + TOPIC + "?brokers=" + kafka.getBootstrapServers();
    }

    // we provide a camel route for this test, which will consume data from
    // the output topic on which we're expected to see our filtered json data appear

    protected RoutesBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() {
                from(getKafkaConsumerURI())
                        .to("mock:visibleEvents")
                        .end();
            }
        };
    }

    @Test
    public void givenThreeInFivePrivateMessagesTwoAreVisible() throws InterruptedException {
        MockEndpoint visibleEvents = context().getEndpoint("mock:foo", MockEndpoint.class);
        // we expect our mock endpoint to receive 2 message exchanges
        visibleEvents.expectedMessageCount(2);

        Exchange deployments =
                fluentTemplate()
                    .withProcessor(
                            exchange -> {
                                exchange.getOut().setHeader(KafkaConstants.KEY, "deployments");
                                exchange.getOut().setBody(INPUT_JSON);
                            })
                    .to(getKafkaProducerURI())
                    .send();

        log.debug(deployments.toString());

        // paying no attention to the order (as it's not the point of this test)
        // we assert the two visible objects we've seen

        // visibleEvents.expectedBodiesReceivedInAnyOrder(v1, v2);

        visibleEvents.assertIsSatisfied();
    }

    // enable generic camel debugging output
    @Override
    public boolean isDumpRouteCoverage() { return true; }

}
