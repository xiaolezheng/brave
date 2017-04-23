package brave.resteasy;

import brave.ServerHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.List;
import javax.ws.rs.ext.Provider;
import org.jboss.resteasy.annotations.interception.ServerInterceptor;
import org.jboss.resteasy.core.ResourceMethod;
import org.jboss.resteasy.core.ServerResponse;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.interception.PostProcessInterceptor;
import org.jboss.resteasy.spi.interception.PreProcessInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

@Component
@Provider
@ServerInterceptor
public class TracingServerInterceptor implements PreProcessInterceptor, PostProcessInterceptor {

  /** Creates a tracing interceptor with defaults. Use {@link #builder(Tracing)} to customize. */
  public static TracingServerInterceptor create(Tracing tracing) {
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

    public TracingServerInterceptor build() {
      return new TracingServerInterceptor(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends ServerHandler.Config<HttpRequest, ServerResponse> {

    @Override protected Parser<HttpRequest, String> spanNameParser() {
      return HttpRequest::getHttpMethod;
    }

    @Override protected TagsParser<HttpRequest> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL, req.getUri().getRequestUri().toString());
    }

    @Override protected TagsParser<ServerResponse> responseTagsParser() {
      return (res, span) -> {
        int httpStatus = res.getStatus();
        if (httpStatus < 200 || httpStatus > 299) {
          span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
        }
      };
    }
  }

  final Tracer tracer;
  final ServerHandler<HttpRequest, ServerResponse> serverHandler;
  final TraceContext.Extractor<HttpRequest> contextExtractor;

  @Autowired // internal
  TracingServerInterceptor(Tracing tracing, Config config) {
    this(builder(tracing).config(config));
  }

  TracingServerInterceptor(Builder builder) {
    tracer = builder.tracing.tracer();
    serverHandler = ServerHandler.create(builder.config);
    contextExtractor = builder.tracing.propagation().extractor((carrier, key) -> {
      List<String> headers = carrier.getHttpHeaders().getRequestHeader(key);
      return headers == null || headers.isEmpty() ? null : headers.get(0);
    });
  }

  @Override public ServerResponse preProcess(HttpRequest request, ResourceMethod resourceMethod) {
    TraceContextOrSamplingFlags contextOrFlags = contextExtractor.extract(request);
    Span span = contextOrFlags.context() != null
        ? tracer.joinSpan(contextOrFlags.context())
        : tracer.newTrace(contextOrFlags.samplingFlags());
    serverHandler.handleReceive(request, span);
    request.setAttribute(Tracer.SpanInScope.class.getName(), true);
    spanInScope.set(tracer.withSpanInScope(span));
    return null;
  }

  /**
   * There's no attribute namespace shared across request and response. Hence, we need to save off
   * a reference to the span in scope, so that we can close it in the response.
   */
  final ThreadLocal<Tracer.SpanInScope> spanInScope = new ThreadLocal<>();

  @Override public void postProcess(ServerResponse response) {
    Span span = tracer.currentSpan();
    serverHandler.handleSend(response, span);
    try (Tracer.SpanInScope ws = spanInScope.get()) {
      spanInScope.remove();
    }
  }
}