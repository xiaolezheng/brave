package brave.spring.resttemplate;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.TraceContext;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

public final class TracingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

  /** Creates trace interceptor with defaults. Use {@link #builder(Tracing)} to customize. */
  public static TracingClientHttpRequestInterceptor create(Tracing tracing) {
    return builder(tracing).build();
  }

  public static Builder builder(Tracing tracing) {
    return new Builder(tracing);
  }

  public static final class Builder {
    final Tracing tracing;
    Config config = new Config();

    Builder(Tracing tracing) { // intentionally hidden
      this.tracing = checkNotNull(tracing, "tracing");
    }

    public Builder config(Config config) {
      this.config = checkNotNull(config, "config");
      return this;
    }

    public TracingClientHttpRequestInterceptor build() {
      return new TracingClientHttpRequestInterceptor(this);
    }
  }

  public static class Config extends ClientHandler.Config<HttpRequest, ClientHttpResponse> {

    @Override protected Parser<HttpRequest, String> spanNameParser() {
      return r -> r.getMethod().name();
    }

    @Override protected TagsParser<HttpRequest> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL, req.getURI().toString());
    }

    @Override protected TagsParser<ClientHttpResponse> responseTagsParser() {
      return (res, span) -> {
        try {
          int httpStatus = res.getRawStatusCode();
          if (httpStatus < 200 || httpStatus > 299) {
            span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
          }
        } catch (IOException e) {
          // don't log a fake value on exception
        }
      };
    }
  }

  final Tracer tracer;
  final ClientHandler<HttpRequest, ClientHttpResponse> handler;
  final TraceContext.Injector<HttpHeaders> injector;

  TracingClientHttpRequestInterceptor(Builder builder) {
    tracer = builder.tracing.tracer();
    handler = ClientHandler.create(builder.config);
    injector = builder.tracing.propagation().injector(HttpHeaders::set);
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    Span span = tracer.nextSpan();
    handler.handleSend(request, span);
    injector.inject(span.context(), request.getHeaders());
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return handler.handleReceive(execution.execute(request, body), span);
    } catch (RuntimeException e) {
      throw handler.handleError(e, span);
    } catch (IOException e) {
      throw handler.handleError(e, span);
    }
  }
}