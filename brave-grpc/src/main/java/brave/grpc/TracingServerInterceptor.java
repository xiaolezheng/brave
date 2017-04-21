package brave.grpc;

import brave.ServerHandler;
import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.parser.Parser;
import brave.parser.TagsParser;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

public class TracingServerInterceptor implements ServerInterceptor {

  /** Creates a tracing interceptor with defaults. Use {@link #builder(Tracing)} to customize. */
  public static TracingServerInterceptor create(Tracing tracing) {
    return builder(tracing).build();
  }

  public static TracingServerInterceptor.Builder builder(Tracing tracing) {
    return new TracingServerInterceptor.Builder(tracing);
  }

  public static final class Builder {
    final Tracing tracing;
    TracingServerInterceptor.Config config = new TracingServerInterceptor.Config();

    Builder(Tracing tracing) { // intentionally hidden
      this.tracing = checkNotNull(tracing, "tracing");
    }

    public TracingServerInterceptor.Builder config(TracingServerInterceptor.Config config) {
      this.config = checkNotNull(config, "config");
      return this;
    }

    public TracingServerInterceptor build() {
      return new TracingServerInterceptor(this);
    }
  }

  // Not final so it can be overridden to customize tags
  // anonymous classes vs lambdas as retrolambda fails on GRPC classes in circleci
  public static class Config extends ServerHandler.Config<ServerCall, Status> {

    @Override protected Parser<ServerCall, String> spanNameParser() {
      return new Parser<ServerCall, String>() {
        @Override public String parse(ServerCall c) {
          return c.getMethodDescriptor().getFullMethodName();
        }
      };
    }

    @Override protected TagsParser<ServerCall> requestTagsParser() {
      return new TagsParser<ServerCall>() {
        @Override public void addTagsToSpan(ServerCall req, Span span) {
        }
      };
    }

    @Override protected TagsParser<Status> responseTagsParser() {
      return new TagsParser<Status>() {
        @Override public void addTagsToSpan(Status status, Span span) {
          if (!status.getCode().equals(Status.Code.OK)) {
            span.tag("grpc.status_code", String.valueOf(status.getCode()));
          }
        }
      };
    }
  }

  final Tracer tracer;
  final ServerHandler<ServerCall, Status> serverHandler;
  final TraceContext.Extractor<Metadata> contextExtractor;

  TracingServerInterceptor(Builder builder) {
    tracer = builder.tracing.tracer();
    serverHandler = ServerHandler.create(builder.config);
    contextExtractor = builder.tracing.propagationFactory().create(AsciiMetadataKeyFactory.INSTANCE)
        .extractor(new Propagation.Getter<Metadata, Metadata.Key<String>>() { // retrolambda no like
          @Override public String get(Metadata metadata, Metadata.Key<String> key) {
            return metadata.get(key);
          }
        });
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
      final Metadata requestHeaders, final ServerCallHandler<ReqT, RespT> next) {
    TraceContextOrSamplingFlags contextOrFlags = contextExtractor.extract(requestHeaders);
    Span span = contextOrFlags.context() != null
        ? tracer.joinSpan(contextOrFlags.context())
        : tracer.newTrace(contextOrFlags.samplingFlags());
    return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
      @Override
      public void request(int numMessages) {
        try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
          serverHandler.handleReceive(call, span);
          super.request(numMessages);
        } catch (RuntimeException e) {
          throw serverHandler.handleError(e, span);
        }
      }

      @Override
      public void close(Status status, Metadata trailers) {
        try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
          serverHandler.handleSend(status, span);
          super.close(status, trailers);
        } catch (RuntimeException e) {
          throw serverHandler.handleError(e, span);
        }
      }
    }, requestHeaders);
  }
}