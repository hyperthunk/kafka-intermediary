package org.spikes.test;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.apache.commons.exec.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.apache.camel.component.mock.MockEndpoint.assertIsSatisfied;
import static org.awaitility.Awaitility.*;

import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.spikes.test.TestData.*;

// we've got a rather complex set of steps to produce this test suite
public class KafkaAclUsageIT extends CamelTestSupport {

    private final String INTERMEDIARY_ROUTE_ID = "group-101-membership-filter";
    private DockerComposeContainer kafka2;

    @Before
    @Override
    public void setUp() throws Exception {
        if (OS.isFamilyWindows())
            log.warn("Test Suite requires cygwin or MinGW to run on Windows...");

        super.setUp();

        execCertChainScript();
        kafka2 = new DockerComposeContainer(new File("docker-compose.yml"))
                .withLocalCompose(true)
                .waitingFor("kafka-setup-complete", Wait.forListeningPort())
                .withExposedService("kafka2", KAFKA_PLAINTEXT_TEST_PORT)
                .withExposedService("kafka2", KAFKA_SSL_TEST_PORT);

        context().addRoutes(intermediaryRouteBuilder());
        context().startRoute(INTERMEDIARY_ROUTE_ID);
    }

    @After
    @Override
    public void tearDown () throws Exception {
        super.tearDown();
        cleanupCertChain();
    }

    //TODO: using shell scripts like this is potentially non-portable.
    // We should be able to use the java cryptography APIs to generate
    // and sign certificates and create trust stores, which would work
    // across all dev and test environments.

    private void execCertChainScript() throws IOException {
        execCertChainScript("create-certs.sh");
    }

    private void cleanupCertChain() throws IOException {
        execCertChainScript("clean-certs.sh");
    }

    private void execCertChainScript(String exec) throws IOException {
        final var testDir = new File("scripts/security");
        final var command =
                String.format("cd %s && %s", testDir.getAbsolutePath(), exec);
        final var cliCmd = new CommandLine(command);

        final DefaultExecuteResultHandler resultHandler =
                new DefaultExecuteResultHandler();

        // we've given this 3 mins...
        final ExecuteWatchdog watchdog = new ExecuteWatchdog(180*1000);
        final Executor executor = new org.apache.commons.exec.DefaultExecutor();

        executor.setExitValue(0);
        executor.setWatchdog(watchdog);
        executor.execute(cliCmd, resultHandler);

        waitAtMost(3, TimeUnit.MINUTES).until(resultHandler::hasResult);
        assertThat(resultHandler.getExitValue(), is(equalTo(0)));
    }


    private RouteBuilder intermediaryRouteBuilder() {
        final var kafkaHost = getKafkaHost();
        final var consumerURI = consumerURI(kafkaHost, INTERMEDIARY_CLIENT_ID);
        final var producerURI = producerURI(kafkaHost, INTERMEDIARY_CLIENT_ID, TOPIC_101);

        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from(consumerURI)
                    .routeId(INTERMEDIARY_ROUTE_ID)
                    .to("log:org.spikes?level=DEBUG")
                    .filter(jsonpath(GROUPID_FILTER_EXPR))
                    .to(producerURI)
                    .end();
            }
        };
    }

    private String getKafkaHost() {
        return kafka2.getServiceHost("kafka2", KAFKA_SSL_TEST_PORT);
    }

    @Test
    public void verifyTestClientCannotReadUnderlyingTopic() throws Exception {
        final var failureEx = Stream.builder();
        final var consumerURI = consumerURI(getKafkaHost(), EXTERNAL_CLIENT_ID);
        final var invalidRouteId = "invalid-route-client-cannot-consume";
        final var mockEndpointId = "mock:neverExpectsToReceiveEvents";

        final RouteBuilder builder = new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                final var proc = Optional.ofNullable(defaultErrorHandler().getFailureProcessor());
                defaultErrorHandler().setFailureProcessor(exchange -> {
                    failureEx.accept(exchange);
                    if (proc.isPresent()) proc.get().process(exchange);
                });

                from(consumerURI).routeId(invalidRouteId).to(mockEndpointId);
            }
        };

        final var context = context();
        context.addRoutes(builder);
        context.startRoute(invalidRouteId);

        MockEndpoint visibleEvents = context.getEndpoint(MOCK_VISIBLE_EVENTS, MockEndpoint.class);

        visibleEvents.expectedMessageCount(0);
        visibleEvents.assertIsSatisfied(15 * 1000);
    }

}
