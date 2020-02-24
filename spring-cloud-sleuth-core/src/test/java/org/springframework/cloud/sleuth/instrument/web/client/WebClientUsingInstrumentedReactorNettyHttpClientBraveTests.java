/*
 * Copyright 2013-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.web.client;

import java.util.concurrent.atomic.AtomicReference;

import brave.http.HttpTracing;
import brave.test.http.ITHttpAsyncClient;
import org.junit.Ignore;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.netty.http.client.HttpClient;
import reactor.util.context.Context;
import zipkin2.Callback;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * This runs Brave's integration tests with duplicate instrumentation, which is normal
 * when reactor is in use.
 */
@Ignore("TODO: this is currently unusable due to ")
public class WebClientUsingInstrumentedReactorNettyHttpClientBraveTests
		extends ITHttpAsyncClient<WebClient> {

	/**
	 * This uses Spring to instrument the {@link WebClient} using a
	 * {@link BeanPostProcessor}.
	 */
	@Override
	protected WebClient newClient(int port) {
		AnnotationConfigApplicationContext result = new AnnotationConfigApplicationContext();
		result.registerBean(HttpTracing.class, () -> httpTracing);
		result.register(WebClientBuilderConfiguration.class);
		result.register(TraceWebClientBeanPostProcessor.class);
		result.refresh();
		return result.getBean(WebClient.Builder.class).baseUrl("http://127.0.0.1:" + port)
				.build();
	}

	@Override
	protected void closeClient(WebClient client) {
		// WebClient is not Closeable
	}

	@Override
	protected void get(WebClient client, String pathIncludingQuery) {
		client.get().uri(pathIncludingQuery).exchange().block();
	}

	@Override
	protected void post(WebClient client, String pathIncludingQuery, String body) {
		client.post().uri(pathIncludingQuery).body(BodyInserters.fromValue(body))
				.exchange().block();
	}

	@Override
	protected void getAsync(WebClient client, String path, Callback<Void> callback) {
		Mono<ClientResponse> request = client.get().uri(path).exchange();

		request.subscribe(new CoreSubscriber<ClientResponse>() {

			final AtomicReference<Subscription> ref = new AtomicReference<>();

			@Override
			public void onSubscribe(Subscription s) {
				if (Operators.validate(ref.getAndSet(s), s)) {
					s.request(Long.MAX_VALUE);
				}
				else {
					s.cancel();
				}
			}

			@Override
			public void onNext(ClientResponse t) {
				Subscription s = ref.getAndSet(null);
				if (s != null) {
					callback.onSuccess(null);
					s.cancel();
				}
				else {
					Operators.onNextDropped(t, currentContext());
				}
			}

			@Override
			public void onError(Throwable t) {
				if (ref.getAndSet(null) != null) {
					callback.onError(t);
				}
			}

			@Override
			public void onComplete() {
				if (ref.getAndSet(null) != null) {
					callback.onSuccess(null);
				}
			}

			@Override
			public Context currentContext() {
				return Context.empty();
			}
		});
	}

	/** This fakes auto-configuration which would configure reactor's HttpClient */
	@Configuration
	@Import(HttpClientBeanPostProcessor.class)
	static class WebClientBuilderConfiguration {

		@Bean
		HttpClient httpClient() {
			return ReactorNettyHttpClientBraveTests.testHttpClient();
		}

		@Bean
		WebClient.Builder webClientBuilder(HttpClient httpClient) {
			return WebClient.builder()
					.clientConnector(new ReactorClientHttpConnector(httpClient));
		}

	}

}
