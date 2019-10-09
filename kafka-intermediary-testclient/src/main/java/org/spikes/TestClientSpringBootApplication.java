package org.spikes;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class TestClientSpringBootApplication {
    /**
     * A main method to start this application.
     */
    public static void main(String[] args) {
        SpringApplication.run(TestClientSpringBootApplication.class, args);
    }

    @Component
    public class MySpringBootRouter extends RouteBuilder {

        @Override
        public void configure() {
            from("undertow:http://localhost:10501")
                    .routeId("hello")
                    .choice()
                        .when(header("dest")
                                .isEqualToIgnoreCase("events_all"))
                        .to("log:foo")
                    .endChoice()
                    .end();
        }

    }
}
