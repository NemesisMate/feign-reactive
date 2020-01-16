package reactivefeign.cloud;

import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import feign.Param;
import feign.RequestLine;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static reactivefeign.cloud.LoadBalancingReactiveHttpClientTest.loadBalancerFactory;

public class PathVariableInTargetUrlTest {

    @ClassRule
    public static WireMockClassRule server1 = new WireMockClassRule(wireMockConfig().dynamicPort());

    private static String serviceName = "PathVariableInTargetUrlTest";

    private static ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;

    @BeforeClass
    public static void setupServersList() {
        loadBalancerFactory = loadBalancerFactory(serviceName, server1.port());
    }

    @Before
    public void resetServers() {
        server1.resetAll();
    }

    @Test
    public void shouldCorrectlyProcessPathVariableInUrl(){

        String body = "Success";
        mockSuccessMono(server1, body);

        TestMonoInterface client = BuilderUtils.<TestMonoInterface>cloudBuilder()
                .enableLoadBalancer(loadBalancerFactory)
                .disableHystrix()
                .target(TestMonoInterface.class, serviceName, "http://"+serviceName+"/mono/{id}");

        StepVerifier.create(client.getMono(1))
                .expectNext(body)
                .verifyComplete();
    }

    static void mockSuccessMono(WireMockClassRule server, String body) {
        server.stubFor(get(urlPathMatching("/mono/1/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    interface TestMonoInterface {

        @RequestLine("GET")
        Mono<String> getMono(@Param("id") long id);
    }
}
