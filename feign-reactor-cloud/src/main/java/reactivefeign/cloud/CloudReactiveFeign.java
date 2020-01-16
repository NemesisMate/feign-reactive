package reactivefeign.cloud;

import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixObservableCommand;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import feign.Contract;
import feign.MethodMetadata;
import feign.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import reactivefeign.FallbackFactory;
import reactivefeign.ReactiveFeignBuilder;
import reactivefeign.ReactiveOptions;
import reactivefeign.client.ReactiveHttpRequestInterceptor;
import reactivefeign.client.ReactiveHttpResponse;
import reactivefeign.client.log.ReactiveLoggerListener;
import reactivefeign.client.statushandler.ReactiveStatusHandler;
import reactivefeign.cloud.methodhandler.HystrixMethodHandlerFactory;
import reactivefeign.cloud.publisher.LoadBalancerPublisherClient;
import reactivefeign.methodhandler.MethodHandlerFactory;
import reactivefeign.publisher.PublisherClientFactory;
import reactivefeign.publisher.PublisherHttpClient;
import reactivefeign.retry.ReactiveRetryPolicy;

import java.util.function.BiFunction;
import java.util.function.Function;

import static reactivefeign.ReactiveFeign.Builder.retry;
import static reactivefeign.retry.FilteredReactiveRetryPolicy.notRetryOn;

/**
 * Allows to specify ribbon {@link ReactiveLoadBalancer.Factory<ServiceInstance>}
 * and HystrixObservableCommand.Setter with fallback factory.
 *
 * @author Sergii Karpenko
 */
public class CloudReactiveFeign {

    private static final Logger logger = LoggerFactory.getLogger(CloudReactiveFeign.class);

    public static <T> Builder<T> builder(ReactiveFeignBuilder<T> builder) {
        return new Builder<>(builder);
    }

    public static class Builder<T> implements ReactiveFeignBuilder<T> {

        private ReactiveFeignBuilder<T> builder;
        private boolean hystrixEnabled = true;
        private SetterFactory commandSetterFactory = new DefaultSetterFactory();
        private FallbackFactory<T> fallbackFactory;
        private ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory;
        private ReactiveRetryPolicy retryOnNextServerPolicy;

        protected Builder(ReactiveFeignBuilder<T> builder) {
            this.builder = builder;
        }

        public Builder<T> disableHystrix() {
            this.hystrixEnabled = false;
            return this;
        }

        public Builder<T> setHystrixCommandSetterFactory(SetterFactory commandSetterFactory) {
            this.commandSetterFactory = commandSetterFactory;
            return this;
        }

        public Builder<T> enableLoadBalancer(
                ReactiveLoadBalancer.Factory<ServiceInstance> loadBalancerFactory){
            this.loadBalancerFactory = loadBalancerFactory;
            return this;
        }

        public Builder<T> retryOnNextServer(ReactiveRetryPolicy retryOnNextServerPolicy){
            this.retryOnNextServerPolicy = notRetryOn(retryOnNextServerPolicy, HystrixBadRequestException.class);
            return this;
        }

        @Override
        public Builder<T> fallback(T fallback) {
            return fallbackFactory(throwable -> fallback);
        }

        @Override
        public Builder<T> fallbackFactory(FallbackFactory<T> fallbackFactory) {
            this.fallbackFactory = fallbackFactory;
            return this;
        }

        @Override
        public Builder<T> contract(Contract contract) {
            builder = builder.contract(contract);
            return this;
        }

        @Override
        public Builder<T> options(ReactiveOptions options) {
            builder = builder.options(options);
            return this;
        }

        @Override
        public Builder<T> addRequestInterceptor(ReactiveHttpRequestInterceptor requestInterceptor) {
            builder = builder.addRequestInterceptor(requestInterceptor);
            return this;
        }

        @Override
        public Builder<T> addLoggerListener(ReactiveLoggerListener loggerListener) {
            builder = builder.addLoggerListener(loggerListener);
            return this;
        }

        @Override
        public Builder<T> decode404() {
            builder = builder.decode404();
            return this;
        }

        @Override
        public Builder<T> statusHandler(ReactiveStatusHandler statusHandler) {
            builder = builder.statusHandler(statusHandler);
            return this;
        }

        @Override
        public Builder<T> responseMapper(BiFunction<MethodMetadata, ReactiveHttpResponse, ReactiveHttpResponse> responseMapper) {
            builder =  builder.responseMapper(responseMapper);
            return this;
        }

        @Override
        public Builder<T> retryWhen(ReactiveRetryPolicy retryPolicy) {
            builder =  builder.retryWhen(notRetryOn(retryPolicy, HystrixBadRequestException.class));
            return this;
        }

        @Override
        public Contract contract() {
            return builder.contract();
        }

        @Override
        public MethodHandlerFactory buildReactiveMethodHandlerFactory(PublisherClientFactory reactiveClientFactory) {
            MethodHandlerFactory methodHandlerFactory = builder.buildReactiveMethodHandlerFactory(reactiveClientFactory);
            return hystrixEnabled
                    ? new HystrixMethodHandlerFactory(
					methodHandlerFactory,
                    commandSetterFactory,
                    (Function<Throwable, Object>) fallbackFactory)
                    : methodHandlerFactory;
        }

        @Override
        public PublisherClientFactory buildReactiveClientFactory() {
            PublisherClientFactory publisherClientFactory = builder.buildReactiveClientFactory();
            return new PublisherClientFactory(){

                private Target target;

                @Override
                public void target(Target target) {
                    this.target = target;
                    publisherClientFactory.target(target);
                }

                @Override
                public PublisherHttpClient create(MethodMetadata methodMetadata) {
                    PublisherHttpClient publisherClient = publisherClientFactory.create(methodMetadata);
                    if(!target.name().equals(target.url()) && loadBalancerFactory != null){
                        publisherClient = new LoadBalancerPublisherClient(
                                loadBalancerFactory.getInstance(target.name()), publisherClient);
                        if(retryOnNextServerPolicy != null){
                            publisherClient = retry(publisherClient, methodMetadata, retryOnNextServerPolicy.toRetryFunction());
                        }
                        return publisherClient;
                    } else {
                        if(retryOnNextServerPolicy != null){
                            logger.warn("retryOnNextServerPolicy will be ignored " +
                                    "as loadBalancerFactory is not configured for {} reactive feign client", target.name());
                        }
                        return publisherClient;
                    }

                }
            };
        }
    }

    public interface SetterFactory {
        HystrixObservableCommand.Setter create(Target<?> target, MethodMetadata methodMetadata);
    }

    public static class DefaultSetterFactory implements SetterFactory {
        @Override
        public HystrixObservableCommand.Setter create(Target<?> target, MethodMetadata methodMetadata) {
            String groupKey = target.name();
            String commandKey = methodMetadata.configKey();
            return HystrixObservableCommand.Setter
                    .withGroupKey(HystrixCommandGroupKey.Factory.asKey(groupKey))
                    .andCommandKey(HystrixCommandKey.Factory.asKey(commandKey));
        }
    }

}
