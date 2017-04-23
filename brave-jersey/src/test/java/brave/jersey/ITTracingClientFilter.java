package brave.jersey;

import brave.Tracer;
import brave.http.ITHttpClient;
import brave.parser.Parser;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.filter.ClientFilter;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ITTracingClientFilter extends ITHttpClient<Client> {

  ExecutorService executor = Executors.newSingleThreadExecutor();

  @Override protected Client newClient(int port) {
    return configureClient(TracingClientFilter.create(tracing));
  }

  Client configureClient(TracingClientFilter filter) {
    Client c = Client.create();
    c.setExecutorService(currentTraceContext.executorService(executor));
    c.setReadTimeout(1000);
    c.setConnectTimeout(1000);
    c.addFilter(filter);
    return c;
  }

  @Override protected Client newClient(int port, Supplier<String> spanNamer) {
    return configureClient(TracingClientFilter.builder(tracing)
        .config(new TracingClientFilter.Config() {
          @Override protected Parser<ClientRequest, String> spanNameParser() {
            return ctx -> spanNamer.get();
          }
        }).build());
  }

  @Override protected void closeClient(Client client) throws IOException {
    executor.shutdownNow();
    client.destroy();
    client.getExecutorService().shutdownNow();
  }

  @Override protected void get(Client client, String pathIncludingQuery) throws IOException {
    client.resource(server.url(pathIncludingQuery).uri()).get(String.class);
  }

  @Override protected void getAsync(Client client, String pathIncludingQuery) throws Exception {
    client.asyncResource(server.url(pathIncludingQuery).uri()).get(String.class);
  }

  /**
   * NOTE: For other interceptors to see the {@link Tracer#currentSpan()} representing this
   * operation, this filter needs to be added last.
   */
  @Test
  public void currentSpanVisibleToUserFilters() throws Exception {
    server.enqueue(new MockResponse());
    closeClient(client);

    client = Client.create();
    client.addFilter(new ClientFilter() {
      @Override public ClientResponse handle(ClientRequest request) throws ClientHandlerException {
        request.getHeaders()
            .putSingle("my-id", tracing.currentTraceContext().get().traceIdString());
        return getNext().handle(request);
      }
    });
    // last means invoked prior to other interceptors
    client.addFilter(TracingClientFilter.create(tracing));

    get(client, "/foo");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
        .isEqualTo(request.getHeader("my-id"));
  }
}
