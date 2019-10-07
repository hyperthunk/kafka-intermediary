package org.spikes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportResource;

//CHECKSTYLE:OFF
@SpringBootApplication
@ImportResource("classpath:camel-config.xml")
public class MySpringBootApplication {

    /**
     * A main method to start this application.
     */
    public static void main(String[] args) {
        SpringApplication.run(MySpringBootApplication.class, args);
    }

    /*@Component
    public class MySpringBootRouter extends RouteBuilder {

        @Override
        public void configure() {
            from("timer:hello?period={{timer.period}}").routeId("hello")
                    .transform().method("myBean", "saySomething")
                    .filter(simple("${body} contains 'foo'"))
                    .to("log:foo")
                    .end()
                    .to("stream:out");
        }

    }*/

}

//CHECKSTYLE:ON