package brave.httpclient4;

import brave.http.ITHttpClient;
import brave.parser.Parser;
import java.io.IOException;
import java.util.function.Supplier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.AssumptionViolatedException;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ITHttpClientTracing extends ITHttpClient<CloseableHttpClient> {

  @Override
  protected CloseableHttpClient newClient(int port) {
    return configureClient(HttpClientTracing.create(tracing));
  }

  @Override
  protected CloseableHttpClient newClient(int port, Supplier<String> spanName) {
    return configureClient(HttpClientTracing.builder(tracing)
        .config(new HttpClientTracing.Config() {
          @Override protected Parser<HttpClientContext, String> spanNameParser() {
            return c -> spanName.get();
          }
        }).build());
  }

  private CloseableHttpClient configureClient(HttpClientTracing instrumentation) {
    return HttpClients.custom()
        .disableAutomaticRetries()
        .addInterceptorFirst(instrumentation.requestInterceptor())
        .addInterceptorFirst(instrumentation.responseInterceptor())
        .build();
  }

  @Override
  protected void closeClient(CloseableHttpClient client) throws IOException {
    client.close();
  }

  @Override protected void get(CloseableHttpClient client, String pathIncludingQuery)
      throws IOException {
    client.execute(new HttpGet(server.url(pathIncludingQuery).uri())).close();
  }

  @Override protected void getAsync(CloseableHttpClient client, String pathIncludingQuery) {
    throw new AssumptionViolatedException("This is not an async library");
  }

  @Test
  public void currentSpanVisibleToUserInterceptors() throws Exception {
    server.enqueue(new MockResponse());

    HttpClientTracing instrumentation = HttpClientTracing.create(tracing);
    try (CloseableHttpClient client = instrumentation.httpClientBuilder()
        .disableAutomaticRetries()
        .addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
          request.addHeader("my-id", tracing.currentTraceContext().get().traceIdString());
        })
        .build()) {

      client.execute(new HttpGet(server.url("/foo").uri())).close();
    }

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
        .isEqualTo(request.getHeader("my-id"));
  }

  @Override
  @Test(expected = AssertionError.class) // doesn't yet close a span on exception
  public void addsErrorTagOnTransportException() throws Exception {
    super.addsErrorTagOnTransportException();
  }

  @Override
  @Test(expected = AssertionError.class) // base url is not logged in apache
  public void httpUrlTagIncludesQueryParams() throws Exception {
    super.httpUrlTagIncludesQueryParams();
  }

  @Override
  @Test(expected = AssertionError.class) // doesn't yet close a span on exception
  public void reportsSpanOnTransportException() throws Exception {
    super.reportsSpanOnTransportException();
  }
}
