package brave.okhttp3;

import brave.ClientHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.TraceContext;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import zipkin.TraceKeys;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

public final class OkHttpTracing {

  /** Creates trace instrumentation with defaults. Use {@link #builder(Tracing)} to customize. */
  public static OkHttpTracing create(Tracing tracing) {
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

    public OkHttpTracing build() {
      return new OkHttpTracing(this);
    }
  }

  public static class Config extends ClientHandler.Config<Request, Response> {

    @Override protected Parser<Request, String> spanNameParser() {
      return Request::method;
    }

    @Override protected TagsParser<Request> requestTagsParser() {
      return (req, span) -> span.tag(TraceKeys.HTTP_URL, req.url().toString());
    }

    @Override protected TagsParser<Response> responseTagsParser() {
      return (res, span) -> {
        if (!res.isSuccessful()) {
          span.tag(TraceKeys.HTTP_STATUS_CODE, String.valueOf(res.code()));
        }
      };
    }
  }

  final Tracer tracer;
  final TraceContext.Injector<Request.Builder> injector;
  final ClientHandler<Request, Response> clientHandler;

  OkHttpTracing(Builder builder) {
    this.tracer = builder.tracing.tracer();
    this.injector = builder.tracing.propagation().injector(Request.Builder::addHeader);
    this.clientHandler = ClientHandler.create(builder.config);
  }

  /**
   * This internally adds an interceptor which runs before others. That means any interceptors in
   * the input can access the current span via {@link Tracer#currentSpan()}
   */
  // NOTE: this is a call factory vs a normal interceptor because the current span can otherwise get
  // lost when there's a backlog.
  public Call.Factory callFactory(OkHttpClient ok) {
    return new TracingCallFactory(ok);
  }

  class TracingCallFactory implements Call.Factory {
    final OkHttpClient ok;

    TracingCallFactory(OkHttpClient ok) {
      this.ok = ok;
    }

    @Override public Call newCall(Request request) {
      Span span = tracer.nextSpan();
      OkHttpClient.Builder b = ok.newBuilder();
      b.interceptors().add(0, chain -> {
        clientHandler.handleSend(request, span);
        Request.Builder builder = request.newBuilder();
        injector.inject(span.context(), builder);
        try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
          return clientHandler.handleReceive(chain.proceed(builder.build()), span);
        } catch (IOException e) { // catch repeated because handleError cannot implement multi-catch
          throw clientHandler.handleError(e, span);
        } catch (RuntimeException e) {
          throw clientHandler.handleError(e, span);
        }
      });
      return b.build().newCall(request);
    }
  }
}