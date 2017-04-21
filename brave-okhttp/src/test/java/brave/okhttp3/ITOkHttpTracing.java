package brave.okhttp3;

import brave.http.ITHttpClient;
import brave.parser.Parser;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ITOkHttpTracing extends ITHttpClient<Call.Factory> {

  @Override protected Call.Factory newClient(int port) {
    return configureClient(OkHttpTracing.create(tracing));
  }

  Call.Factory configureClient(OkHttpTracing instrumentation) {
    return instrumentation.callFactory(new OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build());
  }

  @Override protected Call.Factory newClient(int port, Supplier<String> spanName) {
    return configureClient(OkHttpTracing.builder(tracing)
        .config(new OkHttpTracing.Config() {
          @Override protected Parser<Request, String> spanNameParser() {
            return ctx -> spanName.get();
          }
        }).build());
  }

  @Override protected void closeClient(Call.Factory client) throws IOException {
    ((OkHttpTracing.TracingCallFactory) client).ok.dispatcher().executorService().shutdownNow();
  }

  @Override protected void get(Call.Factory client, String pathIncludingQuery)
      throws IOException {
    client.newCall(new Request.Builder().url(server.url(pathIncludingQuery)).build())
        .execute();
  }

  @Override protected void getAsync(Call.Factory client, String pathIncludingQuery)
      throws Exception {
    client.newCall(new Request.Builder().url(server.url(pathIncludingQuery)).build())
        .enqueue(new Callback() {
          @Override public void onFailure(Call call, IOException e) {
            e.printStackTrace();
          }

          @Override public void onResponse(Call call, Response response) throws IOException {
          }
        });
  }

  @Test public void currentSpanVisibleToUserInterceptors() throws Exception {
    server.enqueue(new MockResponse());
    closeClient(client);

    client = OkHttpTracing.create(tracing).callFactory(new OkHttpClient.Builder()
        .addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
            .addHeader("my-id", tracing.currentTraceContext().get().traceIdString())
            .build()))
        .build());

    get(client, "/foo");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
        .isEqualTo(request.getHeader("my-id"));
  }
}
