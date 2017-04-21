package brave.spring.servlet;

import brave.ServerHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.servlet.TracingFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

@Configuration // not final because of @Configuration
public class TracingHandlerInterceptor extends HandlerInterceptorAdapter {

  /** Creates a tracing filter with defaults. Use {@link #builder(Tracing)} to customize. */
  public static TracingHandlerInterceptor create(Tracing tracing) {
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

    public TracingHandlerInterceptor build() {
      return new TracingHandlerInterceptor(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends TracingFilter.Config {
  }

  final Tracer tracer;
  final ServerHandler<HttpServletRequest, HttpServletResponse> serverHandler;
  final TraceContext.Extractor<HttpServletRequest> contextExtractor;

  @Autowired // internal
  TracingHandlerInterceptor(Tracing tracing, Config config) {
    this(builder(tracing).config(config));
  }

  TracingHandlerInterceptor(Builder builder) {
    tracer = builder.tracing.tracer();
    serverHandler = ServerHandler.create(builder.config);
    contextExtractor = builder.tracing.propagation().extractor(HttpServletRequest::getHeader);
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
      Object handler) {
    if (request.getAttribute(Span.class.getName()) != null) {
      return true; // already handled (possibly due to async request)
    }

    TraceContextOrSamplingFlags contextOrFlags = contextExtractor.extract(request);
    Span span = contextOrFlags.context() != null
        ? tracer.joinSpan(contextOrFlags.context())
        : tracer.newTrace(contextOrFlags.samplingFlags());
    try {
      serverHandler.handleReceive(request, span);
      request.setAttribute(Span.class.getName(), span);
      request.setAttribute(SpanInScope.class.getName(), tracer.withSpanInScope(span));
    } catch (RuntimeException e) {
      throw serverHandler.handleError(e, span);
    }
    return true;
  }

  @Override
  public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
      Object handler, Exception ex) {
    Span span = (Span) request.getAttribute(Span.class.getName());
    ((SpanInScope) request.getAttribute(SpanInScope.class.getName())).close();
    if (ex != null) {
      serverHandler.handleError(ex, span);
    } else {
      serverHandler.handleSend(response, span);
    }
  }
}