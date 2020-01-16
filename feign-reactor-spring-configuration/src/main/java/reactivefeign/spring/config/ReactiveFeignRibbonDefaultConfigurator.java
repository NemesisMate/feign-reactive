package reactivefeign.spring.config;

import com.netflix.client.DefaultLoadBalancerRetryHandler;
import com.netflix.client.RetryHandler;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import reactivefeign.ReactiveFeignBuilder;
import reactivefeign.cloud.CloudReactiveFeign;
import reactivefeign.retry.ReactiveRetryPolicy;
import reactivefeign.retry.SimpleReactiveRetryPolicy;

//Uses ribbon configuration
// used for backward compatibility with feign
public class ReactiveFeignRibbonDefaultConfigurator extends AbstractReactiveFeignConfigurator{

    protected ReactiveFeignRibbonDefaultConfigurator() {
        super(5);
    }

    @Override
    public ReactiveFeignBuilder configure(ReactiveFeignBuilder builder, ReactiveFeignNamedContext namedContext) {
        CloudReactiveFeign.Builder cloudBuilder = (CloudReactiveFeign.Builder)builder;
        if(!namedContext.isRetryConfigured()) {
            RetryHandler retryHandler = getRibbonRetryHandler(namedContext);
            if (retryHandler.getMaxRetriesOnSameServer() > 0) {
                cloudBuilder = cloudBuilder.retryWhen(retryOnSame(retryHandler));
            }
            if (retryHandler.getMaxRetriesOnNextServer() > 0) {
                cloudBuilder = cloudBuilder.retryOnNextServer(retryOnNext(retryHandler));
            }
        }
        return cloudBuilder;
    }

    private ReactiveRetryPolicy retryOnNext(RetryHandler defaultRetryHandler) {
        return new SimpleReactiveRetryPolicy(){
            @Override
            public long retryDelay(Throwable error, int attemptNo) {
                return defaultRetryHandler.isRetriableException(error, false)
                        && attemptNo <= defaultRetryHandler.getMaxRetriesOnNextServer()
                        ? 0 : -1;
            }
        };
    }

    private ReactiveRetryPolicy retryOnSame(RetryHandler defaultRetryHandler) {
        return new SimpleReactiveRetryPolicy(){
            @Override
            public long retryDelay(Throwable error, int attemptNo) {
                return defaultRetryHandler.isRetriableException(error, true)
                        && attemptNo <= defaultRetryHandler.getMaxRetriesOnSameServer()
                        ? 0 : -1;
            }
        };
    }

    static RetryHandler getRibbonRetryHandler(ReactiveFeignNamedContext context) {
        SpringClientFactory springClientFactory = context.getOptional(SpringClientFactory.class);
        IClientConfig clientConfig;
        if(springClientFactory != null){
            clientConfig = springClientFactory.getClientConfig(context.getClientName());
        } else {
            clientConfig = DefaultClientConfigImpl.getClientConfigWithDefaultValues(context.getClientName());
        }

        return new DefaultLoadBalancerRetryHandler(clientConfig);
    }

}
