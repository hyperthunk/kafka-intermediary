package org.spikes.test;

import org.junit.Before;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;

// we've got a rather complex set of steps to produce this test suite
public class KafkaAclUsageIT {

    // @Before
    public void setup() {
        generateCertChain();
        new DockerComposeContainer(new File("docker-compose.yml"))
                .withLocalCompose(true)
                .waitingFor("kafka-setup-complete", Wait.forListeningPort());
    }

    private void generateCertChain() {
        // generate temp file locations
        // generate certificates
        // write certificates to files
        // return file locations
    }

}
