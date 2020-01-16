package reactivefeign.cloud;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import feign.RequestLine;
import feign.RetryableException;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.simple.SimpleDiscoveryProperties;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.loadbalancer.core.RoundRobinLoadBalancer;
import org.springframework.cloud.loadbalancer.support.ServiceInstanceListSuppliers;
import org.springframework.cloud.loadbalancer.support.SimpleObjectProvider;
import reactivefeign.publisher.retry.OutOfRetriesException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static reactivefeign.retry.BasicReactiveRetryPolicy.retry;

/**
 * @author Sergii Karpenko
 */
public class LoadBalancingReactiveHttpClientTest {

    public static final String MONO_URL = "/mono";
    public static final String FLUX_URL = "/flux";

    @ClassRule
    public static WireMockClassRule server1 = new WireMockClassRule(wireMockConfig().dynamicPort());
    @ClassRule
    public static WireMockClassRule server2 = new WireMockClassRule(wireMockConfig().dynamicPort());

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private static String serviceName = "LoadBalancingReactiveHttpClientTest-loadBalancingDefaultPolicyRoundRobin";

    private static ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;

    @BeforeClass
    public static void setupServersList() {
        loadBalancerFactory = loadBalancerFactory(serviceName, server1.port(), server2.port());
    }

    public static ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory(String serviceName, int... ports) {
        return serviceId -> new RoundRobinLoadBalancer(
                new SimpleObjectProvider<>(ServiceInstanceListSuppliers.from(serviceName,
                        IntStream.of(ports).mapToObj(port ->
                                new SimpleDiscoveryProperties.SimpleServiceInstance(URI.create("http://localhost:"+port)))
                        .toArray(ServiceInstance[]::new))),
                serviceName);
    }

    @Before
    public void resetServers() {
        server1.resetAll();
        server2.resetAll();
    }

    @Test
    public void shouldLoadBalanceRequests() {
        String body = "success!";
        mockSuccessMono(server1, body);
        mockSuccessMono(server2, body);

        TestMonoInterface client = BuilderUtils.<TestMonoInterface>cloudBuilder()
                .enableLoadBalancer(loadBalancerFactory)
                .disableHystrix()
                .target(TestMonoInterface.class, serviceName, "http://" + serviceName);

        String result1 = client.getMono().block();
        String result2 = client.getMono().block();

        assertThat(result1)
                .isEqualTo(result2)
                .isEqualTo(body);

        server1.verify(1, getRequestedFor(urlEqualTo(MONO_URL)));
        server2.verify(1, getRequestedFor(urlEqualTo(MONO_URL)));
    }

    @Test
    public void shouldLoadBalanceFluxRequests() {
        String body = "[1, 2]";
        mockSuccessFlux(server1, body);
        mockSuccessFlux(server2, body);

        TestFluxInterface client = BuilderUtils.<TestFluxInterface>cloudBuilder()
                .enableLoadBalancer(loadBalancerFactory)
                .disableHystrix()
                .target(TestFluxInterface.class, serviceName, "http://" + serviceName);

        List<Integer> result1 = client.getFlux().collectList().block();
        List<Integer> result2 = client.getFlux().collectList().block();

        assertThat(result1)
                .isEqualTo(result2);

        server1.verify(1, getRequestedFor(urlEqualTo(FLUX_URL)));
        server2.verify(1, getRequestedFor(urlEqualTo(FLUX_URL)));
    }

    @Test
    public void shouldFailAsPolicyWoRetries() {

        expectedException.expect(RetryableException.class);

        try {
            loadBalancingWithRetry(2, 0, 0);
        } catch (Throwable t) {
            assertThat(server1.getAllServeEvents().size() == 1
                    ^ server2.getAllServeEvents().size() == 1);
            throw t;
        }
    }

    @Test
    public void shouldRetryOnSameAndFail() {

        expectedException.expect(OutOfRetriesException.class);

        try {
            loadBalancingWithRetry(2, 1, 0);
        } catch (Throwable t) {
            assertThat(server1.getAllServeEvents().size() == 2
                    ^ server2.getAllServeEvents().size() == 2);
            throw t;
        }
    }

    @Test
    public void shouldRetryOnNextAndFail() {

        try {
            loadBalancingWithRetry(2, 1, 1);
        } catch (Throwable t) {
            assertThat(server1.getAllServeEvents().size() == 2
                    && server2.getAllServeEvents().size() == 2);
            assertThat(t).isInstanceOf(OutOfRetriesException.class);
            assertThat(t.getCause()).isInstanceOf(OutOfRetriesException.class);
            assertThat(t.getCause().getCause()).isInstanceOf(RetryableException.class);
        }
    }

    @Test
    public void shouldRetryOnSameAndSuccess() {

        loadBalancingWithRetry(2, 2, 0);

        assertThat(server1.getAllServeEvents().size() == 3
                ^ server2.getAllServeEvents().size() == 3);

    }

    private void loadBalancingWithRetry(int failedAttemptsNo, int retryOnSame, int retryOnNext) {
        String body = "success!";
        Stream.of(server1, server2).forEach(server -> {
            mockSuccessAfterSeveralAttempts(server, MONO_URL,
                    failedAttemptsNo, 503,
                    aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body));
        });

        TestMonoInterface client = BuilderUtils.<TestMonoInterface>cloudBuilder()
                .enableLoadBalancer(loadBalancerFactory)
                .retryOnNextServer(retry(retryOnNext))
                .disableHystrix()
                .retryWhen(retry(retryOnSame))
                .target(TestMonoInterface.class, serviceName, "http://" + serviceName);

        String result = client.getMono().block();
        assertThat(result).isEqualTo(body);
    }

    @Test
    public void shouldRetryOnSameAndSuccessWithWarning() {

        loadBalancingWithRetryWithWarning(2, 2, 0);

        assertThat(server1.getAllServeEvents().size() == 3
                ^ server2.getAllServeEvents().size() == 3);

    }

    private void loadBalancingWithRetryWithWarning(int failedAttemptsNo, int retryOnSame, int retryOnNext) {
        String body = "success!";
        Stream.of(server1, server2).forEach(server -> {
            mockSuccessAfterSeveralAttempts(server, MONO_URL,
                    failedAttemptsNo, 503,
                    aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(body));
        });

        TestMonoInterface client = BuilderUtils.<TestMonoInterface>cloudBuilder()
                .enableLoadBalancer(loadBalancerFactory)
                .retryOnNextServer(retry(retryOnNext))
                .retryWhen(retry(retryOnSame))
                .disableHystrix()
                .target(TestMonoInterface.class, serviceName, "http://" + serviceName);

        String result = client.getMono().block();
        assertThat(result).isEqualTo(body);
    }

    static void mockSuccessMono(WireMockClassRule server, String body) {
        server.stubFor(get(urlEqualTo(MONO_URL))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    static void mockSuccessFlux(WireMockClassRule server, String body) {
        server.stubFor(get(urlEqualTo(FLUX_URL))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    static void mockSuccessAfterSeveralAttempts(WireMockServer server, String url,
                                                int failedAttemptsNo, int errorCode, ResponseDefinitionBuilder response) {
        String state = STARTED;
        for (int attempt = 0; attempt < failedAttemptsNo; attempt++) {
            String nextState = "attempt" + attempt;
            server.stubFor(get(urlEqualTo(url))
                    .inScenario("testScenario")
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse()
                            .withStatus(errorCode)
                            .withHeader("Retry-After", "1"))
                    .willSetStateTo(nextState));

            state = nextState;
        }

        server.stubFor(get(urlEqualTo(url))
                .inScenario("testScenario")
                .whenScenarioStateIs(state)
                .willReturn(response));
    }


    interface TestMonoInterface {

        @RequestLine("GET "+MONO_URL)
        Mono<String> getMono();
    }

    interface TestFluxInterface {

        @RequestLine("GET "+FLUX_URL)
        Flux<Integer> getFlux();
    }
}
