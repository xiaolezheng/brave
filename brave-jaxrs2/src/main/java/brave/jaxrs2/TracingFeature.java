package brave.jaxrs2;

import brave.Tracing;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;

import static com.github.kristofa.brave.internal.Util.checkNotNull;

@Provider
public final class TracingFeature implements Feature {

  /** Creates a tracing feature with defaults. Use {@link #builder(Tracing)} to customize. */
  public static TracingFeature create(Tracing tracing) {
    return new Builder(tracing).build();
  }

  public static Builder builder(Tracing tracing) {
    return new Builder(tracing);
  }

  public static final class Builder {
    final Tracing tracing;
    TracingClientFilter.Config clientConfig = new TracingClientFilter.Config();
    TracingContainerFilter.Config containerConfig = new TracingContainerFilter.Config();

    Builder(Tracing tracing) { // intentionally hidden
      this.tracing = checkNotNull(tracing, "tracing");
    }

    public Builder clientConfig(TracingClientFilter.Config clientConfig) {
      this.clientConfig = checkNotNull(clientConfig, "clientConfig");
      return this;
    }

    public Builder containerConfig(TracingContainerFilter.Config containerConfig) {
      this.containerConfig = checkNotNull(containerConfig, "containerConfig");
      return this;
    }

    public TracingFeature build() {
      return new TracingFeature(this);
    }
  }

  final Tracing tracing;
  final TracingClientFilter.Config clientConfig;
  final TracingContainerFilter.Config containerConfig;

  TracingFeature(Builder b) { // intentionally hidden
    tracing = b.tracing;
    clientConfig = b.clientConfig;
    containerConfig = b.containerConfig;
  }

  @Override
  public boolean configure(FeatureContext context) {
    context.register(TracingClientFilter.builder(tracing).config(clientConfig).build());
    context.register(TracingContainerFilter.builder(tracing).config(containerConfig).build());
    return true;
  }
}
