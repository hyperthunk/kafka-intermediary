package org.spikes.test;

import org.apache.camel.LoggingLevel;
import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.junit4.CamelTestSupport;
import org.junit.Test;

import java.util.List;

public class CamelRouteConfigTests extends CamelTestSupport {

    @Override
    protected RoutesBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() {
                onException(Exception.class)
                        .handled(false)
                        .log(LoggingLevel.WARN, "${exception.message}");

                from("direct:start")
                        .routeId("test-filter-101")
                        .filter(jsonpath(TestData.GROUPID_FILTER_EXPR))
                        .to("mock:filtered")
                        .end();
            }
        };
    }

    @Test
    public void givenValidGroupIdFilterShouldReturnBody() throws InterruptedException {
        final MockEndpoint mock = context().getEndpoint("mock:filtered", MockEndpoint.class);
        final String messageBody = TestData.makeBody("app2", "101");

        mock.expectedMessageCount(1);
        mock.expectedBodiesReceivedInAnyOrder(messageBody);

        template().sendBody("direct:start", messageBody);

        mock.assertIsSatisfied();
    }

    @Test
    public void givenInvalidGroupIdFilterShouldNotReturnBody() throws InterruptedException {
        final MockEndpoint mock = context().getEndpoint("mock:filtered", MockEndpoint.class);
        final List<String> bodies =
                List.of( TestData.makeBody("app1", "101")
                       , TestData.makeBody("app2", "352")
                       , TestData.makeBody("app3", "614")
                       , TestData.makeBody("app4", "101")
                       , TestData.makeBody("app5", "101"));

        bodies.forEach(b -> { template().sendBody("direct:start", b); });

        mock.expectedMessageCount(3);
        mock.assertIsSatisfied();
    }

}
