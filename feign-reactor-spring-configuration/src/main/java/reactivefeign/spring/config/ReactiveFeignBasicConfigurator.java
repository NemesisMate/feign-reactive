/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package reactivefeign.spring.config;

import feign.codec.ErrorDecoder;
import reactivefeign.ReactiveFeignBuilder;
import reactivefeign.ReactiveOptions;
import reactivefeign.client.ReactiveHttpRequestInterceptor;
import reactivefeign.client.log.ReactiveLoggerListener;
import reactivefeign.client.statushandler.ReactiveStatusHandler;
import reactivefeign.client.statushandler.ReactiveStatusHandlers;
import reactivefeign.retry.ReactiveRetryPolicy;

import java.util.Map;
import java.util.Objects;


public class ReactiveFeignBasicConfigurator extends AbstractReactiveFeignConfigurator{

	protected ReactiveFeignBasicConfigurator() {
		super(1);
	}

	@Override
	public ReactiveFeignBuilder configure(
			ReactiveFeignBuilder builder,
			ReactiveFeignNamedContext namedContext) {

		if (namedContext.getProperties().isDefaultToProperties()) {
			builder = configureUsingConfiguration(builder, namedContext);
			for(ReactiveFeignClientsProperties.ReactiveFeignClientProperties<?> config : namedContext.getConfigsReverted()){
				builder = configureUsingProperties(builder, namedContext, config);
			}
		} else {
			for(ReactiveFeignClientsProperties.ReactiveFeignClientProperties<?> config : namedContext.getConfigsReverted()){
				builder = configureUsingProperties(builder, namedContext, config);
			}
			builder = configureUsingConfiguration(builder, namedContext);
		}
		return builder;
	}

	private ReactiveFeignBuilder configureUsingConfiguration(ReactiveFeignBuilder builder, ReactiveFeignNamedContext namedContext) {
		ReactiveOptions options = namedContext.getOptional(ReactiveOptions.class);
		if (options != null) {
			builder = builder.options(options);
		}

		ReactiveRetryPolicy retryPolicy = namedContext.getOptional(ReactiveRetryPolicy.class);
		if (retryPolicy != null) {
			throw new IllegalArgumentException("Use ReactiveRetryPolicies instead of ReactiveRetryPolicy");
		}

		ReactiveRetryPolicies retryPolicies = namedContext.getOptional(ReactiveRetryPolicies.class);
		if (retryPolicies != null && retryPolicies.getRetryOnSame() != null) {
			builder = builder.retryWhen(retryPolicies.getRetryOnSame());
			namedContext.setRetryConfigured();
		}

		Map<String, ReactiveHttpRequestInterceptor> requestInterceptors = namedContext.getAll(ReactiveHttpRequestInterceptor.class);
		if (requestInterceptors != null) {
			for(ReactiveHttpRequestInterceptor interceptor : requestInterceptors.values()){
				builder = builder.addRequestInterceptor(interceptor);
			}
		}

		ReactiveStatusHandler statusHandler = namedContext.getOptional(ReactiveStatusHandler.class);
		if(statusHandler == null){
			ErrorDecoder errorDecoder = namedContext.getOptional(ErrorDecoder.class);
			if(errorDecoder != null) {
				statusHandler = ReactiveStatusHandlers.errorDecoder(errorDecoder);
			}
		}
		if (statusHandler != null) {
			builder = builder.statusHandler(statusHandler);
		}

		namedContext.getAll(ReactiveLoggerListener.class).values()
				.forEach(builder::addLoggerListener);

		return builder;
	}

	private ReactiveFeignBuilder configureUsingProperties(
			ReactiveFeignBuilder builder,
			ReactiveFeignNamedContext namedContext,
			ReactiveFeignClientsProperties.ReactiveFeignClientProperties<?> config){
		if (config == null) {
			return builder;
		}

		ReactiveOptions.Builder optionsBuilder = config.getOptions();
		if(optionsBuilder != null){
			builder = builder.options(optionsBuilder.build());
		}

		if (config.getRetry() != null) {
			throw new IllegalArgumentException("Use retryOnSame or retryOnNext instead of retry");
		}

		if (config.getRetryOnSame() != null) {
			ReactiveRetryPolicy retryOnSamePolicy = configureRetryPolicyFromProperties(namedContext, config.getRetryOnSame());
			builder = builder.retryWhen(retryOnSamePolicy);
			namedContext.setRetryConfigured();
		}

		if (config.getRequestInterceptors() != null && !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<ReactiveHttpRequestInterceptor> interceptorClass : config.getRequestInterceptors()) {
				ReactiveHttpRequestInterceptor interceptor = namedContext.getOrInstantiate(interceptorClass);
				builder = builder.addRequestInterceptor(interceptor);
			}
		}

		if (config.getStatusHandler() != null) {
			ReactiveStatusHandler statusHandler = namedContext.getOrInstantiate(config.getStatusHandler());
			builder = builder.statusHandler(statusHandler);
		} else if(config.getErrorDecoder() != null){
			ErrorDecoder errorDecoder = namedContext.getOrInstantiate(config.getErrorDecoder());
			builder = builder.statusHandler(ReactiveStatusHandlers.errorDecoder(errorDecoder));
		}

		if(config.getLogger() != null){
			builder = builder.addLoggerListener(namedContext.getOrInstantiate(config.getLogger()));
		}

		if(config.getMetricsLogger() != null){
			builder = builder.addLoggerListener(namedContext.getOrInstantiate(config.getMetricsLogger()));
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder = builder.decode404();
			}
		}

		if (Objects.nonNull(config.getContract())) {
			builder = builder.contract(namedContext.getOrInstantiate(config.getContract()));
		}
		return builder;
	}

	static ReactiveRetryPolicy configureRetryPolicyFromProperties(
	        ReactiveFeignNamedContext namedContext, ReactiveFeignClientsProperties.RetryProperties retryProperties) {
		ReactiveRetryPolicy retryPolicy = null;
		if(retryProperties.getPolicy() != null){
			retryPolicy = namedContext.getOrInstantiate(retryProperties.getPolicy());
		}
		if(retryPolicy == null){
			ReactiveRetryPolicy.Builder retryPolicyBuilder = namedContext.getOrInstantiate(
					retryProperties.getBuilder(), retryProperties.getArgs());
			retryPolicy = retryPolicyBuilder.build();
		}
		return retryPolicy;
	}
}
