package brave.sparkjava;

import brave.ServerHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.servlet.TracingFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import spark.ExceptionHandler;
import spark.Request;
import zipkin.Constants;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

public final class SparkTracing {

  /** Creates a tracing filter with defaults. Use {@link #builder(Tracing)} to customize. */
  public static SparkTracing create(Tracing tracing) {
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

    public SparkTracing build() {
      return new SparkTracing(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends TracingFilter.Config {

  }

  final Tracer tracer;
  final ServerHandler<HttpServletRequest, HttpServletResponse> serverHandler;
  final TraceContext.Extractor<Request> contextExtractor;

  SparkTracing(Builder builder) {
    tracer = builder.tracing.tracer();
    serverHandler = ServerHandler.create(builder.config);
    contextExtractor = builder.tracing.propagation().extractor(Request::headers);
  }

  public spark.Filter before() {
    return (request, response) -> {
      TraceContextOrSamplingFlags contextOrFlags = contextExtractor.extract(request);
      Span span = contextOrFlags.context() != null
          ? tracer.joinSpan(contextOrFlags.context())
          : tracer.newTrace(contextOrFlags.samplingFlags());
      try {
        serverHandler.handleReceive(request.raw(), span);
        request.attribute(Span.class.getName(), span);
        request.attribute(Tracer.SpanInScope.class.getName(), tracer.withSpanInScope(span));
      } catch (RuntimeException e) {
        throw serverHandler.handleError(e, span);
      }
    };
  }

  public spark.Filter afterAfter() {
    return (request, response) -> {
      Span span = request.attribute(Span.class.getName());
      ((Tracer.SpanInScope) request.attribute(Tracer.SpanInScope.class.getName())).close();
      serverHandler.handleSend(response.raw(), span);
    };
  }

  public ExceptionHandler exception(ExceptionHandler delegate) {
    return (exception, request, response) -> {
      try {
        String message = exception.getMessage();
        if (message == null) message = exception.getClass().getSimpleName();
        Span span = request.attribute(Span.class.getName());
        span.tag(Constants.ERROR, message);
      } finally {
        delegate.handle(exception, request, response);
      }
    };
  }
}