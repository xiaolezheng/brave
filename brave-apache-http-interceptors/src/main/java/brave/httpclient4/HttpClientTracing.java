package brave.httpclient4;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.TraceContext;
import java.io.IOException;
import java.net.URI;
import org.apache.http.HttpException;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.execchain.ClientExecChain;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;
import static org.apache.http.protocol.HttpCoreContext.HTTP_RESPONSE;

public final class HttpClientTracing {

  /** Creates a tracing interceptor with defaults. Use {@link #builder(Tracing)} to customize. */
  public static HttpClientTracing create(Tracing tracing) {
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

    public HttpClientTracing build() {
      return new HttpClientTracing(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends ClientHandler.Config<HttpClientContext, HttpClientContext> {

    @Override protected Parser<HttpClientContext, String> spanNameParser() {
      return c -> c.getRequest().getRequestLine().getMethod();
    }

    @Override protected Parser<HttpClientContext, zipkin.Endpoint> responseAddressParser() {
      return new ServerAddressParser("");
    }

    @Override protected TagsParser<HttpClientContext> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL,
          req.getRequest().getRequestLine().getUri());
    }

    @Override protected TagsParser<HttpClientContext> responseTagsParser() {
      return (res, span) -> {
        int httpStatus = res.getResponse().getStatusLine().getStatusCode();
        if (httpStatus < 200 || httpStatus > 299) {
          span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
        }
      };
    }
  }

  final Tracer tracer;
  final ClientHandler<HttpClientContext, HttpClientContext> handler;
  final TraceContext.Injector<HttpMessage> injector;

  HttpClientTracing(Builder b) {
    this.tracer = b.tracing.tracer();
    this.handler = ClientHandler.create(b.config);
    this.injector = b.tracing.propagation().injector(HttpMessage::setHeader);
  }

  /**
   * Preferred way for constructing http clients, as it allows use of {@link Tracer#currentSpan}
   *
   * <p>Implicitly requires a minimum version of httpclient 4.3
   */
  public HttpClientBuilder httpClientBuilder() {
    return new HttpClientBuilder() {

      /**
       * protocol exec is the second in the execution chain, so is invoked before a request is
       * provisioned. We provision and scope a span here, so that application interceptors can see
       * it via {@link Tracer#currentSpan()}.
       */
      @Override protected ClientExecChain decorateProtocolExec(ClientExecChain exec) {
        return (route, request, context, execAware) -> {
          Span next = tracer.nextSpan();
          context.setAttribute(SpanInScope.class.getName(), tracer.withSpanInScope(next));
          try {
            return exec.execute(route, request, context, execAware);
          } catch (IOException | HttpException | RuntimeException e) {
            context.getAttribute(SpanInScope.class.getName(), SpanInScope.class).close();
            throw e;
          }
        };
      }

      /**
       * main exec is the first in the execution chain, so last to execute. This creates a concrete
       * http request, so this is where the timing in the span occurs.
       *
       * <p>This ends the span (and scoping of it) created by {@link #decorateMainExec(ClientExecChain)}.
       */
      @Override protected ClientExecChain decorateMainExec(ClientExecChain exec) {
        return (route, request, context, execAware) -> {
          Span span = tracer.currentSpan();
          try {
            handler.handleSend(HttpClientContext.adapt(context), span);
            injector.inject(span.context(), request);
            CloseableHttpResponse response = exec.execute(route, request, context, execAware);
            if (context.getResponse() == null) context.setAttribute(HTTP_RESPONSE, response);
            handler.handleReceive(HttpClientContext.adapt(context), span);
            return response;
          } catch (IOException e) { // catch repeated because handleError cannot implement multi-catch
            throw handler.handleError(e, span);
          } catch (HttpException e) {
            throw handler.handleError(e, span);
          } catch (RuntimeException e) {
            throw handler.handleError(e, span);
          } finally {
            context.getAttribute(SpanInScope.class.getName(), SpanInScope.class).close();
          }
        };
      }
    };
  }

  /**
   * The current span is explicitly propagated between the request and response via an attribute
   * named {@link Span}. User interceptors can only read the span if they know this detail. If using
   * httpcomponents 4.3+ prefer {@link #httpClientBuilder()} as {@link Tracer#currentSpan()} works
   * transparently with that approach.
   */
  public HttpRequestInterceptor requestInterceptor() {
    return (request, context) -> {
      Span span = tracer.nextSpan();
      try {
        handler.handleSend(HttpClientContext.adapt(context), span);
        injector.inject(span.context(), request);
        context.setAttribute(Span.class.getName(), span);
      } catch (RuntimeException e) {
        throw handler.handleError(e, span);
      }
    };
  }

  /** Only works and must be present when {@link #requestInterceptor()} is. */
  public HttpResponseInterceptor responseInterceptor() {
    return (response, context) -> {
      Span span = (Span) context.getAttribute(Span.class.getName());
      if (span == null) return;
      try {
        handler.handleReceive(HttpClientContext.adapt(context), span);
      } catch (RuntimeException e) {
        throw handler.handleError(e, span);
      }
    };
  }

  public static void main(String... args) throws IOException {
    HttpClientTracing tracer = HttpClientTracing.create(Tracing.newBuilder().build());

    try (CloseableHttpClient client = HttpClients.custom()
        .disableAutomaticRetries()
        .addInterceptorFirst(tracer.requestInterceptor())
        .addInterceptorFirst(tracer.responseInterceptor())
        .build()) {
      client.execute(new HttpGet(URI.create("https://www.google.com"))).close();
    }

    try (CloseableHttpClient client = tracer.httpClientBuilder()
        .disableAutomaticRetries()
        .build()) {
      client.execute(new HttpGet(URI.create("https://www.google.com"))).close();
    }
  }
}