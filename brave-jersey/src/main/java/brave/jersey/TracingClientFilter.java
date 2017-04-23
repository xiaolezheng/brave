package brave.jersey;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.TraceContext;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientRequest;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.filter.ClientFilter;
import javax.ws.rs.core.MultivaluedMap;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

/**
 * NOTE: For other interceptors to see the {@link Tracer#currentSpan()} representing this operation,
 * this filter needs to be added last.
 */
public class TracingClientFilter extends ClientFilter {

  /** Creates a tracing filter with defaults. Use {@link #builder(Tracing)} to customize. */
  public static TracingClientFilter create(Tracing tracing) {
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

    public TracingClientFilter build() {
      return new TracingClientFilter(this);
    }
  }

  public static class Config extends ClientHandler.Config<ClientRequest, ClientResponse> {

    @Override protected Parser<ClientRequest, String> spanNameParser() {
      return ClientRequest::getMethod;
    }

    @Override protected TagsParser<ClientRequest> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL, req.getURI().toString());
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
  final TraceContext.Injector<MultivaluedMap> injector;

  TracingClientFilter(Builder builder) {
    tracer = builder.tracing.tracer();
    handler = ClientHandler.create(builder.config);
    injector = builder.tracing.propagation().injector(MultivaluedMap::putSingle);
  }

  @Override
  public ClientResponse handle(ClientRequest request) throws ClientHandlerException {
    Span span = tracer.nextSpan();
    handler.handleSend(request, span);
    injector.inject(span.context(), request.getHeaders());
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return handler.handleReceive(getNext().handle(request), span);
    } catch (ClientHandlerException e) {
      throw handler.handleError(e, span);
    }
  }
}