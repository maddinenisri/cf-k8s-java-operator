package com.mdstech.opearator.cloudformation;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.Operator;
import io.javaoperatorsdk.operator.config.runtime.DefaultConfigurationService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.takes.facets.fork.FkRegex;
import org.takes.facets.fork.TkFork;
import org.takes.http.Exit;
import org.takes.http.FtBasic;

public class CFStackOperator {
    private static final Logger log = LoggerFactory.getLogger(CFStackOperator.class);

    public static void main(String[] args) throws IOException {
        log.info("CF Operator starting");
        if(System.getenv(StackController.REGION) == null || System.getenv(StackController.REGION).isEmpty()) {
            log.error("Missing region environment variables");
            System.exit(1);
        }
        Config config = new ConfigBuilder().withNamespace(null).build();
        KubernetesClient client = new DefaultKubernetesClient(config);
        Operator operator = new Operator(client, DefaultConfigurationService.instance());
        operator.register(new StackController());
        new FtBasic(new TkFork(new FkRegex("/health", "Listening on 8080")), 8080).start(Exit.NEVER);
    }
}
