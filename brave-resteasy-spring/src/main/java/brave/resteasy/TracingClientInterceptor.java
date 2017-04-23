package brave.resteasy;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.TraceContext;
import javax.ws.rs.ext.Provider;
import org.jboss.resteasy.annotations.interception.ClientInterceptor;
import org.jboss.resteasy.client.ClientRequest;
import org.jboss.resteasy.client.ClientResponse;
import org.jboss.resteasy.spi.interception.ClientExecutionContext;
import org.jboss.resteasy.spi.interception.ClientExecutionInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

@Component
@Provider
@ClientInterceptor
public class TracingClientInterceptor implements ClientExecutionInterceptor {

  /** Creates a tracing interceptor with defaults. Use {@link #builder(Tracing)} to customize. */
  public static TracingClientInterceptor create(Tracing tracing) {
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

    public TracingClientInterceptor build() {
      return new TracingClientInterceptor(this);
    }
  }

  // Not final so it can be overridden to customize tags
  public static class Config extends ClientHandler.Config<ClientRequest, ClientResponse> {

    @Override protected Parser<ClientRequest, String> spanNameParser() {
      return ClientRequest::getHttpMethod;
    }

    @Override protected TagsParser<ClientRequest> requestTagsParser() {
      return (req, span) -> {
        try {
          span.tag(TraceKeys.HTTP_URL, req.getUri().toString());
        } catch (Exception e) {
        }
      };
    }

    @Override protected TagsParser<ClientResponse> responseTagsParser() {
      return (res, span) -> {
        int httpStatus = res.getStatus();
        if (httpStatus < 200 || httpStatus > 299) {
          span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(httpStatus));
        }
      };
    }
  }

  final Tracer tracer;
  final ClientHandler<ClientRequest, ClientResponse> handler;
  final TraceContext.Injector<ClientRequest> injector;

  @Autowired // internal
  TracingClientInterceptor(Tracing tracing, Config config) {
    this(builder(tracing).config(config));
  }

  TracingClientInterceptor(Builder builder) {
    tracer = builder.tracing.tracer();
    handler = ClientHandler.create(builder.config);
    injector = builder.tracing.propagation().injector(ClientRequest::header);
  }

  @Override
  public ClientResponse<?> execute(ClientExecutionContext ctx) throws Exception {
    ClientRequest request = ctx.getRequest();
    Span span = tracer.nextSpan();
    handler.handleSend(request, span);
    injector.inject(span.context(), request);
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return handler.handleReceive(ctx.proceed(), span);
    } catch (Exception e) {
      throw handler.handleError(e, span);
    }
  }
}