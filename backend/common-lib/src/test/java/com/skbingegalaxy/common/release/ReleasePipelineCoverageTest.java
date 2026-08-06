package com.skbingegalaxy.common.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Does every service that exists actually get shipped?
 *
 * <p>distribution-service was written, migrated, given Kubernetes manifests and a
 * database — and never built, pushed, scanned, deployed or verified, because it was
 * absent from five loops in the Jenkinsfile. Nothing failed. A pipeline cannot report a
 * service it has never heard of, so the gap was invisible from every direction: the code
 * compiled, the tests passed, the manifests were valid, and production simply did not
 * have it.
 *
 * <p>The same class of gap made the backend image builds impossible: each ran
 * {@code docker build .} from inside the service directory while every backend Dockerfile
 * needs the aggregator pom and common-lib in its context.
 *
 * <p>These assertions are deliberately structural rather than clever. They ask only
 * "is each service named everywhere it must be named", which is exactly the question
 * nobody thinks to ask when adding the eleventh service.
 */
@DisplayName("Release pipeline covers every service")
class ReleasePipelineCoverageTest {

    /**
     * The repository root, from a module's surefire working directory
     * ({@code backend/common-lib}).
     */
    private static final Path REPO_ROOT = Paths.get("..", "..").normalize();
    private static final Path BACKEND = REPO_ROOT.resolve("backend");

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    /**
     * Present only in a full checkout. Absent when the module is built inside the
     * Docker image build, whose context is {@code backend/} — and where tests are
     * skipped anyway.
     */
    private static void requireCheckout(Path path) {
        assumeTrue(Files.exists(path),
            "Skipped: " + path + " is not present, so this is not a full checkout.");
    }

    /** Every backend module that produces a deployable image. */
    private static List<String> serviceDirectoriesWithDockerfiles() {
        try (Stream<Path> entries = Files.list(BACKEND)) {
            return entries
                .filter(Files::isDirectory)
                .filter(p -> Files.exists(p.resolve("Dockerfile")))
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list " + BACKEND.toAbsolutePath(), e);
        }
    }

    @Nested
    @DisplayName("Jenkinsfile")
    class Pipeline {

        @Test
        @DisplayName("names every backend service in the build, push, scan and rollout lists")
        void everyServiceIsShipped() {
            Path jenkinsfile = REPO_ROOT.resolve("Jenkinsfile");
            requireCheckout(jenkinsfile);
            String pipeline = read(jenkinsfile);

            String backendServices = valueOf(pipeline, "BACKEND_SERVICES");
            String allImages = valueOf(pipeline, "ALL_IMAGES");
            String deployments = valueOf(pipeline, "K8S_DEPLOYMENTS");

            for (String service : serviceDirectoriesWithDockerfiles()) {
                assertThat(backendServices)
                    .as("%s has a Dockerfile but is never built by the pipeline", service)
                    .contains(service);
                assertThat(allImages)
                    .as("%s is built but never pushed or scanned", service)
                    .contains(service);
                // discovery-server is a StatefulSet, verified in its own loop.
                if (!service.equals("discovery-server")) {
                    assertThat(deployments)
                        .as("%s is deployed but its rollout is never verified", service)
                        .contains(service);
                }
            }
        }

        @Test
        @DisplayName("builds backend images from the backend/ context, never the service directory")
        void buildContextIsTheAggregator() {
            Path jenkinsfile = REPO_ROOT.resolve("Jenkinsfile");
            requireCheckout(jenkinsfile);
            String pipeline = read(jenkinsfile);

            // Every backend Dockerfile is `COPY . .` + `mvn -pl <svc> -am`, so its
            // context must contain the aggregator pom and common-lib. Building from
            // backend/<svc> gave Docker a context with neither.
            assertThat(pipeline)
                .as("backend images must be built with -f <svc>/Dockerfile from backend/")
                .contains("-f ${svc}/Dockerfile");

            for (String service : serviceDirectoriesWithDockerfiles()) {
                assertThat(pipeline)
                    .as("dir('backend/%s') puts docker build in a context with no parent pom", service)
                    .doesNotContain("dir('backend/" + service + "')");
            }
        }

        @Test
        @DisplayName("passes the kubernetes Maven profile into the image build")
        void imagesCarryKubernetesDiscovery() {
            Path jenkinsfile = REPO_ROOT.resolve("Jenkinsfile");
            requireCheckout(jenkinsfile);

            // k8s/namespace.yml sets SPRING_PROFILES_ACTIVE=kubernetes, which disables
            // Eureka and switches to spring.cloud.kubernetes discovery — support that
            // ships only under this Maven profile. Without it the pods have no
            // discovery mechanism at all, and the gateway can route to nothing.
            assertThat(read(jenkinsfile))
                .contains("--build-arg MAVEN_EXTRA_ARGS")
                .contains("-Pkubernetes");
        }

        /** Reads {@code NAME = 'value'} out of the environment block. */
        private String valueOf(String pipeline, String name) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "\\s*=\\s*'([^']*)'")
                .matcher(pipeline);
            assertThat(m.find()).as("Jenkinsfile declares %s", name).isTrue();
            return m.group(1);
        }
    }

    @Nested
    @DisplayName("Kubernetes")
    class Manifests {

        @Test
        @DisplayName("every deployed service can be reached by the gateway")
        void gatewayCanReachEveryService() {
            Path networkPolicy = REPO_ROOT.resolve("k8s/network-policy.yml");
            Path services = REPO_ROOT.resolve("k8s/services.yml");
            requireCheckout(networkPolicy);
            requireCheckout(services);

            String policy = read(networkPolicy);
            String deployed = read(services);

            // default-deny-ingress selects every pod, so a service absent from
            // allow-gateway-to-backends is silently unreachable: the pod is healthy,
            // the route exists, and the request simply times out.
            for (String service : serviceDirectoriesWithDockerfiles()) {
                if (!deployed.contains("name: " + service)) continue;   // not deployed here
                if (service.equals("api-gateway")) continue;            // it IS the gateway
                assertThat(policy)
                    .as("%s is deployed but no NetworkPolicy lets the gateway reach it", service)
                    .contains(service);
            }
        }

        @Test
        @DisplayName("every service database has credentials and a backup")
        void everyDatabaseIsProvisionedAndBackedUp() {
            Path secrets = REPO_ROOT.resolve("scripts/sync-k8s-secrets.sh");
            Path backups = REPO_ROOT.resolve("k8s/backups.yml");
            Path services = REPO_ROOT.resolve("k8s/services.yml");
            requireCheckout(secrets);
            requireCheckout(backups);
            requireCheckout(services);

            String secretSync = read(secrets);
            String backupJob = read(backups);

            // Each deployment naming a <name>-db-creds secret needs the sync script to
            // create it, or the pod CrashLoopBackOffs on a missing secretKeyRef — a
            // failure that reads as a broken service rather than a missing line here.
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name: ([a-z-]+)-db-creds")
                .matcher(read(services));
            int found = 0;
            while (m.find()) {
                found++;
                String db = m.group(1);
                assertThat(secretSync)
                    .as("k8s/services.yml mounts %s-db-creds but nothing creates it", db)
                    .contains(db + "-db-creds");
                assertThat(backupJob)
                    .as("%s_db is deployed but never backed up", db)
                    .contains(db + "_db");
            }
            assertThat(found).as("expected per-service database credentials").isPositive();
        }
    }
}
